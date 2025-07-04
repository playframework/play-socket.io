/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import scala.concurrent.ExecutionContext

import controllers.ExternalAssets
import play.api.routing.Router
import play.api.Application
import play.engineio.EngineIOController

trait TestSocketIOApplication {
  def createApplication(routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router): Application
}
