# Play socket.io Scala support

This describes the Play socket.io Scala support.

## Installing

You can add Play socket.io to your project by adding the following dependency:

```scala
libraryDependencies += "com.lightbend.play" %% "play-socket-io" % "1.0.0-beta-2"
```

## Understanding socket.io and engine.io

engine.io is a protocol for speaking between a client (typically a browser) and a server. It allows multiple transports, including XHR polling and WebSockets, it sends regular heartbeats using pings to ensure the liveness of the connection and detect failures, and it allows upgrading from one transport to another.

When you connect to a server using engine.io, you create a session, which is uniquely identified by a session id. Generally, any failures that happen result in the session terminating, for example, if the WebSocket becomes disconnected. The JavaScript engine.io client will handle creating a new session in that case, and will inform the client application that a reconnection has occurred, so the application can do any further work.

socket.io is a protocol on top of engine.io that allows multiple namespaces to be multiplexed in one engine.io session. This means if you have multiple concerns, you don't need to create multiple sessions (and consequently multiple WebSockets) to handle them.

Play socket.io provides a clean separation of engine.io and socket.io. When you create a socket.io engine, you specify how connection should be handled, you give it an Pekko streams `Flow` to handle the default namespace, and then you can add flows for any other namespace you wish to add. Once you have configured that, you can get an instance of an `EngineIOController`, this can then be plugged into your existing Play router to route requests to it.

## Wiring dependencies

### Compile time injection

If using compile time injection, you can mix `play.socketio.scaladsl.SocketIOComponents` into your application trait. This provides an instance of `play.socketio.scaladsl.SocketIO` as `socketIO`, which you can use for creating socket.io endpoints in your application. Typically, you will want to use it to create a `EngineIOController`, which you can then be injected into your router, like so:

```scala
import play.api.ApplicationLoader
import play.api.BuiltInComponentsFromContext
import play.socketio.scaladsl.SocketIOComponents
import play.engineio.EngineIOController
import com.softwaremill.macwire.wire

class MyApplication(context: ApplicationLoader.Context)
  extends BuiltInComponentsFromContext(context)
  with SocketIOComponents {

  lazy val engineIOController: EngineIOController = 
    socketIO.builder.createController()

  override lazy val router = {
    val prefix = "/"
    wire[_root_.router.Routes]
  }
  override lazy val httpFilters = Nil
}
```

The above creates the simplest socket.io engine possible - it ignores all incoming events, and produces no outgoing events, and provides no namespaces. If you want to do something useful, you'll have to configure the builder before creating the controller, which is best done by creating a class that takes `socketIO` as a dependency, and making it responsible for creating the controller.

### Guice

If using Guice, a module is automatically provided that makes a `play.socketio.scaladsl.SocketIO` injectable. You can then use that to make an instance of `EngineIOController` injectable by implementing a JSR330 provider, for example:

```scala
import javax.inject.{Inject, Provider, Singleton}
import play.engineio.EngineIOController
import play.socketio.scaladsl.SocketIO

@Singleton
class MySocketIOEngineProvider @Inject() (socketIO: SocketIO) 
  extends Provider[EngineIOController] {
  
  override lazy val get = socketIO.builder.createController()
}
```

The above creates the simplest socket.io engine possible - it ignores all incoming events, and produces no outgoing events, and provides no namespaces. If you want to do something useful, you'll have to configure the builder before creating the controller. Having done that, you can bind that provider in your applications Guice module:

```scala
import play.api._
import play.api.inject.Module
import play.engineio.EngineIOController

class MyModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[EngineIOController].toProvider[MySocketIOEngineProvider]
  )
}
```

Now the controller will be available to be injected into a router.

## Routing

Now that you have an `EngineIOController` available to be injected, you can add routes for it into your router. This can be done by adding the following to your `routes` file:

```
GET     /socket.io/         play.engineio.EngineIOController.endpoint(transport)
POST    /socket.io/         play.engineio.EngineIOController.endpoint(transport)
```

It's important that both the `GET` and `POST` methods are routed there, if not, the polling transport won't work. You can see that the path we're routing for is `/socket.io/`, this is the default path that the JavaScript socket.io client uses to connect to your server.  If you change that, you'll need to update here.

## Codecs

### Understanding socket.io messages

Before we talk about defining a codec, we need to understand what socket.io messages look like. Sending a socket.io message from a client looks like this:

```javascript
socket.emit("event name", arg1, arg2);
```

And you might handle the same event like this:

```javascript
socket.on("event name", function(arg1, arg2) {
  
});
```

