/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import play.api.Configuration

import scala.concurrent.duration._
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Configuration for socket.io.
 *
 * See `reference.conf` for in depth documentation.
 */
case class SocketIOConfig(
    ackDeadline: FiniteDuration = 60.seconds,
    ackCleanupEvery: Int = 10
)

object SocketIOConfig {
  def fromConfiguration(configuration: Configuration) = {
    val config = configuration.get[Configuration]("play.socket-io")
    SocketIOConfig(
      ackDeadline = config.get[FiniteDuration]("ack-deadline"),
      ackCleanupEvery = config.get[Int]("ack-cleanup-every")
    )
  }
}

@Singleton
class SocketIOConfigProvider @Inject() (configuration: Configuration) extends Provider[SocketIOConfig] {
  override lazy val get: SocketIOConfig = SocketIOConfig.fromConfiguration(configuration)
}
