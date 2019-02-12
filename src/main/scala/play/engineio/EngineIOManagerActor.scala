/*
 * Copyright 2015 Awesome Company
 */

package play.engineio

import akka.Done
import akka.actor.{ Actor, Props }
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import play.api.mvc.RequestHeader
import play.engineio.protocol.{ EngineIOPacket, EngineIOTransport }

/**
 * The actor responsible for managing all engine.io sessions for this node.
 *
 * The messages sent to/from this actor potentially go through Akka remoting, and so must have serializers configured
 * accordingly.
 */
object EngineIOManagerActor {

  /**
   * Parent type for all messages sent to the actor, implements consistent hashable so that it can be used with a
   * consistent hashing router, particularly useful in a cluster scenario.
   */
  sealed trait SessionMessage extends ConsistentHashable {
    /**
     * The session id.
     */
    val sid: String

    /**
     * The transport this message came from.
     */
    val transport: EngineIOTransport

    /**
     * The id of the request that this message came from.
     */
    val requestId: String

    override def consistentHashKey = sid
  }

  /**
   * Connect a session. Sent to initiate a new session.
   */
  case class Connect(sid: String, transport: EngineIOTransport, request: RequestHeader, requestId: String) extends SessionMessage

  /**
   * Push packets into the session.
   */
  case class Packets(sid: String, transport: EngineIOTransport, packets: Seq[EngineIOPacket], requestId: String) extends SessionMessage

  /**
   * Retrieve packets from the session.
   */
  case class Retrieve(sid: String, transport: EngineIOTransport, requestId: String) extends SessionMessage

  /**
   * Close the session/connection.
   */
  case class Close(sid: String, transport: EngineIOTransport, requestId: String) extends SessionMessage

  def props(config: EngineIOConfig, sessionProps: Props) = Props {
    new EngineIOManagerActor(config, sessionProps)
  }
}

/**
 * The actor responsible for managing all engine.io sessions for this node.
 */
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
