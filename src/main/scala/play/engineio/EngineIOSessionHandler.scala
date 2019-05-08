/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package play.engineio

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import play.api.mvc.RequestHeader

import scala.concurrent.Future

/**
 * A handler for engine.io sessions.
 */
trait EngineIOSessionHandler {

  /**
   * Create a new flow to handle the flow of messages in the engine.io session.
   *
   * It may seem odd that the flow produces a `Seq[EngineIOMessage]`. The reason it does this is because socket.io
   * binary events get encoded into multiple `EngineIOMessage`'s. By producing messages in `Seq`'s, we allow them to
   * be sent as one payload back to the client, otherwise they would usually be split.
   *
   * @param request The first request for this session.
   * @param sid The session id.
   */
  def onConnect(request: RequestHeader, sid: String): Future[Flow[EngineIOMessage, Seq[EngineIOMessage], NotUsed]]

}

/**
 * Exception thrown when a session id is unknown.
 *
 * @param sid The unknown session id.
 */
case class UnknownSessionId(sid: String) extends RuntimeException("Unknown session id: " + sid, null, true, false)

/**
 * Exception thrown when a message is received for a session that is already closed.
 */
case object SessionClosed extends RuntimeException("Session closed", null, true, false)

/**
 * An engine.io message, either binary or text.
 */
sealed trait EngineIOMessage

/**
 * A binary engine.io message.
 */
case class BinaryEngineIOMessage(bytes: ByteString) extends EngineIOMessage

/**
 * A text engine.io message.
 */
case class TextEngineIOMessage(text: String) extends EngineIOMessage
