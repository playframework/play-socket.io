/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import controllers.ExternalAssets
import play.api.Application
import play.api.routing.Router
import play.engineio.EngineIOController

import scala.concurrent.ExecutionContext

trait TestSocketIOApplication {
  def createApplication(routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router): Application
}
