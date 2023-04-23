package dwr.raw_packet

import scala.collection.mutable.ArrayBuffer
import scala.math.{min}
import scala.annotation.tailrec

import dwr.reader._
import dwr.utils.ByteWrangler

class PacketBuilder {
  val length = new ArrayBuffer[Byte](4)
  val id = new ArrayBuffer[Byte](4)
  val last3 = new ArrayBuffer[Byte](3)
  val body = new ArrayBuffer[Byte](64)
  val raw = new ArrayBuffer[Byte](64)
  private val parsedLength = LengthReader()
  
  private final class LengthReader() {
    private final inline val NIL = -1;
    private var cached_ = NIL

    def get() : Int =
      if length.length != 4
      then throw new RuntimeException("the packet `length` field have exactly length 4")
      else {
        if cached_ == NIL
        then {
          cached_ = ByteWrangler.beI32_to_int32(length.toArray)
          cached_
        }
        else cached_
      }

    def clear() : Unit = cached_ = NIL
  }


  private def reset() : Unit =
    length.clear()
    id.clear()
    last3.clear()
    body.clear()
    raw.clear()
    parsedLength.clear()

  def consumeAndReset() : FinishedPacket =
    try {
      if isReply
      then 
        val last3Reader = CheckedReader(last3.toArray)
        Reply(
          length = packetLengthIncludingHeader,
          id = readID(),
          flags = last3Reader.read_int8(),
          errorCode = last3Reader.read_int16(),
          body = body.toArray,
          raw = raw.toArray
        )
      else
        val last3Reader = CheckedReader(last3.toArray)
        Command(
          length = packetLengthIncludingHeader,
          id = readID(),
          flags = last3Reader.read_int8(),
          commandSet = last3Reader.read_int8(),
          command = last3Reader.read_int8(),
          body = body.toArray,
          raw = raw.toArray
        )
    }
    finally {
      reset()
    }
  
  def flags : Byte = last3(0)
  def isReply : Boolean = (flags & 0x80) > 0
  def isCommand : Boolean = !isReply

  private final inline val HEADER_LENGTH = 11

  def packetLengthIncludingHeader : Int = parsedLength.get()
  def packetLengthNoHeader : Int = parsedLength.get() - HEADER_LENGTH

  def readID() : Int = CheckedReader(id.toArray).read_int32()
}

sealed abstract class FinishedPacket {
  protected var id_ : Int
  protected var flags_ : Byte
  protected var body_ : Array[Byte]
  protected var raw_ : Array[Byte]

  def withSwappedID(newID: Int, f: FinishedPacket => Unit) : Unit = this.synchronized {
    val savedID = ID
    try {
      setID(newID)
      f(this)
    }
    finally {
      setID(savedID)
    }
  }
  
  def ID : Int = id_
  def flags : Byte = flags_
  def body : Array[Byte] = body_
  def raw : Array[Byte] = raw_

  private def setID(v: Int) : Unit =
    id_ = v
    val raw = ByteWrangler.int32_to_beI32(v)
    raw_(4) = raw(0)
    raw_(5) = raw(1)
    raw_(6) = raw(2)
    raw_(7) = raw(3)
}

class Command(
  val length: Int,
  id: Int,
  flags: Byte,
  val commandSet: Byte,
  val command: Byte,
  body: Array[Byte],
  raw: Array[Byte]
) extends FinishedPacket {
  protected var id_ = id
  protected var flags_ = flags
  protected var body_ = body
  protected var raw_ = raw
}

class Reply(
  val length: Int,
  id: Int,
  flags: Byte,
  val errorCode: Short,
  body: Array[Byte],
  raw: Array[Byte]
) extends FinishedPacket {
  protected var id_ = id
  protected var flags_ = flags
  protected var body_ = body
  protected var raw_ = raw
}

class PacketParser {
  private val HEADER_LENGTH = 11
  private enum State:
    case HeaderLength()
    case HeaderID()
    case HeaderLast3()
    case Body()
    case Done()
  
