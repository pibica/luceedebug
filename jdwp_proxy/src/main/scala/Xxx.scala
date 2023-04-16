package luceedebug.jdwp_proxy

import luceedebug.jdwp_proxy.JdwpProxy.{HANDSHAKE_BYTES, HANDSHAKE_STRING}

import java.io.{IOException, InputStream, OutputStream}
import java.net.{InetAddress, InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

enum Foo:
  case X(v: Any)
  case Y()

@main
def ok() : Unit =
  JdwpProxy().connect("localhost", 9999)//println(CheckedReader("xpabx".getBytes(StandardCharsets.UTF_8)).readUtf8("xpab"))


class SocketIO(private val inStream: InputStream, private val outStream: OutputStream) {
  private implicit val executionContext : ExecutionContext = ExecutionContext.fromExecutor(
    Executors.newFixedThreadPool(3)
  )

  def chunkedReader(chunkSize: Int) = {
    val buffer = new Array[Byte](chunkSize);
    () => {
      val readSize = inStream.read(buffer)
      buffer.slice(0, readSize)
    }
  }

  def write(bytes: Array[Byte], timeout_ms: Int) : Unit =
    val duration = Duration(timeout_ms, MILLISECONDS)
    Await.result(Future { outStream.write(bytes) }, duration)

  def read(n: Int, timeout_ms: Int) : Array[Byte] =
    val duration = Duration(timeout_ms, MILLISECONDS)
    val bytes = Await.result(Future{inStream.readNBytes(n)}, duration)
    if bytes.length != n
      then throw new IOException(s"Expected ${n} bytes but read ${bytes.length}")
      else bytes
}

class Span(private val buffer: Array[Byte], private val offset: Int, private val xlength: Int)
extends Iterator[Byte]
{
    if offset + xlength > buffer.length then
      throw new RuntimeException(s"Out of bounds span (bufferSize=${buffer.length}, offset=${offset}, length=${xlength})")
    if offset < 0 then
      throw new RuntimeException("Illegal negative offset for Span")
    if xlength < 0 then
      throw new RuntimeException("Illegal negative length for Span")

    private var index : Int = 0

    def apply(i: Int) : Byte =
      if i >= xlength then throw new RuntimeException(s"Out of bounds apply access (bufferSize=${buffer.length}, offset=${offset}, length=${xlength}, i=${i})")
      buffer(offset + i)

    def hasNext() : Boolean = index < xlength
    def next() : Byte =
      val b = buffer(offset + index)
      index += 1
      b

    private def toByteArray: Array[Byte] = buffer.slice(offset, offset + xlength)
    // could start in the middle of a multibyte codepoint ...
    def toStringFromUtf8 : String = String(toByteArray, StandardCharsets.UTF_8)
}

// java is big endian, network is big endian
class CheckedReader(private val raw: Array[Byte]) {
  private var index : Int = 0

  private def getAndAdvance(len: Int) : Span =
    val ret = Span(raw, index, len)
    index += len
    ret

  def readUtf8(s: String) : String =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val span = Span(raw, index, bytes.length)
    for (i <- 0 until bytes.length) {
      val expected = bytes(i)
      val actual = span(i)
      if expected != actual then throw new RuntimeException(s"Expected: `${s}`, actual: `${span.toStringFromUtf8}`")
    }
    s

  def read_int8: Byte =
    val span = getAndAdvance(1)
    span(0)

  def read_int16: Short =
    val span = getAndAdvance(2)
    val ret =
      (span(0) << 8)
        | (span(1) << 0)
    ret.asInstanceOf[Short]

  def read_int32 : Int =
    val span = getAndAdvance(4)
    (span(0) << 24)
      | (span(1) << 16)
      | (span(2) << 8)
      | (span(3) << 0)
}

class JdwpProxy {
  def connect(host: String, port: Int) : Unit =
    val socket = new Socket()
    val inetAddr = new InetSocketAddress(host, port);
    socket.connect(inetAddr, /*ms*/5000);
    val (inStream, outStream) = (socket.getInputStream(), socket.getOutputStream())
    val socketIO = SocketIO(inStream, outStream)

    new Thread(() => messagePump(socketIO)).start()

  def messagePump(socketIO: SocketIO) : Unit =
    socketIO.write(HANDSHAKE_BYTES, 1000)
    val handshakeIn = socketIO.read(HANDSHAKE_STRING.length, 5000)
    CheckedReader(handshakeIn).readUtf8(HANDSHAKE_STRING)

    val readChunk = socketIO.chunkedReader(1024)
    val parser = PacketParser()

    while true do {
      parser.consume(readChunk()) match
        case Some(packet) => 0
        case None => 0
    }
}

class PacketParser {

}

object JdwpProxy {
  private val HANDSHAKE_STRING: String = "JDWP-Handshake"
  private val HANDSHAKE_BYTES: Array[Byte] = HANDSHAKE_STRING.getBytes(StandardCharsets.US_ASCII)
}

class JdwpPacket {}
class JdwpCommandHeader{}
class JdwpReplyHeader(val length: Int, val id: Int, val flags: Byte, val errorCode: Short){}

object CommandPacket {
  def toWire(id: Int, flags: Int, command: JdwpCommand) : Array[Byte] =
    val body = command.toWire
    val length = body.length + 11;
    val flags = 0
    val commandSetID = command.command.getCommentSetID
    val commandID = command.command.getCommandID
    val header = new Array[Byte](0)
    header ++ body
}

object ReplyPacket {
  def fromWire(bytes: Array[Byte]) : Array[Byte] =
    val checkedReader = CheckedReader(bytes)
    val header = readHeader(checkedReader)
    new Array[Byte](0)

  def readHeader(reader: CheckedReader) : JdwpReplyHeader =
    JdwpReplyHeader(
      length = reader.read_int32,
      id = reader.read_int32,
      flags = reader.read_int8,
      errorCode = reader.read_int16,
    )
}

enum Command(commandSetID: Int, commandID: Int):
  def getCommentSetID = commandSetID
  def getCommandID = commandID
  case VirtualMachine_IDSizes extends Command(1, 7)

trait JdwpCommand {
  val command: Command
  def toWire : Array[Byte]
}

trait FromWire[T] {
  def fromWire(buffer: Array[Byte]): T
}

package packet.out:
  class IdSizes() extends JdwpCommand {
    final val command = Command.VirtualMachine_IDSizes
    def toWire = new Array[Byte](0)
  }

package packet.reply:
  class IdSizes(
    val fieldIDSize: Int,
    val methodIDSize: Int,
    val objectIDSize: Int,
    val referenceTypeIDSize: Int,
    val frameIDSize: Int
  ) {}

  object IdSizes extends FromWire[IdSizes] {
    def fromWire(buffer: Array[Byte]) : IdSizes =
      val checkedReader = CheckedReader(buffer)
      IdSizes(
        fieldIDSize = checkedReader.read_int32,
        methodIDSize = checkedReader.read_int32,
        objectIDSize = checkedReader.read_int32,
        referenceTypeIDSize = checkedReader.read_int32,
        frameIDSize = checkedReader.read_int32,
      )
  }