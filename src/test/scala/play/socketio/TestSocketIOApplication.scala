/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
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