As you can see, each event has a name, which is a string, and then zero or more arguments. These arguments are either something that can be expressed as JSON, or they are arbitrary binary blobs. When modelling these events in Scala, we use a type that looks like this:

```scala
case class SocketIOEvent(name: String, arguments: Seq[Either[JsValue, ByteString]])
```

In addition, socket.io also supports acks, where you can pass a function along with an argument, and when the other end invokes it, the invocation is remotely transmitted to the other side. In the JavaScript client, if the last argument to an event is a function, then it's an ack. So you can send an ack by doing the following:

```javascript
socket.emit("event name", arg1, arg2, function(ackArg1, ackArg2) {
  console.log("My ack was invoked with " + ackArg1 + " and " + ackArg2);
});
```

When handling an event with an ack, the ack can be invoked like this:

```javascript
socket.on("event name", function(arg1, arg2, ack) {
  ack("I am invoking your ack", "some arg2");
});
```

Acks can have zero or many arguments, and like regular events, they can either by JSON or binary.  So, adding this to our Scala model, and we now have messages that look like this:

```scala
case class SocketIOEvent(name: String, arguments: Seq[Either[JsValue, ByteString]],
  ack: Option[Seq[Either[JsValue, ByteString]] => Unit])
```

This is exactly how Play socket.io models events. These events are passed through Pekko streams, however, they are quite unwieldy to work with, consequently, we need to define a codec to translate these events into something simpler.

### Defining a codec

Play socket.io provides a straight forward DSL for defining codecs.  Here's an example:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._

val decoder = decodeByName {
  case "chat message" => decodeJson[String]
}

val encoder = encodeByType[String] {
  case _: String => "chat message" -> encodeJson[String]
}
```

This decoder and encoder pair both encode events called "chat message", and they expect a single argument which is a JSON string. The decoder uses the event name to decide how to decode the message, since it's getting the socket.io messages in from the client. Meanwhile, the encoder uses the type of the event to decide how to encode it, since it's getting the high level events in. The encoder needs to, for each type, return a tuple of the event name, and an encoder to encode its arguments.

`decodeJson` and `encodeJson` are argument encoders/decoders, they can be combined to encode/decode multiple arguments. If supplied just by themselves, as is the case above, they will just encode/decode a single argument message, ignoring all other arguments.

Encoders and decoders are actually just plain partial functions, so they can be composed using `orElse`, `andThen`, and so on. Here we have an example of encoding multiple types of messages:

```scala
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.socketio.scaladsl.SocketIOEventCodec._

sealed trait ChatEvent {
  def user: Option[User]
  def room: String
}

case class ChatMessage(user: Option[User], room: String, message: String) extends ChatEvent
object ChatMessage {
  implicit val format: Format[ChatMessage] = Json.format
}

case class JoinRoom(user: Option[User], room: String) extends ChatEvent
object JoinRoom {
  implicit val format: Format[JoinRoom] = Json.format
}

case class LeaveRoom(user: Option[User], room: String) extends ChatEvent
object LeaveRoom {
  implicit val format: Format[LeaveRoom] = Json.format
}

case class User(name: String)
object User {
  // We're just encoding user as a simple string, not an object
  implicit val format: Format[User] = implicitly[Format[String]]
    .inmap(User.apply, _.name)
}

val decoder = decodeByName {
  case "chat message" => decodeJson[ChatMessage]
  case "join room" => decodeJson[JoinRoom]
  case "leave room" => decodeJson[LeaveRoom]
}

val encoder = encodeByType[ChatEvent] {
  case _: ChatMessage => "chat message" -> encodeJson[ChatMessage]
  case _: JoinRoom => "join room" -> encodeJson[JoinRoom]
  case _: LeaveRoom => "leave room" -> encodeJson[LeaveRoom]
}
```

If you're familiar with play-json, then you'll be familiar with why each message type has a format defined on its companion object. One thing to note here, since all the messages being encoded share the same parent trait, `ChatEvent`, the encoder and decoder will accept and produce `ChatEvent` respectively. This means when applied to our stream, we will have a strongly typed `Flow[ChatEvent, ChatEvent, _]` to work with events. If they didn't share a common parent trait, the type would end up being `Any`.

### Encoding/decoding multiple arguments

The examples we've seen so far are for encoding and decoding single arguments. What if multiple arguments are needed? Argument decoders and encoders can be combined, to create tuples of arguments, for example:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._

val decoder = decodeByName {
  case "chat message" => decodeJson[String] ~ decodeJson[String] ~ decodeJson[String]
  case "join room" => decodeJson[String] ~ decodeJson[String]
}

val encoder = encodeByType[Any] {
  case (_: String, _: String, _: String) => 
    "chat message" -> encodeJson[String] ~ encodeJson[String] ~ encodeJson[String]
  case (_: String, _: String) => 
    "join room" -> encodeJson[String] ~ encodeJson[String]
}
```

