package play.socketio

import play.api.{Configuration, Environment}
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