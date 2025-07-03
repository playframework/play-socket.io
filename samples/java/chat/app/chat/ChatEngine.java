package chat;

import org.apache.pekko.NotUsed;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.*;
import play.engineio.EngineIOController;
import play.socketio.javadsl.SocketIO;
import play.socketio.javadsl.SocketIOEventCodec;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

@Singleton
public class ChatEngine implements Provider<EngineIOController> {

  private final EngineIOController controller;

  @Inject
  public ChatEngine(SocketIO socketIO, Materializer materializer) {

    // Here we define our codec. We handle chat messages in both directions as JSON strings
    SocketIOEventCodec<String, String> codec = new SocketIOEventCodec<String, String>() {
      {
        addDecoder("chat message", decodeJson(String.class));
        addEncoder("chat message", String.class, encodeJson());
      }
    };

    // We use a MergeHub to merge all the incoming chat messages from all the
    // connected users into one flow, and we feed that straight into a
    // BroadcastHub to broadcast them out again to all the connected users.
    // See http://doc.akka.io/docs/akka/2.6/scala/stream/stream-dynamic.html
    // for details on these features.
    Pair<Sink<String, NotUsed>, Source<String, NotUsed>> pair = MergeHub.of(String.class)
        .toMat(BroadcastHub.of(String.class), Keep.both()).run(materializer);

    // We couple the sink and source together so that one completes, the other
    // will to, and we use this to handle our chat
    Flow<String, String, NotUsed> chatFlow = Flow.fromSinkAndSourceCoupled(pair.first(), pair.second());

    // Here we create an EngineIOController to handle requests for our chat
    // system, and we add the chat flow under the "/chat" namespace.
    controller = socketIO.createBuilder()
        .addNamespace("/chat", codec, chatFlow)
        .createController();
  }

  public EngineIOController get() {
    return controller;
  }
}