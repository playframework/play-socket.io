/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import scala.concurrent.ExecutionContext

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
        def extAssets: String => EssentialAction = assets.at("src/test/javascript", _)
        implicit val ec: ExecutionContext        = executionContext
        Router.from {
          case GET(p"/socket.io/") ? q"transport=$transport"  => controller.endpoint(transport)
          case POST(p"/socket.io/") ? q"transport=$transport" => controller.endpoint(transport)
          case GET(p"$path*") =>
            println(path)
            EssentialAction { rh =>
              extAssets(path).apply(rh).map(_.withHeaders("Cache-Control" -> "no-cache"))
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
