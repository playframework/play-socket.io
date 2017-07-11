package play.socketio

import controllers.ExternalAssets
import play.api.Application
import play.api.routing.Router
import play.engineio.EngineIOController

import scala.concurrent.ExecutionContext

trait TestSocketIOApplication {
  def createApplication(routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router): Application
}
