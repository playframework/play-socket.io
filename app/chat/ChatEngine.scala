package chat

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub}
import play.api.libs.json.{Format, Json}
import socketio._

import scala.concurrent.Future


object ChatProtocol {

  case class ChatMessage(message: String)

  object ChatMessage {
    implicit val format: Format[ChatMessage] = Json.format
  }

  val decoder = SocketIOEventDecoder.compose {
    SocketIOEventDecoder.json[ChatMessage]("chat message")
  }

  val encoder = SocketIOEventEncoder.compose {
    case _: ChatMessage => SocketIOEventEncoder.json[ChatMessage]("chat message")
  }

}

class ChatEngine(engineIOFactory: EngineIOFactory)(implicit mat: Materializer) {

  import ChatProtocol._

  private val (chatSink, chatSource) = MergeHub.source[ChatMessage]
    .toMat(BroadcastHub.sink)(Keep.both).run

  private val engine = engineIOFactory("chatengine",
    (request, sid) => Future.successful(Some(SocketIOSession(sid, NotUsed)))
  ) { _ => EngineIO.namespace(decoder, encoder)(Flow.fromSinkAndSource(chatSink, chatSource)) }
  { (_, _) => None }

  def endpoint(transport: String) = engine.endpoint(transport)
}