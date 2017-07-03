package chat

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub}
import play.api.libs.json.Format
import play.engineio._

import scala.concurrent.Future
import play.api.libs.functional.syntax._

object ChatProtocol {

  case class ChatMessage(message: String)

  object ChatMessage {
    implicit val format: Format[ChatMessage] =
      implicitly[Format[String]].inmap(ChatMessage.apply, _.message)
  }

  import SocketIOEventCodec._

  val decoder = decoders {
    decodeJson[ChatMessage]("chat message")
  }

  val encoder = encoders {
    case (_: ChatMessage, _) => encodeJson[ChatMessage]("chat message")
  }

}

class ChatEngine(engineIOFactory: EngineIOFactory)(implicit mat: Materializer) {

  import ChatProtocol._

  private val chatFlow = {
    val (sink, source) = MergeHub.source[ChatMessage].toMat(BroadcastHub.sink)(Keep.both).run
    Flow.fromSinkAndSource(sink, source)
  }

  private val engine = engineIOFactory("chatengine",
    (_, sid) => Future.successful(Some(SocketIOSession(sid, NotUsed)))
  ) { _ => EngineIO.namespace(decoder, encoder)(chatFlow) }(PartialFunction.empty)

  def endpoint(transport: String) = engine.endpoint(transport)
}