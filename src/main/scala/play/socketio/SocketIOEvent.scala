/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import akka.util.ByteString
import play.api.libs.json.JsValue

/**
 * A socket.io session.
 *
 * The socket.io session object holds any information relevant to the session. A user can define data to be stored
 * there, such as authentication data, and then use that when connecting to namespaces.
 *
 * @param sid The session ID.
 * @param data The session data.
 */
case class SocketIOSession[+T](sid: String, data: T)

/**
 * A socket.io event.
 *
 * A socket.io event consists of a name (which is used on the client side to register handlers for events), and a set
 * of arguments associated with that event.
 *
 * The arguments mirror the structure in JavaScript, when you publish an event, you pass the event name, and then
 * several arguments. When you subscribe to an event, you declare a function that takes zero or more arguments.
 * Consequently, the arguments get modelled as a sequence.
 *
 * Furthermore, socket.io allows you to pass either a structure that can be serialized to JSON (in the Play world,
 * this is a [[JsValue]]), or a binary argument (in the Play world, this is a [[ByteString]]). Hence, each argument
 * is `Either[JsValue, ByteString]`.
 *
 * Finally, as the last argument, socket.io allows the emitter to pass an ack function. This then gets passed to the
 * consumer as the last argument to their callback function, and they can invoke it, and the arguments passed to it
 * will be serialized to the wire, and passed to the function registered on the other side.
 *
 * @param name The name of the event.
 * @param arguments The list of arguments.
 * @param ack An optional ack function.
 */
case class SocketIOEvent(name: String, arguments: Seq[Either[JsValue, ByteString]], ack: Option[SocketIOEventAck])

object SocketIOEvent {

  /**
   * Create an unnamed event.
   *
   * This is a convenient function for creating events with a name of `"<unnamed>"`.
   */
  def unnamed(arguments: Seq[Either[JsValue, ByteString]], ack: Option[SocketIOEventAck]) =
    SocketIOEvent("<unnamed>", arguments, ack)
}
