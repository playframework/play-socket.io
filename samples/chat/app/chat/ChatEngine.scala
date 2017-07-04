package chat

import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub}
import play.api.libs.json.Format
import play.engineio._

import play.api.libs.functional.syntax._

object ChatProtocol {

  case class ChatMessage(message: String)

  object ChatMessage {
    implicit val format: Format[ChatMessage] = implicitly[Format[String]]
      .inmap[ChatMessage](ChatMessage.apply, _.message)
  }

  import SocketIOEventCodec._

  val decoder = decoders(
    decodeJson[ChatMessage]("chat message")
  )

  val encoder = encoders {
    case _: ChatMessage => encodeJson[ChatMessage]("chat message")
  }
}

class ChatEngine(engineIO: EngineIO)(implicit mat: Materializer) {

  import ChatProtocol._

  private val chatFlow = {
    val (sink, source) = MergeHub.source[ChatMessage]
      .toMat(BroadcastHub.sink)(Keep.both).run

    Flow.fromSinkAndSource(sink, source)
  }

  val controller: EngineIOController = engineIO.builder
    .addNamespace("/chat", decoder, encoder, chatFlow)
    .build
}