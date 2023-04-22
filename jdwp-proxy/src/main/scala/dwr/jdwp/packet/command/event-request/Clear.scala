package dwr.jdwp.packet.command.event_request

import dwr.jdwp.packet._
import dwr.reader._
import dwr.utils.{ByteWrangler}

class Clear(val eventKind: Byte, val requestID: Int) extends JdwpCommand with BodyToWire {
    val command = Command.EventRequest_Clear
    def bodyToWire() : Array[Byte] =
        val b_requestID = ByteWrangler.int32_to_beI32(requestID)
        Array[Byte](
            eventKind,
            b_requestID(0),
            b_requestID(1),
            b_requestID(2),
            b_requestID(3)
        )
}

object Clear extends BodyFromWire[Clear] {
    def bodyFromWire(idSizes: IdSizes, body: Array[Byte]) : Clear =
        val reader = JdwpSizedReader(idSizes, body)
        Clear(
            eventKind = reader.read_int8(),
            requestID = reader.read_int32()
        )
}