Now, instead of handling high level arguments, we are handling tuples of strings. We are decoding/encoding the chat message as a 3-tuple of strings, and the join room message as a 2-tuple of strings. Working like this however has a problem - if we also want to decode/encode leave room, it would also end up being a 2-tuple of string, which would prevent us from distinguishing between leave room and join room when we encode it. Fortunately, our argument encoder/decoders are just regular functions, and so can be composed accordingly:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._

val decoder = decodeByName {
  case "chat message" => decodeJson[String] ~ decodeJson[String] ~ decodeJson[String] andThen {
    case (user, room, message) => ChatMessage(user, room, message)
  }
  case "join room" => decodeJson[String] ~ decodeJson[String] andThen {
    case (user, room) => JoinRoom(user, room)
  }
  case "leave room" => decodeJson[String] ~ decodeJson[String] andThen {
    case (user, room) => LeaveRoom(user, room)
  }
}

val encoder = encodeByType[ChatEvent] {
  case _: ChatMessage => 
    "chat message" -> (encodeJson[String] ~ encodeJson[String] ~ encodeJson[String] compose[ChatMessage] {
      case ChatMessage(user, room, message) => (user, room, message)
    })
  case _: JoinRoom =>
    "join room" -> (encodeJson[String] ~ encodeJson[String] compose[JoinRoom] {
      case JoinRoom(user, room) => (user, room)
    })
  case _: LeaveRoom =>
    "leave room" -> (encodeJson[String] ~ encodeJson[String] compose[LeaveRoom] {
      case LeaveRoom(user, room) => (user, room)
    })
}
```

### Handling acks

An ack is a function that sends a message back to the client or server. So when a decoder decodes a message that has an ack function, it needs to provide an encoder to encode the message that gets sent back. For example, to encode a simple string argument:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._

val decoder = decodeByName {
  case "chat message" => decodeJson[String] withAckEncoder encodeJson[String]
}
```

The type of the above decoder is `(String, String => Unit)`. It can be mapped to a higher level type like so:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._

case class ChatMessageWithAck(message: String, ack: String => Unit)

val decoder = decodeByName {
  case "chat message" => decodeJson[String] withAckEncoder encodeJson[String] andThen {
    case (message, ack) => ChatMessageWithAck(message, ack)
  }
}
```

Acks can have multiple arguments, just like regular messages:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._

val decoder = decodeByName {
  case "chat message" => decodeJson[String] withAckEncoder (encodeJson[String] ~ encodeJson[String])
}
```

You may also want to optionally take an ack, so the client doesn't have to provide an ack if they don't want to. This can be done using `withMaybeAckEncoder`:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._

val decoder = decodeByName {
  case "chat message" => decodeJson[String] withMaybeAckEncoder encodeJson[String]
}
```

The type of this decoder is now `(String, Option[String => Unit])`.

When encoding messages with acks, you need to provide a decoder so that when the client sends an ack back, the arguments to it can be decoded and passed to your ack function:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._

val encoder = encodeByType {
  case (_: String, _) => "chat message" -> (encodeJson[String] withAckDecoder decodeJson[String])
}
```

### Handling binary arguments

Binary arguments can be handled using `decodeBytes` and `encodeBytes`, which decodes and encodes the argument to `pekko.util.ByteString`:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._
import org.apache.pekko.util.ByteString

val decoder = decodeByName {
  case "binary event" => decodeBytes
}

val encoder = encodeByType {
  case _: ByteString => "binary event" -> encodeBytes
}
```

### Handling no arguments

In certain situations you may have a message with no arguments. This can be handled by using `encodeNoArgs` or `decodeNoArgs`, which produces `pekko.NotUsed` as the message:

```scala
import play.socketio.scaladsl.SocketIOEventCodec._
import org.apache.pekko.NotUsed

val decoder = decodeByName {
  case "no arg event" => decodeNoArgs
}

