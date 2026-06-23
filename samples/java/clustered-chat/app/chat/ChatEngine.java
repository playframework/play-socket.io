package chat;

import chat.ChatEvent.ChatMessage;
import chat.ChatEvent.JoinRoom;
import chat.ChatEvent.LeaveRoom;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.cluster.pubsub.DistributedPubSub;
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Publish;
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Subscribe;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.javadsl.*;
import play.Logger;
import play.engineio.EngineIOController;
import play.socketio.javadsl.SocketIO;
import play.socketio.javadsl.SocketIOEventCodec;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.Optional;

@Singleton
public class ChatEngine implements Provider<EngineIOController> {

  private final EngineIOController controller;
  private final Materializer materializer;
  private final ActorRef mediator;

  @Inject
  public ChatEngine(SocketIO socketIO, Materializer materializer, ActorSystem actorSystem) {
    this.materializer = materializer;
    this.mediator = DistributedPubSub.get(actorSystem).mediator();

    // Here we define our codec. We're serializing our events to/from json.
    var codec = new SocketIOEventCodec<ChatEvent, ChatEvent>() {
      {
        addDecoder("chat message", decodeJson(ChatMessage.class));
        addDecoder("join room", decodeJson(JoinRoom.class));
        addDecoder("leave room", decodeJson(LeaveRoom.class));

        addEncoder("chat message", ChatMessage.class, encodeJson());
        addEncoder("join room", JoinRoom.class, encodeJson());
        addEncoder("leave room", LeaveRoom.class, encodeJson());
      }
    };

    controller = socketIO.createBuilder()
        .onConnect((request, sid) -> {
          Logger.info("New session created: " + sid);
          // Extract the username from the header
          var username = request.queryString("user");
          if (username.isPresent()) {
            // And return the user, this will be the data for the session that we can read when we add a namespace
            return new User(username.get());
          }
          throw new RuntimeException("No user parameter");
        }).addNamespace(codec, (session, chat) -> {
          if (chat.split("\\?")[0].equals("/chat")) {
            return Optional.of(createFlow(session.data()));
          } else {
            return Optional.empty();
          }
        })
        .createController();
  }

  // This gets an existing chat room, or creates it if it doesn't exist
  private Flow<ChatEvent, ChatEvent, NotUsed> getChatRoom(String room, User user) {

    // Create a sink that sends all the messages to the chat room
    var sink = Sink.<ChatEvent>foreach(message ->
      mediator.tell(new Publish(room, message), ActorRef.noSender())
    );

    // Create a source that subscribes to messages from the chatroom
    var source = Source.<ChatEvent>actorRef(16, OverflowStrategy.dropHead())
      .mapMaterializedValue(ref -> {
        mediator.tell(new Subscribe(room, ref), ActorRef.noSender());
        return NotUsed.getInstance();
      });

    // A coupled sink and source ensures if either side is cancelled/completed, the other will be too.
    return Flow.fromSinkAndSourceCoupled(
        Flow.<ChatEvent>create()
            // Add the join and leave room events
            .concat(Source.<ChatEvent>single(new LeaveRoom(user, room)))
            .prepend(Source.<ChatEvent>single(new JoinRoom(user, room)))
            .to(sink),
        source
    );
  }

  @SuppressWarnings("unchecked")
  private Flow<ChatEvent, ChatEvent, NotUsed> createFlow(User user) {
    // broadcast source and sink for demux/muxing multiple chat rooms in this one flow
    // They'll be provided later when we materialize the flow
    Source<ChatEvent, NotUsed>[] broadcastSource = new Source[1];
    Sink<ChatEvent, NotUsed>[] mergeSink = new Sink[1];

    // Create a chat flow for a user session
    return Flow.<ChatEvent>create().map(event -> {
      if (event instanceof JoinRoom) {
        var room = event.getRoom();
        var roomFlow = getChatRoom(room, user);

        // Add the room to our flow
        broadcastSource[0]
            // Ensure only messages for this room get there.
            // Also filter out JoinRoom messages, since there's a race condition as to whether it will
            // actually get here or not, so we explicitly add it below.
            .filter(e -> e.getRoom().equals(room) && !(e instanceof JoinRoom))
            // Take until we get a leave room message.
            .takeWhile(e -> !(e instanceof LeaveRoom))
            // We ensure that a join room message is sent first
            // And send it through the room flow
            .via(roomFlow)
            // Re-add the leave room here, since it was filtered out before
            .concat(Source.<ChatEvent>single(new LeaveRoom(user, room)))
            // And run it with the merge sink
            .runWith(mergeSink[0], materializer);
        return event;
      } else if (event instanceof ChatMessage) {
        // Add the user
        return new ChatMessage(user, event.getRoom(), ((ChatMessage) event).getMessage());
      } else {
        return event;
      }
    }).via(
        Flow.fromSinkAndSourceCoupledMat(BroadcastHub.of(ChatEvent.class), MergeHub.of(ChatEvent.class), (source, sink) -> {
          broadcastSource[0] = source;
          mergeSink[0] = sink;
          return NotUsed.getInstance();
        })
    );
  }

  public EngineIOController get() {
    return controller;
  }
}