package chat

import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub}
import play.api.libs.json.Format
import play.engineio.EngineIOController
import play.api.libs.functional.syntax._
import play.socketio.SocketIO

object ChatProtocol {

  case class ChatMessage(message: String)

  object ChatMessage {
    implicit val format: Format[ChatMessage] = implicitly[Format[String]]
      .inmap[ChatMessage](ChatMessage.apply, _.message)
  }

  import play.socketio.SocketIOEventCodec._

  val decoder = decoders(
    decodeJson[ChatMessage]("chat message")
  )

  val encoder = encoders {
    case _: ChatMessage => encodeJson[ChatMessage]("chat message")
  }
}

class ChatEngine(socketIO: SocketIO)(implicit mat: Materializer) {

  import ChatProtocol._

  private val chatFlow = {
    val (sink, source) = MergeHub.source[ChatMessage]
      .toMat(BroadcastHub.sink)(Keep.both).run

    Flow.fromSinkAndSourceCoupled(sink, source)
  }

  val controller: EngineIOController = socketIO.builder
    .addNamespace("/chat", decoder, encoder, chatFlow)
    .createController()
}