/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import akka.util.ByteString
import play.api.libs.json.JsValue
import scala.util.Either

/**
 * A socket.io ack function.
 */
trait SocketIOEventAck {

  def apply(args: Seq[Either[JsValue, ByteString]]): Unit

  def compose[T](f: T => Seq[Either[JsValue, ByteString]]): T => Unit = (args: T) => {
    SocketIOEventAck.this.apply(f(args))
  }
}

object SocketIOEventAck {
  def apply(f: Seq[Either[JsValue, ByteString]] => Unit): SocketIOEventAck = args => f(args)

  @deprecated("Use apply method", since = "1.1.0")
  def fromScala(f: Seq[Either[JsValue, ByteString]] => Unit): SocketIOEventAck = apply(f)
}