  private def startState : State = State.HeaderLength()

  private var state = startState
  private var builder = PacketBuilder() 

  private case class ParseResult(nextState: State, remaining: Array[Byte])

  private def pushByte(builderTarget: ArrayBuffer[Byte], byte: Byte) : Unit =
    builderTarget += byte
    builder.raw += byte
  
  private def parseLength(buffer: Array[Byte]) : ParseResult =
    val consumable = min(buffer.length, 4 - builder.length.length)
    var consumed = 0
    val checkedReader = CheckedReader(buffer)
    while consumed != consumable do
      pushByte(builder.length, checkedReader.read_int8())
      consumed += 1

    ParseResult(
      nextState = if builder.length.length == 4 then State.HeaderID() else State.HeaderLength(),
      remaining = buffer.slice(consumed, buffer.length)
    )

  private def parseId(buffer: Array[Byte]) : ParseResult =
    var consumable = min(buffer.length, 4 - builder.id.length)
    var consumed = 0
    val checkedReader = CheckedReader(buffer)
    while consumed != consumable do
      pushByte(builder.id, checkedReader.read_int8())
      consumed += 1

    ParseResult(
      nextState = if builder.id.length == 4 then State.HeaderLast3() else State.HeaderID(),
      remaining = buffer.slice(consumed, buffer.length)
    )

  private def parseLast3(buffer: Array[Byte]) : ParseResult =
    val consumable = min(buffer.length, 3 - builder.last3.length)
    var consumed = 0
    val checkedReader = CheckedReader(buffer)
    while consumed != consumable do
      pushByte(builder.last3, checkedReader.read_int8())
      consumed += 1

    ParseResult(
      nextState = if builder.last3.length == 3
        then
          if builder.packetLengthIncludingHeader == HEADER_LENGTH
          then State.Done() // no body, we're done
          else State.Body() // still need to parse body
        else State.HeaderLast3(),
      remaining = buffer.slice(consumable, buffer.length)
    )
  
  private def parseBody(buffer: Array[Byte]) : ParseResult =
    val consumable = min(buffer.length, builder.packetLengthNoHeader - builder.body.length)

    if consumable < 0
    then throw new RuntimeException(s"buffer consumable count was LT 0: ${consumable}")

    var consumed = 0
    val checkedReader = CheckedReader(buffer)
    while consumed != consumable do
      pushByte(builder.body, checkedReader.read_int8())
      consumed += 1

    if (builder.body.length > builder.packetLengthNoHeader)
    then throw new RuntimeException(s"current body length is ${builder.body.length} but target size is ${builder.packetLengthNoHeader} (all sans header)")
    
    println("body length is " + builder.body.length + ", expected body length is " + (builder.packetLengthNoHeader))
    
    ParseResult(
      nextState = if builder.body.length == builder.packetLengthNoHeader then State.Done() else State.Body(),
      remaining = buffer.slice(consumed, buffer.length)
    )

  private def unreachable[T] : T = throw RuntimeException("unreachable")

  private def parse(buffer: Array[Byte]) : ParseResult =
    state match
      case _ : State.HeaderLength => parseLength(buffer)
      case _ : State.HeaderID => parseId(buffer)
      case _ : State.HeaderLast3 => parseLast3(buffer)
      case _ : State.Body => parseBody(buffer)
      case _ : State.Done => unreachable

  @tailrec
  private def consume(buffer: Array[Byte], completed: List[FinishedPacket]) : List[FinishedPacket] =
    if buffer.length == 0
    then return completed

    parse(buffer) match
      case ParseResult(State.Done(), remaining) =>
        state = startState
        consume(remaining, completed :+ builder.consumeAndReset())
      case ParseResult(nextState, remaining) =>
        state = nextState
        consume(remaining, completed)

  def consume(buffer: Array[Byte]) : List[FinishedPacket] = consume(buffer, List())
}
