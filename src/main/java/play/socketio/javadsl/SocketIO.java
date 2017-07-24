/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.javadsl;

import akka.NotUsed;
import akka.stream.Materializer;
import akka.stream.javadsl.BidiFlow;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.scaladsl.Source;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import play.engineio.EngineIO;
import play.engineio.EngineIOController;
import play.engineio.EngineIOSessionHandler;
import play.mvc.Http;
import play.socketio.SocketIOConfig;
import play.socketio.SocketIOEvent;
import play.socketio.SocketIOSession;
import scala.concurrent.ExecutionContext;

import javax.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The engine.io system. Allows you to create engine.io controllers for handling engine.io connections.
 */
public final class SocketIO {

  private final SocketIOConfig config;
  private final EngineIO engineIO;
  private final ExecutionContext ec;
  private final Materializer mat;

  @Inject
  public SocketIO(SocketIOConfig config, EngineIO engineIO, ExecutionContext ec, Materializer mat) {
    this.config = config;
    this.engineIO = engineIO;
    this.ec = ec;
    this.mat = mat;
  }

  /**
   * Create a builder.
   *
   * If no further configuration is done, the socket.io handler produced by this build will by default:
   *
   * <ul>
   *   <li>Accept all sessions</li>
   *   <li>Send the message of each exception the client as a JSON string</li>
   *   <li>Use a flow that ignores all incoming messages and produces no outgoing messages for the default namespace</li>
   *   <li>Provide no other namespaces</li>
   * </ul>
   */
  public SocketIOBuilder<Object> createBuilder() {
    return new SocketIOBuilder<>(
        (req, sid) -> CompletableFuture.completedFuture(NotUsed.getInstance()),
        t -> {
          if (t.getMessage() != null) {
            return Optional.of(TextNode.valueOf(t.getMessage()));
          } else {
            return Optional.of(TextNode.valueOf(t.getClass().getName()));
          }
        },
        session -> Flow.fromSinkAndSource(Sink.ignore(), Source.maybe()),
        (session, namespace) -> Optional.empty()
    );
  }

  /**
   * A builder for engine.io instances.
   */
  public class SocketIOBuilder<SessionData> {

    private final BiFunction<Http.RequestHeader, String, CompletionStage<SessionData>> connectCallback;
    private final Function<Throwable, Optional<JsonNode>> errorHandler;
    private final Function<SocketIOSession<SessionData>, Flow<SocketIOEvent, SocketIOEvent, NotUsed>> defaultNamespaceCallback;
    private final BiFunction<SocketIOSession<SessionData>, String, Optional<Flow<SocketIOEvent, SocketIOEvent, NotUsed>>> connectToNamespaceCallback;

    private SocketIOBuilder(BiFunction<Http.RequestHeader, String, CompletionStage<SessionData>> connectCallback,
        Function<Throwable, Optional<JsonNode>> errorHandler,
        Function<SocketIOSession<SessionData>, Flow<SocketIOEvent, SocketIOEvent, NotUsed>> defaultNamespaceCallback,
        BiFunction<SocketIOSession<SessionData>, String, Optional<Flow<SocketIOEvent, SocketIOEvent, NotUsed>>> connectToNamespaceCallback) {
      this.connectCallback = connectCallback;
      this.errorHandler = errorHandler;
      this.defaultNamespaceCallback = defaultNamespaceCallback;
      this.connectToNamespaceCallback = connectToNamespaceCallback;
    }

    /**
     * Set the onConnect callback.
     * <p>
     * The callback takes the request header of the incoming connection and the id of the session, and should produce a
     * session object, which can be anything, for example, a user principal, or other authentication and/or
     * authorization details.
     * <p>
     * If you wish to reject the connection, you can throw an exception, which will later be handled by the error
     * handler to turn it into a message to send to the client.
     */
    public <S extends SessionData> SocketIOBuilder<S> onConnect(
        BiFunction<Http.RequestHeader, String, S> callback
    ) {
      return onConnectAsync((rh, sid) -> CompletableFuture.completedFuture(callback.apply(rh, sid)));
    }

