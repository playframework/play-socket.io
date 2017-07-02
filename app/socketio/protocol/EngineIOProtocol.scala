package socketio.protocol

import java.util.Base64
import java.util.regex.Pattern

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import play.api.http.Writeable
import play.api.http.websocket.{BinaryMessage, Message, TextMessage}

import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.streams.Accumulator
import play.api.mvc.WebSocket.MessageFlowTransformer
import play.api.mvc._

import scala.concurrent.ExecutionContext

sealed abstract class EngineIOTransport private (val name: String)

object EngineIOTransport {
  case object Polling extends EngineIOTransport("polling")
  case object WebSocket extends EngineIOTransport("websocket")

  def fromName(name: String) = name match {
    case "polling" => Polling
    case "websocket" => WebSocket
    case _ => throw new RuntimeException("Unknown transport")
  }
}

case class EngineIOOpenMessage(sid: String, upgrades: Seq[EngineIOTransport], pingInterval: FiniteDuration, pingTimeout: FiniteDuration)

object EngineIOOpenMessage {
  implicit val reads: Reads[EngineIOOpenMessage] = (
    (__ \ "sid").read[String] ~
    (__ \ "upgrades").readWithDefault[Seq[String]](Nil).map(_.map(EngineIOTransport.fromName)) ~
    (__ \ "pingInterval").read[Long].map(_.millis) ~
    (__ \ "pingTimeout").read[Long].map(_.millis)
  ).apply(EngineIOOpenMessage.apply _)

  implicit val writes: Writes[EngineIOOpenMessage] = (
    (__ \ "sid").write[String] ~
    (__ \ "upgrades").write[Seq[String]].contramap[Seq[EngineIOTransport]](_.map(_.name)) ~
    (__ \ "pingInterval").write[Long].contramap[FiniteDuration](_.toMillis) ~
    (__ \ "pingTimeout").write[Long].contramap[FiniteDuration](_.toMillis)
  ).apply(o => (o.sid, o.upgrades, o.pingInterval, o.pingTimeout))
}

sealed abstract class EngineIOPacketType private (val id: Int) {
  val stringEncoded: String = id.toString
  val binaryEncoded: ByteString = ByteString(stringEncoded.head)
}

object EngineIOPacketType {
  case object Open extends EngineIOPacketType(0)
  case object Close extends EngineIOPacketType(1)
  case object Ping extends EngineIOPacketType(2)
  case object Pong extends EngineIOPacketType(3)
  case object Message extends EngineIOPacketType(4)
  case object Upgrade extends EngineIOPacketType(5)
  case object Noop extends EngineIOPacketType(6)

  def fromChar(char: Char) = fromBinary(char.toByte)

  def fromBinary(byte: Byte) = byte - '0' match {
    case 0 => Open
    case 1 => Close
    case 2 => Ping
    case 3 => Pong
    case 4 => Message
    case 5 => Upgrade
    case 6 => Noop
    case other => throw EngineIOEncodingException(s"Unknown packet type id: $other")
  }
}

sealed trait EngineIOPacket {
  val typeId: EngineIOPacketType
}

object EngineIOPacket {
  def apply[T: Writes](packetType: EngineIOPacketType, message: T): EngineIOPacket = {
    Utf8EngineIOPacket(packetType, Json.stringify(Json.toJson(message)))
  }

  def apply(packetType: EngineIOPacketType): EngineIOPacket = {
    Utf8EngineIOPacket(packetType, "")
  }

  implicit def writeable(implicit request: RequestHeader): Writeable[EngineIOPacket] = {
    EngineIOPayload.writeable.map[EngineIOPacket](packet => EngineIOPayload(Seq(packet)))
  }

  implicit def webSocketTransformer: MessageFlowTransformer[EngineIOPacket, EngineIOPacket] = new MessageFlowTransformer[EngineIOPacket, EngineIOPacket] {
    override def transform(flow: Flow[EngineIOPacket, EngineIOPacket, _]) = {
      Flow[Message] collect {
        case TextMessage(text) => Utf8EngineIOPacket.decode(text)
        case BinaryMessage(bytes) => BinaryEngineIOPacket.decode(bytes)
      } via flow map {
        case BinaryEngineIOPacket(typeId, bytes) => BinaryMessage(typeId.binaryEncoded ++ bytes)
        case Utf8EngineIOPacket(typeId, text) => TextMessage(typeId.stringEncoded + text)
      }
    }
  }
}

case class BinaryEngineIOPacket(typeId: EngineIOPacketType, data: ByteString) extends EngineIOPacket

object BinaryEngineIOPacket {
  implicit def writeable(implicit request: RequestHeader): Writeable[BinaryEngineIOPacket] = EngineIOPacket.writeable

