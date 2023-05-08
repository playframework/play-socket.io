/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import play.api.Configuration
import play.api.Environment
import play.api.inject.Module

/**
 * Module for providing both scaladsl and javadsl socket.io components to Play's runtime dependency injection system.
 */
class SocketIOModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[SocketIOConfig].toProvider[SocketIOConfigProvider],
    bind[scaladsl.SocketIO].toSelf,
    bind[javadsl.SocketIO].toSelf
  )
}
