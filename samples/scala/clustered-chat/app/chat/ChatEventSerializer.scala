package chat

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.serialization.BaseSerializer
import org.apache.pekko.serialization.SerializerWithStringManifest
import play.api.libs.json.Json

/**
 * Since messages sent through distributed pubsub go over Pekko remoting, they need to be
 * serialized. This serializer serializes them as JSON.
 */
class ChatEventSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {
  override def manifest(o: AnyRef): String = o match {
    case _: ChatMessage => "M"
    case _: JoinRoom    => "J"
    case _: LeaveRoom   => "L"
    case other          => sys.error("Don't know how to serialize " + other)
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    val json = o match {
      case cm: ChatMessage => Json.toJson(cm)
      case jr: JoinRoom    => Json.toJson(jr)
      case lr: LeaveRoom   => Json.toJson(lr)
      case other           => sys.error("Don't know how to serialize " + other)
    }
    Json.toBytes(json)
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val json = Json.parse(bytes)
    manifest match {
      case "M"   => json.as[ChatMessage]
      case "J"   => json.as[JoinRoom]
      case "L"   => json.as[LeaveRoom]
      case other => sys.error("Unknown manifest " + other)
    }
  }
}