val encoder = encodeByType {
  case NotUsed => "no arg event" -> encodeNoArgs
}
```

### A word of advice on designing codecs

socket.io is designed for a callback centric approach to handling events. Play on the other hand, and Play's socket.io, is designed for a streaming approach to handling events. Both approaches have their merits.

Callbacks are great when the set of possible events that you could handle is enormous compared to the set of events that you want to handle, for example, in a user interface, there are many events that occur all the time - key presses, mouse moves, component render, etc, and you're only interested in a very small subset, like when the user clicks the mouse on this particular button. In this case you want an opt in approach to handling events, and this is done well by registering callbacks.

Streams are great when you want to handle most or all of the events that could happen, and you want or need higher level features such as backpressure, lifecycle management, and ensuring that the stream progresses. A good example of this is in network communications - generally only events that you're interested in are sent over the wire since it's expensive to send things over the network. Backpressure is important to ensure that servers and clients aren't overwhelmed by the load being sent, and lifecycle management is also important, you want to ensure that errors are propagated to the right place, and that the stream always progresses and doesn't just stop silently (as often happens with callbacks when you forget to pass the right callback in the right place.)

The socket.io protocol is designed for a callback centric approach, that's why messages are treated as lists of arguments with acks. But since it's being used for network communications, we have provided a streaming centric implementation on the Play server, which gives you backpressure, better error propagation and lifecycle management. For this reason, we recommend designing your codec to be more stream centric, that means making each event simply pass one argument so that it looks more like a stream messages, and not using acks, since acks subvert backpressure and lifecycle management. This will also generally make the definition of your encoders and decoders much simpler.

## Building a socket.io engine

Once you have created a codec for your socket.io event stream, you are ready to build a socket.io engine. Here is a simple engine that simply echos the messages received, assuming we're using the `decoder` and `encoder` above that encodes/decodes chat messages to and from strings:

```scala
import play.socketio.scaladsl.SocketIO  

class MyEngine(socketIO: SocketIO) {
  val controller = {
    socketIO.builder
      .defaultNamespace(decoder, encoder, Flow[String])
      .createController()  
  }
}
```

The above is not that useful since it only lets you chat with yourself, we can use a merge and broadcast hub to create a chat room that allows all users to talk to each other:

```scala
import play.socketio.scaladsl.SocketIO  
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.NotUsed

class MyEngine(socketIO: SocketIO)(implicit mat: Materializer) {

  val chatFlow: Flow[String, String, NotUsed] = {
    val (sink, source) = MergeHub.source[String].toMat(BroadcastHub.sink)(Keep.both).run()
    Flow.fromSinkAndSourceCoupled(sink, source)
  }

  val controller = {
    socketIO.builder
      .defaultNamespace(decoder, encoder, chatFlow)
      .createController()  
  }
}
```

### Adding namespaces

So far we've seen configuring the default namespace, you can also add other namespaces, for example:

```scala
import play.socketio.scaladsl.SocketIO  
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.NotUsed

class MyEngine(socketIO: SocketIO)(implicit mat: Materializer) {

  val chatFlow: Flow[String, String, NotUsed] = {
    val (sink, source) = MergeHub.source[String].toMat(BroadcastHub.sink)(Keep.both).run()
    Flow.fromSinkAndSourceCoupled(sink, source)
  }

  val controller = {
    socketIO.builder
      .defaultNamespace(decoder, encoder, chatFlow)
      .addNamespace("/echo", decoder, encoder, Flow[String])
      .createController()  
  }
}
```

### Using sessions

When you first receive a socket.io request, you can extract information from the request, such as cookies, to, for example, authenticate the user. Here's an example of using the Play session to authenticate a user:

```scala
socketIO.builder
  .onConnect { (request, sessionId) => 
    request.session.get("user") match {
      case Some(user) => user
      case None => throw new NotAuthenticatedException()
    }
  }
```

You can also do asynchronous operations, for example, if you wanted to load the user details from a database:

```scala
socketIO.builder
  .onConnectAsync { (request, sessionId) => 
    request.session.get("user") match {
      case Some(user) => userDao.loadUser(user)
      case None => throw new NotAuthenticatedException()
    }
  }
```

Having extracted some data for the session, you can now use that data when connecting to either the default namespace:

```scala
socketIO.builder
  .onConnect { (request, sessionId) => 
    request.session.get("user") match {
      case Some(user) => user
      case None => throw new NotAuthenticatedException()
    }
  }
  .defaultNamespace(decoder, encoder) { session =>
    val user = session.data
    // Create flow here
    Flow[String].map(message => s"You are $user and you said $message")
  }
```

Or to a custom namespace:

```scala
socketIO.builder
  .onConnect { (request, sessionId) => 
    request.session.get("user") match {
      case Some(user) => user
      case None => throw new NotAuthenticatedException()
    }
  }
  .addNamespace(decoder, encoder) {
    case (SocketIOSession(sessionId, user), "/echo") =>
      Flow[String].map(message => s"You are $user and you said $message")
  }
```

### Error handling

By default, Play socket.io will send the message of any exceptions encountered to the client as a String. You can customise the error handling by providing a custom error handler:

```scala
socketIO.builder
  .withErrorHandler {
    case _: NotAuthenticatedException => JsString("You are not authenticated")
  }
