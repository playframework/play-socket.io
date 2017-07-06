package play.socketio

import akka.stream.stage.{InHandler, OutHandler}
import play.socketio.PausedHandler.Event


object PausedHandler {

  sealed trait Event[-Handler] {
    def replay(handler: Handler): Unit
  }

  sealed trait InEvent extends Event[InHandler]
  case object Push extends InEvent {
    def replay(handler: InHandler) = handler.onPush()
  }
  case object UpstreamFinish extends InEvent {
    def replay(handler: InHandler) = handler.onUpstreamFinish()
  }
  case class UpstreamFailure(ex: Throwable) extends InEvent {
    def replay(handler: InHandler) = handler.onUpstreamFailure(ex)
  }

  sealed trait OutEvent extends Event[OutHandler]
  case object Pull extends OutEvent {
    def replay(handler: OutHandler) = handler.onPull()
  }
  case object DownstreamFinish extends OutEvent {
    def replay(handler: OutHandler) = handler.onDownstreamFinish()
  }
}

import PausedHandler._

sealed trait PausedHandler[Handler] {
  private var events: Seq[Event[Handler]] = Nil
  def addEvent(event: Event[Handler]) = {
    events :+= event
  }

  def replay(handler: Handler) = {
    events.foreach(_.replay(handler))
  }
}

sealed trait GenPausedInHandler[Handler <: InHandler] extends PausedHandler[Handler] with InHandler {
  override def onPush() = addEvent(Push)
  override def onUpstreamFinish() = addEvent(UpstreamFinish)
  override def onUpstreamFailure(ex: Throwable) = addEvent(UpstreamFailure(ex))
}

final class PausedInHandler extends GenPausedInHandler[InHandler]

sealed trait GenPausedOutHandler[Handler <: OutHandler] extends PausedHandler[Handler] with OutHandler {
  override def onPull() = addEvent(Pull)
  override def onDownstreamFinish() = addEvent(DownstreamFinish)
}

final class PausedOutHandler extends GenPausedOutHandler[OutHandler]

final class PausedInOutHandler extends PausedHandler[InHandler with OutHandler]
  with GenPausedInHandler[InHandler with OutHandler]
  with GenPausedOutHandler[InHandler with OutHandler]