    /**
     * Set the onConnect callback.
     * <p>
     * The callback takes the request header of the incoming connection and the id of the session, and should produce a
     * session object, which can be anything, for example, a user principal, or other authentication and/or
     * authorization details.
     * <p>
     * If you wish to reject the connection, you can throw an exception, which will later be handled by the error
     * handler to turn it into a message to send to the client.
     */
    @SuppressWarnings("unchecked")
    public <S extends SessionData> SocketIOBuilder<S> onConnectAsync(
        BiFunction<Http.RequestHeader, String, CompletionStage<S>> callback
    ) {
      return new SocketIOBuilder<S>(
          callback,
          errorHandler,
          (Function) defaultNamespaceCallback,
          (BiFunction) connectToNamespaceCallback
      );
    }

    /**
     * Set the error handler.
     * <p>
     * If any errors are encountered, they will be serialized to JSON this function, and then passed to the client
     * using a socket.io error message.
     * <p>
     * Any errors not handled by this partial function will fallback to the existing error handler in this builder,
     * which by default sends the exception message as a JSON string.
     */
    public SocketIOBuilder<SessionData> withErrorHandler(Function<Throwable, Optional<JsonNode>> handler) {
      return new SocketIOBuilder<>(
          connectCallback,
          (Throwable t) -> handler.apply(t)
              .map(Optional::of)
              .orElseGet(() -> errorHandler.apply(t)),
          defaultNamespaceCallback,
          connectToNamespaceCallback
      );
    }

    /**
     * Set the default namespace flow.
     *
     * @param codec the codec to use.
     * @param flow  the flow.
     */
    public <In, Out> SocketIOBuilder<SessionData> defaultNamespace(SocketIOEventCodec<In, Out> codec,
        Flow<In, Out, ?> flow) {
      return defaultNamespace(codec, session -> flow);
    }

    /**
     * Set the default namespace flow.
     * <p>
     * This variant allows you to customise the returned flow according to the session.
     *
     * @param codec    the codec to use.
     * @param callback a callback to create the flow given the session.
     */
    public <In, Out> SocketIOBuilder<SessionData> defaultNamespace(SocketIOEventCodec<In, Out> codec,
        Function<SocketIOSession<SessionData>, Flow<In, Out, ?>> callback) {
      BidiFlow<SocketIOEvent, In, Out, SocketIOEvent, NotUsed> codecFlow = codec.createFlow();

      return new SocketIOBuilder<>(
          connectCallback,
          errorHandler,
          callback.andThen(codecFlow::join),
          connectToNamespaceCallback
      );
    }

    /**
     * Add a namespace.
     *
     * @param name  The name of the namespace.
     * @param codec the codec to use.
     * @param flow  The flow to use for the namespace.
     */
    public <In, Out> SocketIOBuilder<SessionData> addNamespace(String name,
        SocketIOEventCodec<In, Out> codec, Flow<In, Out, ?> flow) {
      return addNamespace(codec, (session, namespace) -> {
        // Drop the query string
        String[] parts = namespace.split("\\?", 2);
        if (parts[0].equals(name)) {
          return Optional.of(flow);
        } else {
          return Optional.empty();
        }
      });
    }

    /**
     * Add a namespace.
     * <p>
     * This variant allows you to pass a callback that pattern matches on the namespace name, and uses the session
     * data to decide whether the user should be able to connect to this namespace or not.
     * <p>
     * Any exceptions thrown here will result in an error being sent back to the client, serialized by the
     * errorHandler. Alternatively, you can simply not return a value from the partial function, which will result in
     * an error being sent to the client that the namespace does not exist.
     *
     * @param codec    the codec to use.
     * @param callback A callback to match the namespace and create a flow accordingly.
     */
    public <In, Out> SocketIOBuilder<SessionData> addNamespace(SocketIOEventCodec<In, Out> codec,
        BiFunction<SocketIOSession<SessionData>, String, Optional<Flow<In, Out, ?>>> callback) {

      BidiFlow<SocketIOEvent, In, Out, SocketIOEvent, NotUsed> codecFlow = codec.createFlow();

      return new SocketIOBuilder<>(
          connectCallback,
          errorHandler,
          defaultNamespaceCallback,
          (session, sid) -> connectToNamespaceCallback.apply(session, sid)
              .map(Optional::of)
              .orElseGet(() ->
                  callback.apply(session, sid)
                      .map(codecFlow::join)
              )
      );
    }

    /**
     * Build the engine.io controller.
     */
    public EngineIOController createController() {
      EngineIOSessionHandler handler = SocketIOSessionFlowHelper.createEngineIOSessionHandler(
          config,
          connectCallback,
          errorHandler,
          defaultNamespaceCallback,
          connectToNamespaceCallback,
          ec,
          mat
      );

      return engineIO.createController(handler);
    }

  }


}
