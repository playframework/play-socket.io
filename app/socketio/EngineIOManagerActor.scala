package socketio

import java.util.UUID

import akka.actor.{Actor, Props}
import play.api.mvc.RequestHeader
import socketio.protocol.{EngineIOPacket, EngineIOTransport}

object EngineIOManagerActor {
  case class Connect(transport: EngineIOTransport, request: RequestHeader)
  sealed trait SessionMessage {
    val sid: String
  }
  case class Packets(sid: String, transport: EngineIOTransport, packets: Seq[EngineIOPacket], requestId: String = "") extends SessionMessage
  case class Retrieve(sid: String, transport: EngineIOTransport, requestId: String) extends SessionMessage
  case object GoAway

  def props(config: EngineIOConfig, sessionProps: Props) = Props {
    new EngineIOManagerActor(config, sessionProps)
  }
}

class EngineIOManagerActor(config: EngineIOConfig, sessionProps: Props) extends Actor {

  import EngineIOManagerActor._

  override def receive = {
    case connect: Connect =>
      val id = UUID.randomUUID().toString
      val session = context.actorOf(sessionProps, id)
      session.tell(connect, sender())
    case message: SessionMessage =>
      context.child(message.sid) match {
        case Some(sessionActor) =>
          sessionActor.tell(message, sender())

        case None =>
          val sessionActor = context.actorOf(sessionProps, message.sid)
          sessionActor.tell(message, sender())
      }
  }
}
