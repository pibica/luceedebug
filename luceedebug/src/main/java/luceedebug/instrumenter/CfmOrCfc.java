package luceedebug.instrumenter;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

public class CfmOrCfc extends ClassVisitor {
    private Type thisType = null; // is not initialized until `visit`
    private String sourceName = "??????"; // is not initialized until `visitSource`

    public CfmOrCfc(int api, ClassWriter cw, String className) {
        super(api, cw);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces
    ) {
        this.thisType = Type.getType("L" + name + ";");
        super.visit(version, access, name, signature, superName, interfaces);
    }

    static class IDebugManager_t {
        static final Type type = Type.getType("Lluceedebug/IDebugManager;");
        // pushCfFrame : (_ : PageContext, filenameAbsPath : string, distanceToFrame : int) => void
        static final Method m_pushCfFrame = Method.getMethod("void pushCfFrame(lucee.runtime.PageContext, String)");
        // pushCfFunctionDefaultValueInitializationFrame : (_ : PageContext, filenameAbsPath : string, distanceToFrame : int) => void
        static final Method m_pushCfFunctionDefaultValueInitializationFrame = Method.getMethod("void pushCfFunctionDefaultValueInitializationFrame(lucee.runtime.PageContext, String)");
        // popCfFrame : () => void 
        static final Method m_popCfFrame = Method.getMethod("void popCfFrame()");
        // step : (currentLine : int) => void
        static final Method m_step = Method.getMethod("void luceedebug_stepNotificationEntry_step(int)");
        // stepAfterCompletedUdfCall : () => void
        static final Method m_stepAfterCompletedUdfCall = Method.getMethod("void luceedebug_stepNotificationEntry_stepAfterCompletedUdfCall()");
    }

    static class GlobalIDebugManagerHolder_t {
        static final Type type = Type.getType("Lluceedebug/GlobalIDebugManagerHolder;");
    }

    @Override
    public void visitSource(String source, String debug) {
        this.sourceName = source;
        super.visitSource(source, debug);
    }

    /**
     * prevents recursively trying to wrap wrapper methods
     */
    private boolean isWrappingMethod = false;

    /**
     * wrap some cf call like
     * 
     * Object udfCallX(...) throws ... {
     *    try {
     *       DebugManager.pushCfFrame();
     *       return __luceedebug__udfCallX(...args); // "real" method is renamed
     *    }
     *    finally {
     *       DebugManager.popCfFrame();
     *    }
     * }
     */
    private void createWrapperMethod(
        int access,
        String name,
        String descriptor,
        String signature,
        String[] exceptions,
        String delegateToName
    ) {
        try {
            isWrappingMethod = true;

            final var argCount = Type.getArgumentTypes(descriptor).length;
            final var mv = visitMethod(access, name, descriptor, signature, exceptions);
            final var ga = new GeneratorAdapter(mv, access, name, descriptor);

            final var tryStart = ga.mark();

            //
            // try
            //
            {
                // [<empty-stack>]

                // pushCfFrame
                {
                    ga.getStatic(GlobalIDebugManagerHolder_t.type, "debugManager", IDebugManager_t.type);
                    // [IDebugManager_t]

                    ga.loadArg(0); // should be PageContextImpl as PageContext
                    // [IDebugManager_t, PageContext]
                    
                    ga.push(sourceName);
                    // [IDebugManager_t, PageContext, String]

                    if (name.startsWith("udfDefaultValue")) {
                        ga.invokeInterface(IDebugManager_t.type, IDebugManager_t.m_pushCfFunctionDefaultValueInitializationFrame);
                    }
                    else {
                        ga.invokeInterface(IDebugManager_t.type, IDebugManager_t.m_pushCfFrame);
                    }
                    // [<empty>]
                }

                ga.loadThis();
                // [<this>]

                for (int i = 0; i < argCount; ++i) {
                    ga.loadArg(i);
                }
                // [<this>, ...args]

                ga.invokeVirtual(thisType, new Method(delegateToName, descriptor));
                // [<return-value>]

                // popCfFrame
                {
                    ga.getStatic(GlobalIDebugManagerHolder_t.type, "debugManager", IDebugManager_t.type);
                    ga.invokeInterface(IDebugManager_t.type, IDebugManager_t.m_popCfFrame);

                    // non-exceptional function return gets a step notification,
                    // with the exception of udfDefaultValue frames (serves to set function default args), which behave sort of weirdly
                    // (as if they're merged with their associated UDF? not clear at the moment)
                    if (!name.equals("udfDefaultValue")) {
                        ga.getStatic(GlobalIDebugManagerHolder_t.type, "debugManager", IDebugManager_t.type);
                        ga.invokeInterface(IDebugManager_t.type, IDebugManager_t.m_stepAfterCompletedUdfCall);
                    }
                }
                
                // [<return-value>]

                ga.returnValue();
            }

            final var tryEnd = ga.mark();

            //
            // catch
            //
            {
                // [<exception-object>]

                // popCfFrame
                {
                    ga.getStatic(GlobalIDebugManagerHolder_t.type, "debugManager", IDebugManager_t.type);
                    ga.invokeInterface(IDebugManager_t.type, IDebugManager_t.m_popCfFrame);

                    //
                    // n.b exceptional function return DOES NOT get a step notification
                    //
                }
                // [<exception-object>]

                ga.throwException();
            }

            ga.visitTryCatchBlock(tryStart, tryEnd, tryEnd, null);

            ga.endMethod();
        }
        finally {
            isWrappingMethod = false;
        }
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions
    ) {
        // `call` is the main entry point to a cfm page (how about cfc's, is that the same?)
        // `udfCall(<N>)?` are generated -- per function statement? how about per function expression?
        // sometimes they can have empty bodies (at least exactly "udfCall"), even if subsequently indexed methods don't
        // e.g. `udfCall` is an empty method with zero bytecodes, but `udfCall1` is non-empty, etc.
        

        if (!isWrappingMethod && (
            name.equals("call")
            || name.startsWith("call_") // call_001, call_002, etc.
            || name.startsWith("udfCall")
            || name.equals("initComponent")
            || name.equals("newInstance")
            || name.equals("threadCall")
            || name.startsWith("udfDefaultValue")
            || name.equals("staticConstructor")
        )) {
            final String delegateToName = "__luceedebug__" + name;
            createWrapperMethod(access, name, descriptor, signature, exceptions, delegateToName);

            final var mv = super.visitMethod(access, delegateToName, descriptor, signature, exceptions);

            return new AdviceAdapter(this.api, mv, access, delegateToName, descriptor) {
                @Override
                public void visitLineNumber(int line, Label start) {
                    // step
                    {
                        this.getStatic(GlobalIDebugManagerHolder_t.type, "debugManager", IDebugManager_t.type);
                        this.push(line);
                        this.invokeInterface(IDebugManager_t.type, IDebugManager_t.m_step);
                    }

                    super.visitLineNumber(line, this.mark());
                }
            };
        }
        else {
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }
}
