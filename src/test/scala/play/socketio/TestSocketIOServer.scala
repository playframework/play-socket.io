/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import play.core.server.AkkaHttpServer
import play.core.server.ServerConfig
import play.api.mvc.EssentialAction
import play.api.routing.sird._
import play.api.routing.Router

/**
 * Test server that can be
 */
object TestSocketIOServer {

  def start(testApplication: TestSocketIOApplication, config: ServerConfig = ServerConfig()): AkkaHttpServer = {
    AkkaHttpServer.fromApplication(
      testApplication.createApplication { (assets, controller, executionContext) =>
        def extAssets: String => EssentialAction = assets.at("src/test/javascript", _)
        implicit val ec                          = executionContext
        Router.from {
          case GET(p"/socket.io/") ? q"transport=$transport"  => controller.endpoint(transport)
          case POST(p"/socket.io/") ? q"transport=$transport" => controller.endpoint(transport)
          case GET(p"$path*") =>
            EssentialAction { rh =>
              (if (path.endsWith("/")) {
                 extAssets(path + "index.html")
               } else {
                 extAssets(path)
               }).apply(rh).map(_.withHeaders("Cache-Control" -> "no-cache"))
            }
        }
      },
      config
    )
  }

  def main(testApplication: TestSocketIOApplication) = {
    val server = start(testApplication)
    println("Press enter to terminate application.")
    System.in.read()
    server.stop()
  }
}
