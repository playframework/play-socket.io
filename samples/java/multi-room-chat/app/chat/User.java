package chat;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Value;

@Value
public class User {
  String name;

  @JsonValue
  public String getName() {
    return name;
  }
}