```

The error handler needs to return a `play.api.libs.json.JsValue`, this will be available as the argument to the error handler on the client. Any errors that your error handler doesn't handle will fallback to the built in error handler.

## Multi-node setup

Play socket.io is designed to work with Pekko clustering in a multi-node setup. Many other socket.io server implementations require sticky load balancing to ensure requests from one client always go to the same node - Play socket.io does not require this, you can use any load balancing approach, such as round robin, to route requests to any node, and Pekko clustering can ensure that the engine.io messages sent to that node will be forwarded to the node where that session lives.

The simplest way to do this is to use Pekko's consistent hashing router. This can be configured like so in your `application.conf`:

```
play.engine-io {

  # The router name. This tells play-engine.io to use a router with this name,
  # which is configured below.
  router-name = "engine.io-router"
}

pekko {
  actor {

    # Enable clustering
    provider = "cluster"

    deployment {

      # This actor path matches the configured play.engine-io.router-name above.
      "/engine.io-router" {

        # We use a consistent hashing group.
        router = consistent-hashing-group

        # This is the default path for the engine.io manager actor.
        # If you've changed that (via the play.engine-io.actor-name setting),
        # then this must be updated to match.
        routees.paths = ["/user/engine.io"]

        cluster {
          enabled = on
          allow-local-routees = on
        }
      }
    }   
  }
}
```

Now the only thing needed to be done is to configure Pekko clustering, which is beyond the scope of this documentation. Full documentation for configuring Pekko clustering can be found [here](https://pekko.apache.org/docs/pekko/current/typed/cluster.html).

## Configuration

Play socket.io provides a number of configuration options, here is the `reference.conf` for them:

```
# Play EngineIO config
play.engine-io {

  # The ping interval to use to send to clients. This is used both by clients
  # to determine how often they should ping, as well as by the socket-io
  # session server to determine how often it should check to see if a session
  # has timed out and to do other clean up tasks
  ping-interval = 25 seconds

  # The ping timeout. If a socket.io client can't get a response in this time,
  # it will consider the connection dead. Likewise, if the server doesn't
  # receive a ping in this time, it will consider the connection dead.
  ping-timeout = 60 seconds

  # The list of transports the server should advertise that it supports. The
  # two valid values are websocket and polling. Note that changing this list
  # won't actually disable the servers support for the transports, it will
  # just change whether the server will advertise these as available upgrades
  # to the client.
  transports = ["websocket", "polling"]

  # The name of the actor to create for the engine.io manager.
  actor-name = "engine.io"

  # The router name for the engine.io router. This path should correspond to
  # a configured router group, such as a cluster consistent hashing router.
  # The routees of that actor should be the path to the configured actor-name.
  # If null, no router group will be used, messages will be sent directly to
  # the engine.io manager actor.
  router-name = null

  # The role to start the engine.io actors on. Useful when using a consistent
  # hashing cluster router, to have engine.io sessions only run on some nodes.
  # This must match the cluster.use-role setting in the configured router. If
  # null, will start the actors on every node. This setting will have no
  # effect if router-name is null.
  use-role = null

}

# socket.io specific config
play.socket-io {

  # How long the client has to respond to an ack before the server will
  # forget about the ack. Since the server has to track all the ack
  # functions it sends, if the client doesn't ack them, then this will
  # result in the ack map growing indefinitely for a session. Consequently,
  # the server periodically cleans up all expired acks to avoid this.
  ack-deadline = 60 seconds

  # How often expired acks should be cleaned up. Expired acks will be checked
  # every this many acks that we send.
  ack-cleanup-every = 10
}
```

## Examples

A number of example applications have been written, all based on the use case of a chat server.

### Simple chat server

The simple chat server can be found [here](../samples/scala/chat), it provides a minimal chat server with a single room, and no concept of different users. It implements exactly the same system as the official socket.io chat example tutorial written [here](https://socket.io/get-started/chat/), except that the backend of course is a Play backend.

### Multi-room chat server

The multi room chat server can be found [here](../samples/scala/multi-room-chat). This is an extension of the simple chat server, it allows users to log in and join and leave different rooms. It demonstrates a more complex dynamic Pekko streams setup, along with more complex codecs than simple strings.

### Clustered chat server

The multi room chat server can be found [here](../samples/scala/clustered-chat). This is the multi-room chat server example, modified to run in a cluster.  It configures Play socket.io to run in a cluster, and also modifies the streams for the backend rooms to use Pekko distributed pubsub. It includes a script that sets up three nodes running in a cluster, with an nginx round robin load balancer in front of them.
