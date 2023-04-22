package dwr.reader

import dwr.utils.{ByteWrangler}
import dwr.jdwp.{Tag, TaggedObjectID, Location, Value}
import dwr.jdwp.packet.reply.virtual_machine.{IdSizes}
import java.nio.charset.StandardCharsets

/**
 * java is big endian, network is big endian
 */
class CheckedReader(raw: Array[Byte]) {
  private var index : Int = 0

  private def getAndAdvance(len: Int) : Array[Byte] =
    val ret = raw.slice(index, index + len)
    ret(len - 1) // dies on out-of-bounds right? (slice allows out-of-bounds slices...?)
    index += len
    ret

  def readN(n: Int) : Array[Byte] = getAndAdvance(n)
  def read_int8(): Byte = ByteWrangler.beI8_to_int8(readN(1))
  def read_int16(): Short = ByteWrangler.beI16_to_int16(readN(2))
  def read_int32() : Int = ByteWrangler.beI32_to_int32(readN(4))
  def read_int64() : Long = ByteWrangler.beI64_to_int64(readN(8))
}

class JdwpSizedReader(idSizes: IdSizes, raw: Array[Byte]) extends CheckedReader(raw) {
  private def read4Or8(size: Int) : Long =
    size match
      case 4 => read_int32().asInstanceOf[Long]
      case 8 => read_int64()
      case _ => throw new RuntimeException(s"unexpected field size ${idSizes.fieldIDSize}")
  
  def readBoolean() : Boolean = if read_int8() == 0 then false else true

  def readFieldID() = read4Or8(idSizes.fieldIDSize)
  def readMethodID() = read4Or8(idSizes.methodIDSize)
  def readObjectID() = read4Or8(idSizes.objectIDSize)
  def readReferenceTypeID() = read4Or8(idSizes.referenceTypeIDSize)
  def readFrameID() = read4Or8(idSizes.frameIDSize)

  def readThreadID() = readObjectID()
  def readThreadGroupID() = readObjectID()
  def readStringID() = readObjectID()
  def readClassLoaderID() = readObjectID()
  def readClassObjectID() = readObjectID()
  def readArrayID() = readObjectID()
  def readTaggedObjectID() =
    val tag = read_int8()
    val objID = readObjectID()
    TaggedObjectID(tag, objID)

  def readClassID() = readReferenceTypeID()
  def readInterfaceID() = readReferenceTypeID()
  def readArrayTypeID() = readReferenceTypeID()

  def readString() : String =
    val len = read_int32()
    val bytes = readN(len)
    String(bytes, StandardCharsets.UTF_8)

  def readLocation() : Location =
    Location(
      typeTag = read_int8(),
      classID = readClassID(),
      methodID = readMethodID(),
      index = read_int64(),
    )

  def readValue() : Value =
    val tag = read_int8()
    val value = tag match
      case Tag.ARRAY => readObjectID().asInstanceOf[Long]
      case Tag.BYTE => read_int8().asInstanceOf[Long]
      case Tag.CHAR => read_int16().asInstanceOf[Long]
      case Tag.OBJECT => readObjectID().asInstanceOf[Long]
      case Tag.FLOAT => read_int32().asInstanceOf[Long]
      case Tag.DOUBLE => read_int64().asInstanceOf[Long]
      case Tag.INT => read_int32().asInstanceOf[Long]
      case Tag.LONG => read_int64().asInstanceOf[Long]
      case Tag.SHORT => read_int16().asInstanceOf[Long]
      case Tag.VOID => 0.asInstanceOf[Long]
      case Tag.BOOLEAN => read_int8().asInstanceOf[Long]
      // see docs, this is an objectRef and not a literally encoded Utf8 string
      case Tag.STRING => readObjectID().asInstanceOf[Long]
      case Tag.THREAD => readObjectID().asInstanceOf[Long]
      case Tag.THREAD_GROUP => readObjectID().asInstanceOf[Long]
      case Tag.CLASS_LOADER => readObjectID().asInstanceOf[Long]
      case Tag.CLASS_OBJECT => readObjectID().asInstanceOf[Long]
      case _ => throw new RuntimeException(s"Unexpected type tag '${tag}'")
    Value(tag, value)
    
}