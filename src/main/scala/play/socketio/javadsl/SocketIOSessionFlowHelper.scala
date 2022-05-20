/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.javadsl

import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.function.BiFunction
import java.util.function.Function

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.javadsl.Flow
import com.fasterxml.jackson.databind.JsonNode
import play.api.libs.json.Json
import play.mvc.Http.RequestHeader
import play.socketio.SocketIOConfig
import play.socketio.SocketIOEvent
import play.socketio.SocketIOSession
import play.socketio.SocketIOSessionFlow

import scala.concurrent.ExecutionContext
import scala.Function.unlift
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._

/**
 * Helps with mapping Java types to Scala types, which is much easier to do in Scala than Java.
 */
private[javadsl] object SocketIOSessionFlowHelper {

  def createEngineIOSessionHandler[SessionData](
      config: SocketIOConfig,
      connectCallback: BiFunction[RequestHeader, String, CompletionStage[SessionData]],
      errorHandler: Function[Throwable, Optional[JsonNode]],
      defaultNamespaceCallback: Function[SocketIOSession[SessionData], Flow[SocketIOEvent, SocketIOEvent, NotUsed]],
      connectToNamespaceCallback: BiFunction[SocketIOSession[SessionData], String, Optional[
        Flow[SocketIOEvent, SocketIOEvent, NotUsed]
      ]]
  )(implicit ec: ExecutionContext, mat: Materializer) = {
    SocketIOSessionFlow.createEngineIOSessionHandler[SessionData](
      config,
      (request, sid) => connectCallback(request.asJava, sid).toScala,
      unlift(t => errorHandler(t).asScala.map(Json.toJson(_))),
      session => defaultNamespaceCallback(session).asScala,
      unlift { case (session, sid) =>
        connectToNamespaceCallback(session, sid).asScala.map(_.asScala)
      }
    )
  }
}
