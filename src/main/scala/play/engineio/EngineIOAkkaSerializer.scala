package play.engineio

import akka.actor.ExtendedActorSystem
import akka.serialization.{BaseSerializer, SerializerWithStringManifest}
import akka.util.{ByteString => AByteString}
import com.google.protobuf.{ByteString => PByteString}
import play.engineio.EngineIOManagerActor._
import play.engineio.protocol._
import play.engineio.protobuf.{engineio => p}

/**
  * Serializer for all messages sent to/from the EngineIOManagerActor.
  */
class EngineIOAkkaSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {

  private val ConnectManifest = "A"
  private val PacketsManifest = "B"
  private val RetrieveManifest = "C"
  private val CloseManifest = "D"
  private val EngineIOEncodingExceptionManifest = "E"
  private val SocketIOEncodingExceptionManifest = "F"

  override def manifest(obj: AnyRef) = obj match {
    case _: Connect => ConnectManifest
    case _: Packets => PacketsManifest
    case _: Retrieve => RetrieveManifest
    case _: Close => CloseManifest
    case _: EngineIOEncodingException => EngineIOEncodingExceptionManifest
    case _: SocketIOEncodingException => SocketIOEncodingExceptionManifest
    case _ =>
      throw new IllegalArgumentException(s"I don't know how to serialize object of type ${obj.getClass}")
  }

  override def toBinary(obj: AnyRef): Array[Byte] = {
    val protobufObject = obj match {

      case Connect(sid, transport, request, requestId) =>
        p.Connect(sid, encodeTransport(transport), requestId, request.method, request.uri, request.version,
          request.headers.headers.map(header => p.HttpHeader(header._1, header._2))
        )

      case Packets(sid, transport, packets, requestId, lastPacket) =>
        p.Packets(sid, encodeTransport(transport), packets.map { packet =>
          p.Packet(p.PacketType.fromValue(packet.typeId.id), packet match {
            case Utf8EngineIOPacket(_, text) => p.Packet.Payload.Text(text)
            case BinaryEngineIOPacket(_, bytes) => p.Packet.Payload.Binary(encodeBytes(bytes))
          })
        }, requestId, lastPacket)

      case Retrieve(sid, transport, requestId) =>
        p.Retrieve(sid, encodeTransport(transport), requestId)

      case Close(sid, transport, requestId) =>
        p.Close(sid, encodeTransport(transport), requestId)

      case EngineIOEncodingException(message) =>
        p.EngineIOEncodingException(message)

      case SocketIOEncodingException(packet, message, null) =>
        p.SocketIOEncodingException(packet, message)

      case SocketIOEncodingException(packet, message, cause) =>
        p.SocketIOEncodingException(packet, message + ": " + cause.toString)

      case other =>
        throw new RuntimeException("Don't know how to serialize " + other)
    }

    protobufObject.toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String) = manifest match {
    case `ConnectManifest` =>
      val connect = p.Connect.parseFrom(bytes)
      Connect(connect.sid, decodeTransport(connect.transport),
        new DeserializedRequestHeader(connect.method, connect.uri, connect.version,
          connect.headers.map(h => (h.name, h.value))), connect.requestId
      )

    case `PacketsManifest` =>
      val packets = p.Packets.parseFrom(bytes)
      Packets(packets.sid, decodeTransport(packets.transport), packets.packets.map { packet =>
        val packetType = EngineIOPacketType.fromBinary(packet.packetType.productArity.toByte)
        packet.payload.value match {
          case p.Packet.Payload.Text(text) =>
            Utf8EngineIOPacket(packetType, text)
          case p.Packet.Payload.Binary(byteString) =>
            BinaryEngineIOPacket(packetType, decodeBytes(byteString))
        }
      }, packets.requestId, packets.lastPacket)

    case `RetrieveManifest` =>
      val retrieve = p.Retrieve.parseFrom(bytes)
      Retrieve(retrieve.sid, decodeTransport(retrieve.transport), retrieve.requestId)

    case `CloseManifest` =>
      val close = p.Close.parseFrom(bytes)
      Close(close.sid, decodeTransport(close.transport), close.requestId)

    case `EngineIOEncodingExceptionManifest` =>
      val exception = p.EngineIOEncodingException.parseFrom(bytes)
      EngineIOEncodingException(exception.message)

    case `SocketIOEncodingExceptionManifest` =>
      val exception = p.SocketIOEncodingException.parseFrom(bytes)
      SocketIOEncodingException(exception.packet, exception.message)

    case _ =>
      throw new IllegalArgumentException(s"I don't know how to deserialize object with manifest [$manifest]")
  }

  private def encodeBytes(bytes: AByteString): PByteString = {
    // This does 2 buffer copies - not sure if there's a smarter zero buffer copy way to do it
    PByteString.copyFrom(bytes.toByteBuffer)
  }

  private def encodeTransport(transport: EngineIOTransport): p.Transport = transport match {
    case EngineIOTransport.Polling => p.Transport.POLLING
    case EngineIOTransport.WebSocket => p.Transport.WEBSOCKET
  }

  private def decodeTransport(transport: p.Transport): EngineIOTransport = transport match {
    case p.Transport.POLLING => EngineIOTransport.Polling
    case p.Transport.WEBSOCKET => EngineIOTransport.WebSocket
    case p.Transport.Unrecognized(value) => throw new IllegalArgumentException("Unrecognized transport: " + value)
  }

  private def decodeBytes(bytes: PByteString): AByteString = {
    AByteString.apply(bytes.asReadOnlyByteBuffer())
  }
}
