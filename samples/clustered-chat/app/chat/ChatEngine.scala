package chat

import akka.NotUsed
import akka.actor.ActorSystem
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe}
import akka.stream._
import akka.stream.scaladsl.{BroadcastHub, Flow, MergeHub, Sink, Source}
import play.api.Logger
import play.api.libs.json.{Format, Json}
import play.engineio.EngineIOController
import play.api.libs.functional.syntax._
import play.socketio.scaladsl.SocketIO

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

object ChatProtocol {
  import play.socketio.scaladsl.SocketIOEventCodec._

  val decoder = decodeByName {
    case "chat message" => decodeJson[ChatMessage]
    case "join room" => decodeJson[JoinRoom]
    case "leave room" => decodeJson[LeaveRoom]
  }

  val encoder = encodeByType[ChatEvent] {
    case _: ChatMessage => "chat message" -> encodeJson[ChatMessage]
    case _: JoinRoom => "join room" -> encodeJson[JoinRoom]
    case _: LeaveRoom => "leave room" -> encodeJson[LeaveRoom]
  }
}

class ChatEngine(socketIO: SocketIO, system: ActorSystem)(implicit mat: Materializer) {

  import ChatProtocol._

  val mediator = DistributedPubSub(system).mediator

  // This gets a chat room using Akka distributed pubsub
  private def getChatRoom(room: String, user: User) = {

    // Create a sink that sends all the messages to the chat room
    val sink = Sink.foreach[ChatEvent] { message =>
      mediator ! Publish(room, message)
    }

    // Create a source that subscribes to messages from the chatroom
    val source = Source.actorRef[ChatEvent](16, OverflowStrategy.dropHead)
      .mapMaterializedValue { ref =>
        mediator ! Subscribe(room, ref)
      }

    val leaveRoom = LeaveRoom(Some(user), room)
    Flow.fromSinkAndSourceCoupled(
      Flow[ChatEvent]
        // Concat the leave room inside the coupled flow, so that no matter what, it will be sent to the
        // publisher
        .concat(Source.single(leaveRoom))
        .to(sink),
      source
    )
  }

  // Creates a chat flow for a user session
  def userChatFlow(user: User): Flow[ChatEvent, ChatEvent, NotUsed] = {

    // broadcast source and sink for demux/muxing multiple chat rooms in this one flow
    // They'll be provided later when we materialize the flow
    var broadcastSource: Source[ChatEvent, NotUsed] = null
    var mergeSink: Sink[ChatEvent, NotUsed] = null

    Flow[ChatEvent] collect Function.unlift {
      case JoinRoom(_, room) =>
        val roomFlow = getChatRoom(room, user)

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
          // And send it through the room flow
          .via(roomFlow)
          // Re-add the leave room here, since it was filtered out before
          .concat(Source.single(LeaveRoom(Some(user), room)))
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
      Logger.info(s"Starting $sid session")
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