/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package chat;

import akka.NotUsed;
import akka.japi.Pair;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import lombok.val;
import play.engineio.EngineIOController;
import play.socketio.javadsl.SocketIO;
import play.socketio.javadsl.SocketIOEventCodec;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import chat.ChatEvent.*;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class ChatEngine implements Provider<EngineIOController> {

  private final EngineIOController controller;
  private final Materializer materializer;
  // All the chat rooms
  private final ConcurrentHashMap<String, Pair<Sink<ChatEvent, NotUsed>, Source<ChatEvent, NotUsed>>> chatRooms =
      new ConcurrentHashMap<>();

  @Inject
  @SuppressWarnings("unchecked")
  public ChatEngine(SocketIO socketIO, Materializer materializer) {
    this.materializer = materializer;

    // Here we define our codec. We're serializing our events to/from json.
    val codec = new SocketIOEventCodec<ChatEvent, ChatEvent>() {
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
          // Extract the username from the header
          val username = request.getQueryString("user");
          if (username == null) {
            throw new RuntimeException("No user parameter");
          }
          // And return the user, this will be the data for the session that we can read when we add a namespace
          return new User(username);

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

    val pair = chatRooms.computeIfAbsent(room, (r) -> {
      // Each chat room is a merge hub merging messages into a broadcast hub.
      return MergeHub.of(ChatEvent.class).toMat(BroadcastHub.of(ChatEvent.class), Keep.both()).run(materializer);
    });

    // A coupled sink and source ensures if either side is cancelled/completed, the other will be too.
    return Flow.fromSinkAndSourceCoupled(
        Flow.<ChatEvent>create()
            // Add the join and leave room events
            .concat(Source.<ChatEvent>single(new LeaveRoom(user, room)))
            .prepend(Source.<ChatEvent>single(new JoinRoom(user, room)))
            .to(pair.first()),
        pair.second()
    );
  }

  private Flow<ChatEvent, ChatEvent, NotUsed> createFlow(User user) {
    // broadcast source and sink for demux/muxing multiple chat rooms in this one flow
    // They'll be provided later when we materialize the flow
    Source<ChatEvent, NotUsed>[] broadcastSource = new Source[1];
    Sink<ChatEvent, NotUsed>[] mergeSink = new Sink[1];

    // Create a chat flow for a user session
    return Flow.<ChatEvent>create().map(event -> {
      if (event instanceof JoinRoom) {
        val room = event.getRoom();
        val roomFlow = getChatRoom(room, user);

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