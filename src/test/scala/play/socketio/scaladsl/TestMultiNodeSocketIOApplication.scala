/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.scaladsl

import controllers.ExternalAssets
import play.api.inject.ApplicationLifecycle
import play.api.routing.Router
import play.engineio.EngineIOController
import play.socketio.TestSocketIOApplication
import play.socketio.TestSocketIOServer

import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._

object TestMultiNodeSocketIOApplication extends TestSocketIOApplication {
  override def createApplication(routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router) = {

    val backendAkkaPort  = 24101
    val frontendAkkaPort = 24102

    // First, create a backend application node. This node won't receive any HTTP requests, but it's where
    // all the socket.io sessions will live.
    val backendApplication = new TestSocketIOScalaApplication(
      Map(
        "akka.actor.provider"            -> "remote",
        "akka.remote.enabled-transports" -> Seq("akka.remote.netty.tcp").asJava,
        "akka.remote.netty.tcp.hostname" -> "127.0.0.1",
        "akka.remote.netty.tcp.port"     -> backendAkkaPort.asInstanceOf[java.lang.Integer]
      )
    ).createComponents(routerBuilder).application

    println("Started backend application.")

    // Now create a frontend application node. This will be configured to route all session messages to the
    // backend node
    val frontendComponents = new TestSocketIOScalaApplication(
      Map(
        "akka.actor.provider"                          -> "remote",
        "akka.remote.enabled-transports"               -> Seq("akka.remote.netty.tcp").asJava,
        "akka.remote.netty.tcp.hostname"               -> "127.0.0.1",
        "akka.remote.netty.tcp.port"                   -> frontendAkkaPort.asInstanceOf[java.lang.Integer],
        "play.engine-io.router-name"                   -> "backend-router",
        "akka.actor.deployment./backend-router.router" -> "round-robin-group",
        "akka.actor.deployment./backend-router.routees.paths" ->
          Seq(s"akka.tcp://application@127.0.0.1:$backendAkkaPort/user/engine.io").asJava
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
  def main(args: String*) = {
    TestSocketIOServer.main(this)
  }
}
