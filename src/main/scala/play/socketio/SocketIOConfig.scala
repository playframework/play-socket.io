/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

import scala.concurrent.duration._

import play.api.Configuration

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
  def fromConfiguration(configuration: Configuration): SocketIOConfig = {
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
