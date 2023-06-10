package chat

import akka.stream.Materializer
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import play.api.libs.json.Format
import play.engineio.EngineIOController
import play.api.libs.functional.syntax._
import play.socketio.scaladsl.SocketIO

/**
 * A simple chat engine.
 */
class ChatEngine(socketIO: SocketIO)(implicit mat: Materializer) {

  import play.socketio.scaladsl.SocketIOEventCodec._

  // This will decode String "chat message" events coming in
  val decoder = decodeByName { case "chat message" =>
    decodeJson[String]
  }

  // This will encode String "chat message" events going out
  val encoder = encodeByType[String] { case _: String =>
    "chat message" -> encodeJson[String]
  }

  private val chatFlow = {
    // We use a MergeHub to merge all the incoming chat messages from all the
    // connected users into one flow, and we feed that straight into a
    // BroadcastHub to broadcast them out again to all the connected users.
    // See http://doc.akka.io/docs/akka/2.6/scala/stream/stream-dynamic.html
    // for details on these features.
    val (sink, source) = MergeHub
      .source[String]
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

    // We couple the sink and source together so that one completes, the other
    // will to, and we use this to handle our chat
    Flow.fromSinkAndSourceCoupled(sink, source)
  }

  // Here we create an EngineIOController to handle requests for our chat
  // system, and we add the chat flow under the "/chat" namespace.
  val controller: EngineIOController = socketIO.builder
    .addNamespace("/chat", decoder, encoder, chatFlow)
    .createController()
}
