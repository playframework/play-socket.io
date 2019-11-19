/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package chat;

import lombok.Value;

public interface ChatEvent {
  User getUser();
  String getRoom();

  @Value
  class ChatMessage implements ChatEvent {
    User user;
    String room;
    String message;
  }

  @Value
  class JoinRoom implements ChatEvent {
    User user;
    String room;
  }

  @Value
  class LeaveRoom implements ChatEvent {
    User user;
    String room;
  }
}