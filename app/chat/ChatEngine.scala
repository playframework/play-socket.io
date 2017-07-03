package chat

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub}
import akka.util.ByteString
import play.api.libs.json.{Format, Json}
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
    decodeJson[ChatMessage]("chat message").withMaybeAck(SocketIOAckEncoder[String] { msg =>
      Seq(Right(ByteString(msg)))
    })
  }

  val encoder = encoders {
    case (_: ChatMessage, _: (String => Unit)) => encodeJson[ChatMessage]("chat message").withAck(SocketIOAckDecoder { msg =>
      msg.headOption.flatMap(_.right.toOption).map(_.utf8String).getOrElse(throw new RuntimeException("Missing ack argument"))
    })
  }

}

class ChatEngine(engineIOFactory: EngineIOFactory)(implicit mat: Materializer) {

  import ChatProtocol._

  private val chatFlow = {
    val (sink, source) = MergeHub.source[ChatMessage].toMat(BroadcastHub.sink)(Keep.both).run
    val mappedSink = Flow[(ChatMessage, Option[String => Unit])].map {
      case (message, maybeAck) =>
        maybeAck.foreach { ack =>
          ack("I (the server) got your message: " + message.message)
        }
        message
    }.to(sink)
    val mappedSource = source.map { message =>
      (message, { ackMessage: String =>
        println("Ack from client: " + ackMessage)
      })
    }
    Flow.fromSinkAndSource(mappedSink, mappedSource)
  }

  private val engine = engineIOFactory("chatengine",
    (request, sid) => Future.successful(Some(SocketIOSession(sid, NotUsed)))
  ) { _ => EngineIO.namespace(decoder, encoder)(chatFlow)
  } (PartialFunction.empty)

  def endpoint(transport: String) = engine.endpoint(transport)
}