  def decode(bytes: ByteString): BinaryEngineIOPacket = {
    if (bytes.length < 1) {
      throw EngineIOEncodingException("Cannot decode empty binary packet")
    }

    val packetTypeId = EngineIOPacketType.fromBinary(bytes.head)

    val packetBytes = bytes.drop(1)
    BinaryEngineIOPacket(packetTypeId, packetBytes)
  }
}

case class Utf8EngineIOPacket(typeId: EngineIOPacketType, text: String) extends EngineIOPacket

object Utf8EngineIOPacket {
  implicit def writeable(implicit request: RequestHeader): Writeable[Utf8EngineIOPacket] = EngineIOPacket.writeable

  def decode(text: String): Utf8EngineIOPacket = {
    if (text.length < 1) {
      throw EngineIOEncodingException("Cannot decode empty text packet")
    }

    val packetTypeId = EngineIOPacketType.fromChar(text.head)

    val packetText = text.drop(1)
    Utf8EngineIOPacket(packetTypeId, packetText)
  }
}

case class EngineIOPayload(packets: Seq[EngineIOPacket])

object EngineIOPayload {
  private val ParseInt = "(\\d+)".r

  implicit def writeable(implicit request: RequestHeader): Writeable[EngineIOPayload] = {
    request.getQueryString("j") match {
      case Some(ParseInt(j)) => throw new UnsupportedOperationException("JSONP is not yet supported")
      case Some(other) => throw new IllegalArgumentException(s"Illegal j value: $other")
      case None =>
        if (!request.getQueryString("b64").contains("1")) {
          // encode using binary
          Writeable(BinaryEngineIOPayloadEncoding.encode, Some("application/octet-stream"))
        } else {
          // Encode using text
          Writeable(Utf8EngineIOPayloadEncoding.encodeBytes, Some("text/plain; charset=utf-8"))
        }
    }
  }

  def parser(parsers: PlayBodyParsers)(implicit ec: ExecutionContext): BodyParser[Option[EngineIOPayload]] = BodyParser { req =>
    if (req.method == "POST") {
      req.contentType match {
        case Some("text/plain") =>
          parsers.tolerantText.map(text => Some(Utf8EngineIOPayloadEncoding.decode(text))).apply(req)
        case other =>
          Accumulator.done(Left(Results.Ok("Bad content type")))
      }
    } else {
      Accumulator.done(Right(None))
    }
  }
}

object BinaryEngineIOPayloadEncoding {

  val StringPacketByte: Byte = 0
  val StringPacketBytes = ByteString(StringPacketByte)
  val BinaryPacketByte: Byte = 1
  val BinaryPacketBytes = ByteString(BinaryPacketByte)
  val LengthTerminator: Byte = 255.toByte
  val LengthTerminatorBytes = ByteString(LengthTerminator)

  def encode(payload: EngineIOPayload): ByteString = {
    payload.packets.foldLeft(ByteString.empty)((bytes, packet) => bytes ++ encodePacket(packet))
  }

  def decode(bytes: ByteString): EngineIOPayload = {
    def decodeP(bytes: ByteString): List[EngineIOPacket] = {
      if (bytes.isEmpty) {
        Nil
      } else {
        val (packet, remaining) = decodePacket(bytes)
        packet :: decodeP(remaining)
      }
    }
    EngineIOPayload(decodeP(bytes))
  }

  private def encodePacket(packet: EngineIOPacket) = {
    packet match {
      case BinaryEngineIOPacket(typeId, data) =>
        BinaryPacketBytes ++ encodeInt(data.size + 1) ++ LengthTerminatorBytes ++ typeId.binaryEncoded ++ data
      case Utf8EngineIOPacket(typeId, text) =>
        val data = ByteString(text)
        StringPacketBytes ++ encodeInt(data.size + 1) ++ LengthTerminatorBytes ++ typeId.binaryEncoded ++ data
    }
  }

  /**
    * Engine.IO encodes integers into bytes by converting each digit in the integer into a byte with value from 0-9.
    *
    * So, to implement it, we convert the integer to a String, and then convert each character to a byte, and then
    * subtract '0' (48) from each byte.
    */
  private def encodeInt(int: Int) = {
    assert(int >= 0)
    ByteString(int.toString.map(b => (b - '0').toByte): _*)
  }

