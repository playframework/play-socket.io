package play.engineio

import akka.Done
import akka.actor.{Actor, Props}
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import play.api.mvc.RequestHeader
import play.engineio.protocol.{EngineIOPacket, EngineIOTransport}

object EngineIOManagerActor {
  sealed trait SessionMessage extends ConsistentHashable {
    val sid: String
    val transport: EngineIOTransport
    val requestId: String
    override def consistentHashKey = sid
  }

  case class Connect(sid: String, transport: EngineIOTransport, request: RequestHeader, requestId: String) extends SessionMessage
  case class Packets(sid: String, transport: EngineIOTransport, packets: Seq[EngineIOPacket], requestId: String,
    lastPacket: Boolean = false) extends SessionMessage
  case class Retrieve(sid: String, transport: EngineIOTransport, requestId: String) extends SessionMessage
  case class Close(sid: String, transport: EngineIOTransport, requestId: String) extends SessionMessage

  def props(config: EngineIOConfig, sessionProps: Props) = Props {
    new EngineIOManagerActor(config, sessionProps)
  }
}

class EngineIOManagerActor(config: EngineIOConfig, sessionProps: Props) extends Actor {

  import EngineIOManagerActor._

  override def receive = {
    case connect: Connect =>
      context.child(connect.sid) match {
        case Some(sessionActor) =>
          // Let the actor deal with the duplicate connect
          sessionActor.tell(connect, sender())
        case None =>
          val session = context.actorOf(sessionProps, connect.sid)
          session.tell(connect, sender())
      }

    case close @ Close(sid, _, _) =>
      // Don't restart the session just to close it
      context.child(sid) match {
        case Some(sessionActor) =>
          sessionActor.tell(close, sender())
        case None =>
          sender ! Done
      }

    case message: SessionMessage =>
      context.child(message.sid) match {
        case Some(sessionActor) =>
          sessionActor.tell(message, sender())

        case None =>
          // We let the session handle what to do if we get a connection for a non existing sid
          val sessionActor = context.actorOf(sessionProps, message.sid)
          sessionActor.tell(message, sender())
      }
  }
}
