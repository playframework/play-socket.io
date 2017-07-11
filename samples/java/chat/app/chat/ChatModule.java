package chat;

import com.google.inject.AbstractModule;
import play.engineio.EngineIOController;

/**
 * The chat module.
 */
public class ChatModule extends AbstractModule {
  protected void configure() {
    bind(EngineIOController.class).toProvider(ChatEngine.class);
  }
}