  private def decodePacket(bytes: ByteString): (EngineIOPacket, ByteString) = {
    bytes.head match {
      case packetEncoding @ (StringPacketByte | BinaryPacketByte) =>

        val lengthTerminator = bytes.indexOf(LengthTerminator)

        if (lengthTerminator < 0) {
          throw EngineIOEncodingException("No length terminator found in packet")
        }

        val length = decodeInt(bytes.slice(1, lengthTerminator))

        if (bytes.size < lengthTerminator + length + 1) {
          throw EngineIOEncodingException(s"Parsed packet length of $length but only ${bytes.size - lengthTerminator - 1} bytes are available")
        }

        if (length == 0) {
          throw EngineIOEncodingException("Packet length must be at least 1")
        }

        val packetTypeId = EngineIOPacketType.fromBinary(bytes(lengthTerminator + 1))

        val packetBytes = bytes.slice(lengthTerminator + 2, lengthTerminator + length + 1)

        val packet = packetEncoding match {
          case `StringPacketByte` => Utf8EngineIOPacket(packetTypeId, packetBytes.utf8String)
          case `BinaryPacketByte` => BinaryEngineIOPacket(packetTypeId, packetBytes)
        }

        packet -> bytes.drop(lengthTerminator + 1 + length)

      case other =>
        val unsigned = if (other < 0) other.toInt + 256 else other.toInt
        throw EngineIOEncodingException(s"Unexpected byte at beginning of packet: 0x${Integer.toHexString(unsigned)}")
    }


  }

  private def decodeInt(bytes: ByteString) = {
    bytes.foldLeft(0)((result, byte) => result * 10 + byte)
  }

}

object Utf8EngineIOPayloadEncoding {
  def encode(payload: EngineIOPayload): String = {
    val builder = StringBuilder.newBuilder
    payload.packets.foreach(encodePacket(builder))
    builder.toString
  }

  def encodeBytes(payload: EngineIOPayload): ByteString = {
    ByteString(encode(payload))
  }

  private def encodePacket(builder: StringBuilder)(packet: EngineIOPacket) = {
    val (packetData, length) = packet match {
      case Utf8EngineIOPacket(_, text) =>
        // Use text.codePointCount rather than text.length since we need the number of code points, not chars
        text -> text.codePointCount(0, text.length)
      case BinaryEngineIOPacket(_, data) =>
        val text = new String(Base64.getEncoder.encode(data.toArray), "US-ASCII")
        // text is ascii, no need to do a code point count
        text -> text.length
    }
    builder ++= (length + 1).toString
    builder += ':'
    if (packet.isInstanceOf[BinaryEngineIOPacket]) {
      builder += 'b'
    }
    builder ++= packet.typeId.stringEncoded
    builder ++= packetData
  }

  def decode(text: String): EngineIOPayload = {
    EngineIOPayload(decodePackets(text, 0))
  }

  private def decodePackets(text: String, startIndex: Int): List[EngineIOPacket] = {
    if (text.length() == startIndex) {
      Nil
    } else {
      val (packet, endIndex) = decodePacket(text, startIndex)
      packet :: decodePackets(text, endIndex)
    }
  }

  private def decodePacket(text: String, startIndex: Int): (EngineIOPacket, Int) = {
    val matcher = PacketHeaderPattern.matcher(text)
    if (matcher.find(startIndex)) {
      val length = matcher.group(1).toInt
      val binary = matcher.group(2) == "b"
      val packetType = EngineIOPacketType.fromChar(matcher.group(3).head)
      if (binary) {
        if (text.length - matcher.end() - 1 < length) {
          throw EngineIOEncodingException(s"Parsed packet length of $length but only ${text.length - matcher.end() + 1} bytes are available.")
        }
        val data = ByteString(Base64.getDecoder.decode(text.substring(matcher.end(), matcher.end() + length - 1)))
        BinaryEngineIOPacket(packetType, data) -> (matcher.end() + length - 1)
      } else {
        val codePointCount = text.codePointCount(matcher.end(), text.length)
        if (codePointCount < length - 1) {
          throw EngineIOEncodingException(s"Parsed packet length of $length but only ${codePointCount + 1} bytes are available.")
        }
        val (data, endIndex) = extractCodePoints(text, matcher.end(), length - 1)
        Utf8EngineIOPacket(packetType, data) -> endIndex
      }
    } else {
      throw EngineIOEncodingException("Malformed packet")
    }

  }

  private def extractCodePoints(text: String, startIndex: Int, length: Int): (String, Int) = {
    var count = 0
    var endIndex = startIndex
    while (count < length) {
      if (text(endIndex).isSurrogate) {
        endIndex += 2
      } else {
        endIndex += 1
      }
      count += 1
    }
    text.substring(startIndex, endIndex) -> endIndex
  }

  val PacketHeaderPattern = Pattern.compile("(\\d+):(b?)(\\d)")
}

case class EngineIOEncodingException(msg: String) extends RuntimeException