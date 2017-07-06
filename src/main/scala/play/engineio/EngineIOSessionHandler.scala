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
    * @param request The first request for this session.
    * @param sid The session id.
    */
  def onConnect(request: RequestHeader, sid: String): Future[Flow[EngineIOMessage, EngineIOMessage, NotUsed]]

}

case class UnknownSessionId(sid: String) extends RuntimeException("Unknown session id: " + sid, null, true, false)

/**
  * An engine io message, either binary or text.
  */
sealed trait EngineIOMessage

case class BinaryEngineIOMessage(bytes: ByteString) extends EngineIOMessage
case class TextEngineIOMessage(text: String) extends EngineIOMessage