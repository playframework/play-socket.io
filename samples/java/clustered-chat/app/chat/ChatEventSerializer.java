package chat;

import akka.actor.ExtendedActorSystem;
import akka.serialization.SerializerWithStringManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.val;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ChatEventSerializer extends SerializerWithStringManifest {
  private final int identifier;
  private final ObjectMapper mapper = new ObjectMapper();

  public ChatEventSerializer(ExtendedActorSystem system) {
    identifier = system.settings().config().getInt("akka.actor.serialization-identifiers.\"" + getClass().getName() + "\"");
  }

  @Override
  public int identifier() {
    return identifier;
  }

  @Override
  public String manifest(Object o) {
    if (o instanceof ChatEvent.ChatMessage) {
      return "M";
    } else if (o instanceof ChatEvent.JoinRoom) {
      return "J";
    } else if (o instanceof ChatEvent.LeaveRoom) {
      return "L";
    } else {
      throw new RuntimeException("Don't know how to serialize " + o);
    }
  }

  @Override
  public byte[] toBinary(Object o) {
    val baos = new ByteArrayOutputStream();
    try {
      mapper.writeValue(baos, o);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return baos.toByteArray();
  }

  @Override
  public Object fromBinary(byte[] bytes, String manifest) {
    try {
      switch(manifest) {
        case "M":
          return mapper.readValue(bytes, ChatEvent.ChatMessage.class);
        case "J":
          return mapper.readValue(bytes, ChatEvent.JoinRoom.class);
        case "L":
          return mapper.readValue(bytes, ChatEvent.LeaveRoom.class);
        default:
          throw new RuntimeException("Unknown manifest: " + manifest);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
