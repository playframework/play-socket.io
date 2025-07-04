/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import scala.concurrent.ExecutionContext

import play.api.http.HeaderNames.CACHE_CONTROL
import play.api.mvc.EssentialAction
import play.api.routing.sird._
import play.api.routing.Router
import play.core.server.PekkoHttpServer
import play.core.server.ServerConfig

/**
 * Test server that can be
 */
object TestSocketIOServer {

  def start(testApplication: TestSocketIOApplication, config: ServerConfig = ServerConfig()): PekkoHttpServer = {
    PekkoHttpServer.fromApplication(
      testApplication.createApplication { (assets, controller, executionContext) =>
        implicit val ec: ExecutionContext = executionContext
        Router.from {
          case GET(p"/socket.io/") ? q"transport=$transport"  => controller.endpoint(transport)
          case POST(p"/socket.io/") ? q"transport=$transport" => controller.endpoint(transport)
          case GET(p"$path*") =>
            println(path)
            EssentialAction { rh =>
              assets.at("src/test/javascript", path).apply(rh).map(_.withHeaders(CACHE_CONTROL -> "no-cache"))
            }
        }
      },
      config
    )
  }

  def main(testApplication: TestSocketIOApplication): Unit = {
    val server = start(testApplication)
    println("Press enter to terminate application.")
    System.in.read()
    server.stop()
  }
}
