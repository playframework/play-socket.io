/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.javadsl

import java.util.concurrent.CompletionStage
import java.util.function.BiFunction
import java.util.function.Function
import java.util.Optional

import scala.concurrent.ExecutionContext
import scala.jdk.FutureConverters.CompletionStageOps
import scala.jdk.OptionConverters.RichOptional
import scala.Function.unlift

import com.fasterxml.jackson.databind.JsonNode
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.Materializer
import org.apache.pekko.NotUsed
import play.api.libs.json.Json
import play.engineio.EngineIOSessionHandler
import play.mvc.Http.RequestHeader
import play.socketio.SocketIOConfig
import play.socketio.SocketIOEvent
import play.socketio.SocketIOSession
import play.socketio.SocketIOSessionFlow

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
  )(implicit ec: ExecutionContext, mat: Materializer): EngineIOSessionHandler = {
    SocketIOSessionFlow.createEngineIOSessionHandler[SessionData](
      config,
      (request, sid) => connectCallback(request.asJava, sid).asScala,
      unlift(t => errorHandler(t).toScala.map(Json.toJson(_))),
      session => defaultNamespaceCallback(session).asScala,
      unlift {
        case (session, sid) =>
          connectToNamespaceCallback(session, sid).asScala.map(_.asScala)
      }
    )
  }
}
