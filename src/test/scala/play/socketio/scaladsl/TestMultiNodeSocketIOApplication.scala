/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.scaladsl

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.SeqHasAsJava

import controllers.ExternalAssets
import play.api.routing.Router
import play.api.Application
import play.engineio.EngineIOController
import play.socketio.TestSocketIOApplication
import play.socketio.TestSocketIOServer

object TestMultiNodeSocketIOApplication extends TestSocketIOApplication {
  override def createApplication(
      routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router
  ): Application = {

    val backendPekkoPort  = 24101
    val frontendPekkoPort = 24102

    // First, create a backend application node. This node won't receive any HTTP requests, but it's where
    // all the socket.io sessions will live.
    val backendApplication = new TestSocketIOScalaApplication(
      Map(
        "pekko.actor.provider"            -> "remote",
        "pekko.remote.enabled-transports" -> Seq("pekko.remote.netty.tcp").asJava,
        "pekko.remote.netty.tcp.hostname" -> "127.0.0.1",
        "pekko.remote.netty.tcp.port"     -> backendPekkoPort.asInstanceOf[java.lang.Integer]
      )
    ).createComponents(routerBuilder).application

    println("Started backend application.")

    // Now create a frontend application node. This will be configured to route all session messages to the
    // backend node
    val frontendComponents = new TestSocketIOScalaApplication(
      Map(
        "pekko.actor.provider"                          -> "remote",
        "pekko.remote.enabled-transports"               -> Seq("pekko.remote.netty.tcp").asJava,
        "pekko.remote.netty.tcp.hostname"               -> "127.0.0.1",
        "pekko.remote.netty.tcp.port"                   -> frontendPekkoPort.asInstanceOf[java.lang.Integer],
        "play.engine-io.router-name"                    -> "backend-router",
        "pekko.actor.deployment./backend-router.router" -> "round-robin-group",
        "pekko.actor.deployment./backend-router.routees.paths" ->
          Seq(s"pekko.tcp://application@127.0.0.1:$backendPekkoPort/user/engine.io").asJava
      )
    ).createComponents(routerBuilder)

    frontendComponents.application
    println("Started frontend application.")

    // shutdown the backend application when the frontend application shuts down
    frontendComponents.applicationLifecycle
      .addStopHook(() => backendApplication.stop())

    // And return the frontend application
    frontendComponents.application
  }

  @annotation.varargs
  def main(args: String*): Unit = {
    TestSocketIOServer.main(this)
  }
}
