# Play socket.io Java support

This describes the Play socket.io Java support.

Note that we are using [Lombok](https://projectlombok.org/) in all code examples for brevity.

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

If using Guice, a module is automatically provided that makes a `play.socketio.javadsl.SocketIO` injectable. You can then use that to make an instance of `EngineIOController` injectable by implementing a JSR330 provider, for example:

```java
import javax.inject.*;
import play.engineio.EngineIOController;
import play.socketio.javadsl.SocketIO;

@Singleton
public class MySocketIOEngineProvider implements Provider<EngineIOController> {
  private final EngineIOController controller;
    
  @Inject
  public MySocketIOEngineProvider(SocketIO socketIO) {
    controller = socketIO.createBuilder().createController();
  }
  
  @Override
  public EngineIOController get() {
    return controller;
  }  
}
```

The above creates the simplest socket.io engine possible - it ignores all incoming events, and produces no outgoing events, and provides no namespaces. If you want to do something useful, you'll have to configure the builder before creating the controller. Having done that, you can bind that provider in your applications Guice module:

```java
import com.google.inject.AbstractModule;
import play.engineio.EngineIOController;

public class MyModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(EngineIOController.class).toProvider(MySocketIOEngineProvider.class);
  }
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

As you can see, each event has a name, which is a string, and then zero or more arguments. These arguments are either something that can be expressed as JSON, or they are arbitrary binary blobs. When modelling these events in Java, we use a type that looks like this:

```java
@Value
public class SocketIOEvent {
  String name;
  List<Either<JsonNode, ByteString>> arguments;
}
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

Acks can have zero or many arguments, and like regular events, they can either by JSON or binary.  So, adding this to our Java model, and we now have messages that look like this:

```java
@Value
public class SocketIOEvent {
  String name;
  List<Either<JsonNode, ByteString>> arguments;
  Optional<Consumer<Either<JsonNode, ByteString>>> ack;
}
```

This is exactly how Play socket.io models events. These events are passed through Pekko streams, however, they are quite unwieldy to work with, consequently, we need to define a codec to translate these events into something simpler.

### Defining a codec

Play socket.io provides a straight forward DSL for defining codecs.  Here's an example:

```java
import play.socketio.javadsl.SocketIOEventCodec;

public class MyCodec extends SocketIOEventCodec<String, String> {
  {
    addDecoder("chat message", decodeJson(String.class));
    addEncoder("chat message", String.class, encodeJson());
  }
}
```

This decoder and encoder pair both encode events called "chat message", and they expect a single argument which is a JSON string. The decoder uses the event name to decide how to decode the message, since it's getting the socket.io messages in from the client. Meanwhile, the encoder uses the type of the event to decide how to encode it, since it's getting the high level events in. The encoder needs to, for each type, return a tuple of the event name, and an encoder to encode its arguments.

`decodeJson` and `encodeJson` are argument encoders/decoders, they can be combined to encode/decode multiple arguments. If supplied just by themselves, as is the case above, they will just encode/decode a single argument message, ignoring all other arguments.

Of course, usually you have more than just one type of message, here's an example of encoding multiple types of messages:

```java
import play.socketio.javadsl.SocketIOEventCodec;
import lombok.Value;
import com.fasterxml.jackson.annotation.JsonValue;

public class MyCodec extends SocketIOEventCodec<ChatEvent, ChatEvent>{
  public interface ChatEvent {
    User getUser();
    String getRoom();
  }
  
  @Value
  public static class ChatMessage extends ChatEvent {
    User user;
    String room;
    String message;
  }

  @Value
  public static class JoinRoom extends ChatEvent {
    User user;
    String room;
  }

  @Value
  public static class LeaveRoom extends ChatEvent {
    User user;
    String room;
  }
  
  @Value
  public static class User {
    @JsonValue
    String name;
  }
  
  {
    addDecoder("chat message", decodeJson(ChatMessage.class));
    addDecoder("join room", decodeJson(JoinRoom.class));
    addDecoder("leave room", decodeJson(LeaveRoom.class));
    
    addEncoder("chat message", ChatMessage.class, encodeJson());
    addEncoder("join room", JoinRoom.class, encodeJson());
    addEncoder("leave room", LeaveRoom.class, encodeJson());    
  }
}
```

If you're familiar with play-json, then you'll be familiar with why each message type has a format defined on its companion object. One thing to note here, since all the messages being encoded share the same parent trait, `ChatEvent`, the encoder and decoder will accept and produce `ChatEvent` respectively. This means when applied to our stream, we will have a strongly typed `Flow<ChatEvent, ChatEvent, ?>` to work with events. If they didn't share a common parent trait, the type would end up being `Object`.

### Encoding/decoding multiple arguments

The examples we've seen so far are for encoding and decoding single arguments. What if multiple arguments are needed? Argument decoders and encoders can be combined, to create tuples of arguments, for example:

```java
import play.socketio.javadsl.SocketIOEventCodec;

public class MyCodec extends SocketIOEventCodec<Object, Object> {
  {
    addDecoder("chat message", 
      decodeJson(String.class).and(decodeJson(String.class)).and(decodeJson(String.class))
    );
    addDecoder("join room", 
      decodeJson(String.class).and(decodeJson(String.class))
    );

    addEncoder("chat message", Tuple3.class, 
      encodeJson().and(encodeJson()).and(encodeJson())
    );
    addEncoder("join room", Pair.class, 
      encodeJson().and(encodeJson())
    );
  }
}
```

Now, instead of handling high level arguments, we are handling tuples of strings. We are decoding/encoding the chat message as a 3-tuple of strings, and the join room message as a 2-tuple (pair) of strings. Working like this however has a problem - if we also want to decode/encode leave room, it would also end up being a 2-tuple of string, which would prevent us from distinguishing between leave room and join room when we encode it. Fortunately, our argument encoder/decoders are just regular functions, and so can be composed accordingly:

```java
import play.socketio.javadsl.SocketIOEventCodec;
import org.apache.pekko.japi.Pair;
import play.libs.F.Tuple3;

public class MyCodec extends SocketIOEventCodec<ChatEvent, ChatEvent> {
  {
    addDecoder("chat message", 
      decodeJson(String.class).and(decodeJson(String.class)).and(decodeJson(String.class))
        .map(tuple3 -> new ChatMessage(tuple3._1, tuple3._2, tuple3._3))
    );
    addDecoder("join room", 
      decodeJson(String.class).and(decodeJson(String.class))
        .map(pair -> new JoinRoom(pair.first(), pair.second()))
    );
    addDecoder("leave room", 
      decodeJson(String.class).and(decodeJson(String.class))
        .map(pair -> new LeaveRoom(pair.first(), pair.second()))
    );

    addEncoder("chat message", ChatMessage.class, 
      encodeJson().and(encodeJson()).and(encodeJson())
        .contramap(m -> new Tuple3(m.user, m.room, m.message))
    );
    addEncoder("join room", JoinRoom.class, 
      encodeJson().and(encodeJson())
        .contramap(jr -> new Pair(jr.user, jr.room))
    );
    addEncoder("leave room", LeaveRoom.class, 
      encodeJson().and(encodeJson())
        .contramap(lr -> new Pair(lr.user, lr.room))
    );
  }
}
```

### Handling acks

An ack is a function that sends a message back to the client or server. So when a decoder decodes a message that has an ack function, it needs to provide an encoder to encode the message that gets sent back. For example, to encode a simple string argument:

```java
import play.socketio.javadsl.SocketIOEventCodec;
import org.apache.pekko.japi.Pair;
import java.util.function.Consumer;

public class MyCodec extends SocketIOEventCodec<Pair<String, Consumer<String>>, Object> {
  {
    addDecoder("chat message", 
      decodeJson(String.class).withAckEncoder(encodeJson())
    );
  }
}
```

The type of the above decoder is `Pair<String, Consumer<String>>`. It can be mapped to a higher level type like so:

```java
import play.socketio.javadsl.SocketIOEventCodec;
import org.apache.pekko.japi.Pair;
import java.util.function.Consumer;
import lombok.Value;

public class MyCodec extends SocketIOEventCodec<Pair<String, Consumer<String>>, Object> {
  
  @Value
  public static class ChatMessageWithAck {
    String message;
    Consumer<String> ack;
  }
  
  {
    addDecoder("chat message", 
      decodeJson(String.class).withAckEncoder(encodeJson())
        .map(pair -> new ChatMessageWithAck(pair.first(), pair.second()))
    );
  }
}
```

Acks can have multiple arguments, just like regular messages:

```java
import play.socketio.javadsl.SocketIOEventCodec;
import org.apache.pekko.japi.Pair;
import java.util.function.Consumer;

public class MyCodec extends SocketIOEventCodec<Pair<String, Consumer<Pair<String, String>>>, Object> {
  {
    addDecoder("chat message", 
      decodeJson(String.class).withAckEncoder(encodeJson().and(encodeJson()))
    );
  }
}
```
You may also want to optionally take an ack, so the client doesn't have to provide an ack if they don't want to. This can be done using `withMaybeAckEncoder`:

```java
import play.socketio.javadsl.SocketIOEventCodec;
import org.apache.pekko.japi.Pair;
import java.util.function.Consumer;

public class MyCodec extends SocketIOEventCodec<Pair<String, Optional<Consumer<String>>>, Object> {
  {
    addDecoder("chat message", 
      decodeJson(String.class).withMaybeAckEncoder(encodeJson())
    );
  }
}
```

The type of this decoder is now `Pair<String, Optional<Consumer<String>>>`.

When encoding messages with acks, you need to provide a decoder so that when the client sends an ack back, the arguments to it can be decoded and passed to your ack function:


```java
import play.socketio.javadsl.SocketIOEventCodec;
import org.apache.pekko.japi.Pair;
import java.util.function.Consumer;

public class MyCodec extends SocketIOEventCodec<Object, Pair<String, Consumer<String>>> {
  {
    addEncoder("chat message", Pair.class, 
      this.<String>encodeJson().withAckDecoder(decodeJson(String.class))
    );
  }
}
```

### Handling binary arguments

Binary arguments can be handled using `decodeBytes` and `encodeBytes`, which decodes and encodes the argument to `pekko.util.ByteString`:

```java
import play.socketio.javadsl.SocketIOEventCodec;

public class MyCodec extends SocketIOEventCodec<ByteString, ByteString> {
  {
    addDecoder("binary message", decodeBytes());
    addEncoder("binary message", ByteString.class, encodeBytes());
  }
}
```

### Handling no arguments

In certain situations you may have a message with no arguments. This can be handled by using `encodeNoArgs` or `decodeNoArgs`, which produces `pekko.NotUsed` as the message:

```java
import play.socketio.javadsl.SocketIOEventCodec;

public class MyCodec extends SocketIOEventCodec<NotUsed, NotUsed> {
  {
    addDecoder("no arg event", decodeNoArgs());
    addEncoder("no arg event", NotUsed.class, encodeNoArgs());
  }
}
```

### A word of advice on designing codecs

socket.io is designed for a callback centric approach to handling events. Play on the other hand, and Play's socket.io, is designed for a streaming approach to handling events. Both approaches have their merits.

Callbacks are great when the set of possible events that you could handle is enormous compared to the set of events that you want to handle, for example, in a user interface, there are many events that occur all the time - key presses, mouse moves, component render, etc, and you're only interested in a very small subset, like when the user clicks the mouse on this particular button. In this case you want an opt in approach to handling events, and this is done well by registering callbacks.

Streams are great when you want to handle most or all of the events that could happen, and you want or need higher level features such as backpressure, lifecycle management, and ensuring that the stream progresses. A good example of this is in network communications - generally only events that you're interested in are sent over the wire since it's expensive to send things over the network. Backpressure is important to ensure that servers and clients aren't overwhelmed by the load being sent, and lifecycle management is also important, you want to ensure that errors are propagated to the right place, and that the stream always progresses and doesn't just stop silently (as often happens with callbacks when you forget to pass the right callback in the right place.)

The socket.io protocol is designed for a callback centric approach, that's why messages are treated as lists of arguments with acks. But since it's being used for network communications, we have provided a streaming centric implementation on the Play server, which gives you backpressure, better error propagation and lifecycle management. For this reason, we recommend designing your codec to be more stream centric, that means making each event simply pass one argument so that it looks more like a stream messages, and not using acks, since acks subvert backpressure and lifecycle management. This will also generally make the definition of your encoders and decoders much simpler.

## Building a socket.io engine

Once you have created a codec for your socket.io event stream, you are ready to build a socket.io engine. Here is a simple engine that simply echos the messages received, assuming we're using the `decoder` and `encoder` above that encodes/decodes chat messages to and from strings:

```java
import play.socketio.javadsl.SocketIO;
import org.apache.pekko.stream.javadsl.Flow;

public class MyEngine {
  
  private final EngineIOController controller;
  
  public MyEngine(SocketIO socketIO) {
    controller = EngineIsocketIO.createBuilder()
      .defaultNamespace(new MyCodec(), Flow.create())
      .createController();
  }
}
```

The above is not that useful since it only lets you chat with yourself, we can use a merge and broadcast hub to create a chat room that allows all users to talk to each other:

```java
import play.socketio.javadsl.SocketIO;
import org.apache.pekko.stream.Meterializer;
import org.apache.pekko.stream.javadsl.*;
import org.apache.pekko.NotUsed;

public class MyEngine {
  
  private final EngineIOController controller;
  
  public MyEngine(SocketIO socketIO, Materializer mat) {
    Pair<Sink<String, NotUsed>, Source<String, NotUsed>> pair =
      MergeHub.of(String.class)
        .toMat(BroadcastHub.of(String.class), Keep.both())
        .run(mat);
    
    Flow<String, String, NotUsed> chatFlow = 
      Flow.fromSinkAndSourceCoupled(pair.first(), pair.second());
    
    controller = EngineIsocketIO.createBuilder()
      .defaultNamespace(new MyCodec(), chatFlow)
      .createController();
  }
}
```

### Adding namespaces

So far we've seen configuring the default namespace, you can also add other namespaces, for example:

```java
import play.socketio.javadsl.SocketIO;
import org.apache.pekko.stream.Meterializer;
import org.apache.pekko.stream.javadsl.*;
import org.apache.pekko.NotUsed;

public class MyEngine {
  
  private final EngineIOController controller;
  
  public MyEngine(SocketIO socketIO, Materializer mat) {
    Pair<Sink<String, NotUsed>, Source<String, NotUsed>> pair =
      MergeHub.of(String.class)
        .toMat(BroadcastHub.of(String.class), Keep.both())
        .run(mat);
    
    Flow<String, String, NotUsed> chatFlow = 
      Flow.fromSinkAndSourceCoupled(pair.first(), pair.second());
    
    controller = EngineIsocketIO.createBuilder()
      .defaultNamespace(new MyCodec(), chatFlow)
      .addNamespace("/echo", new MyCodec(), Flow.create())
      .createController();
  }
}
```

### Using sessions

When you first receive a socket.io request, you can extract information from the request, such as cookies, to, for example, authenticate the user. Here's an example of using the Play session to authenticate a user:

```java
socketIO.createBuilder()
  .onConnect((request, sessionId) -> {
    String user = request.session().get("user");
    if (user == null) {
      throw new NotAuthenticatedException();
    } else {
      return user;
    }
  })  
```

You can also do asynchronous operations, for example, if you wanted to load the user details from a database:

```java
socketIO.createBuilder()
  .onConnectAsync((request, sessionId) -> {
    String user = request.session().get("user");
    if (user == null) {
      throw new NotAuthenticatedException();
    } else {
      return userDao.loadUser(user);
    }
  })  
```

Having extracted some data for the session, you can now use that data when connecting to either the default namespace:

```java
socketIO.createBuilder()
  .onConnect((request, sessionId) -> {
    String user = request.session().get("user");
    if (user == null) {
      throw new NotAuthenticatedException();
    } else {
      return user;
    }
  })
  .defaultNamespace(new MyCodec(), session -> {
    String user = session.data();
    return Flow.<String>create().map(message ->
      "You are " + user + " and you said " + message
    );
  })
```

Or to a custom namespace:

```java
socketIO.createBuilder()
  .onConnect((request, sessionId) -> {
    String user = request.session().get("user");
    if (user == null) {
      throw new NotAuthenticatedException();
    } else {
      return user;
    }
  })
  .addNamespace(new MyCodec(), (session, namespace) -> {
    if (namespace.equals("/echo")) {
      String user = session.data();
      return Optional.of(Flow.<String>create().map(message ->
        "You are " + user + " and you said " + message
      ));
    } else {
      return Optional.empty();
    }
  })
```

### Error handling

By default, Play socket.io will send the message of any exceptions encountered to the client as a String. You can customise the error handling by providing a custom error handler:

```java
socketIO.createBuilder()
  .withErrorHandler(e -> {
    if (e instanceOf NotAuthenticatedException) {
      return Optional.of(TextNode.valueOf("Not authenticated!"));
    } else {
      return Optional.empty();
    }
  })  
```

The error handler needs to return a `JsonNode`, this will be available as the argument to the error handler on the client. Any errors that your error handler doesn't handle will fallback to the built in error handler.

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

Now the only thing needed to be done is to configure Pekko clustering, which is beyond the scope of this documentation. Full documentation for configuring Pekko clustering can be found [here](http://doc.akka.io/docs/akka/2.6/scala/cluster-usage.html).

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

The simple chat server can be found [here](../samples/java/chat), it provides a minimal chat server with a single room, and no concept of different users. It implements exactly the same system as the official socket.io chat example tutorial written [here](https://socket.io/get-started/chat/), except that the backend of course is a Play backend.

### Multi-room chat server

The multi room chat server can be found [here](../samples/java/multi-room-chat). This is an extension of the simple chat server, it allows users to log in and join and leave different rooms. It demonstrates a more complex dynamic Pekko streams setup, along with more complex codecs than simple strings.

### Clustered chat server

The multi room chat server can be found [here](../samples/java/clustered-chat). This is the multi-room chat server example, modified to run in a cluster.  It configures Play socket.io to run in a cluster, and also modifies the streams for the backend rooms to use Pekko distributed pubsub. It includes a script that sets up three nodes running in a cluster, with an nginx round robin load balancer in front of them.
