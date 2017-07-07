package chat

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import play.api.libs.json.{Format, Json}
import play.engineio.EngineIOController
import play.api.libs.functional.syntax._
import play.socketio.SocketIO

import scala.collection.concurrent.TrieMap

object ChatProtocol {

  /**
    * A chat event, either a message, a join room, or a leave room event.
    */
  sealed trait ChatEvent {
    def user: Option[User]
    def room: String
  }

  case class ChatMessage(user: Option[User], room: String, message: String) extends ChatEvent
  object ChatMessage {
    implicit val format: Format[ChatMessage] = Json.format
  }

  case class JoinRoom(user: Option[User], room: String) extends ChatEvent
  object JoinRoom {
    implicit val format: Format[JoinRoom] = Json.format
  }

  case class LeaveRoom(user: Option[User], room: String) extends ChatEvent
  object LeaveRoom {
    implicit val format: Format[LeaveRoom] = Json.format
  }

  case class User(name: String)
  object User {
    // We're just encoding user as a simple string, not an object
    implicit val format: Format[User] = implicitly[Format[String]].inmap(User.apply, _.name)
  }

  import play.socketio.SocketIOEventCodec._

  val decoder = decoders(
    decodeJson[ChatMessage]("chat message"),
    decodeJson[JoinRoom]("join room"),
    decodeJson[LeaveRoom]("leave room")
  )

  val encoder = encoders {
    case _: ChatMessage => encodeJson[ChatMessage]("chat message")
    case _: JoinRoom => encodeJson[JoinRoom]("join room")
    case _: LeaveRoom => encodeJson[LeaveRoom]("leave room")
  }
}

class ChatEngine(socketIO: SocketIO)(implicit mat: Materializer) {

  import ChatProtocol._

  // All the chat rooms
  private val chatRooms = TrieMap.empty[String, Flow[ChatEvent, ChatEvent, _]]

  // This gets an existing chat room, or creates it if it doesn't exist
  private def getChatRoom(room: String) = {
    chatRooms.getOrElseUpdate(room, {
      // Each chat room is a merge hub merging messages into a broadcast hub.
      val (sink, source) = MergeHub.source[ChatEvent].toMat(BroadcastHub.sink[ChatEvent])(Keep.both).run
      Flow.fromSinkAndSource(sink, source)
    })
  }

  // Creates a chat flow for a user session
  def userChatFlow(user: User): Flow[ChatEvent, ChatEvent, NotUsed] = {

    // broadcast source and sink for demux/muxing multiple chat rooms in this one flow
    // They'll be provided later when we materialize the flow
    var broadcastSource: Source[ChatEvent, NotUsed] = null
    var mergeSink: Sink[ChatEvent, NotUsed] = null

    Flow[ChatEvent] collect Function.unlift {
      case JoinRoom(_, room) =>
        val roomFlow = getChatRoom(room)

        // Add the room to our flow
        broadcastSource
          // Ensure only messages for this room get there
          .filter(_.room == room)
          // Take until we get a leave room message.
          .takeWhile(!_.isInstanceOf[LeaveRoom])
          // We ensure that a join room message is sent first, and a leave room message is sent when we disconnect.
          // The reason we filter the previous LeaveRoom message out, and add one here, is so that when an unclean
          // disconnect happens (disconnect without sending leave room first), we always still send a leave room.
          .prepend(Source.single(JoinRoom(Some(user), room)))
          .concat(Source.single(LeaveRoom(Some(user), room)))
          // And send it through the room flow
          .via(roomFlow)
          // And now we want to cancel this end of the flow when we see the leave room message for us.
          .takeWhile({
            case LeaveRoom(Some(`user`), `room`) => false
            case _ => true
          }, inclusive = true)
          .runWith(mergeSink)

        // And ignore the original JoinRoom message, we've already ensured it gets prepended to our flow of messages
        // into the room
        None

      case LeaveRoom(_, room) =>
        Some(LeaveRoom(Some(user), room))

      case ChatMessage(_, room, message) =>
        Some(ChatMessage(Some(user), room, message))

    } via {
      Flow.fromSinkAndSourceCoupledMat(BroadcastHub.sink[ChatEvent], MergeHub.source[ChatEvent]) { (source, sink) =>
        broadcastSource = source
        mergeSink = sink
        NotUsed
      }
    }
  }

  val controller: EngineIOController = socketIO.builder
    .onConnect { (request, sid) =>
      // Extract the username from the header
      val username = request.getQueryString("user").getOrElse {
        throw new RuntimeException("No user parameter")
      }
      // And return the user, this will be the data for the session that we can read when we add a namespace
      User(username)
    }.addNamespace(decoder, encoder) {
      case (session, chat) if chat.split('?').head == "/chat" => userChatFlow(session.data)
    }
    .createController()
}