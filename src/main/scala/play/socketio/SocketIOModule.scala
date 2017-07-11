package play.socketio

import play.api.{Configuration, Environment}
import play.api.inject.Module

class SocketIOModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[SocketIOConfig].toProvider[SocketIOConfigProvider],
    bind[scaladsl.SocketIO].toSelf,
    bind[javadsl.SocketIO].toSelf
  )
}