/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.javadsl;

import com.google.inject.AbstractModule;
import controllers.ExternalAssets;
import org.apache.pekko.NotUsed;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.javadsl.*;
import org.apache.pekko.util.ByteString;
import play.api.Application;
import play.api.libs.json.JsString;
import play.api.libs.json.JsValue;
import play.api.routing.Router;
import play.engineio.EngineIOController;
import play.inject.guice.GuiceApplicationBuilder;
import play.socketio.SocketIOEvent;
import play.socketio.TestSocketIOApplication;
import play.socketio.TestSocketIOServer;
import scala.Function3;
import scala.Option;
import scala.concurrent.ExecutionContext;
import scala.jdk.javaapi.CollectionConverters;
import scala.util.Either;
import scala.util.Left;

import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Collections;
import java.util.Optional;

public class TestSocketIOJavaApplication implements TestSocketIOApplication {

  public static void main(String... args) {
    TestSocketIOServer.main(new TestSocketIOJavaApplication());
  }

  @Override
  public play.api.Application createApplication(Function3<ExternalAssets, EngineIOController, ExecutionContext, Router> routerBuilder) {
    Application application = new GuiceApplicationBuilder()
        .overrides(new AbstractModule() {
          @Override
          protected void configure() {
            bind(Router.class).toProvider(new RouterProvider(routerBuilder));
          }
        })
        .build().asScala();

    System.out.println("Started Java application.");
    return application;
  }

  public static class RouterProvider implements Provider<Router> {
    private final Function3<ExternalAssets, EngineIOController, ExecutionContext, Router> routerBuilder;
    @Inject private ExternalAssets extAssets;
    @Inject private SocketIO socketIO;
    @Inject private Materializer mat;
    @Inject private ExecutionContext ec;

    private RouterProvider(Function3<ExternalAssets, EngineIOController, ExecutionContext, Router> routerBuilder) {
      this.routerBuilder = routerBuilder;
    }

    @Override
    public Router get() {
      return routerBuilder.apply(extAssets, createController(socketIO, mat), ec);
    }
  }

  public static EngineIOController createController(SocketIO socketIO, Materializer mat) {
    Pair<SourceQueueWithComplete<SocketIOEvent>, Source<SocketIOEvent, NotUsed>>
        disconnectPair = Source.<SocketIOEvent>queue(10, OverflowStrategy.backpressure())
        .toMat(BroadcastHub.of(SocketIOEvent.class), Keep.both()).run(mat);
    Flow<SocketIOEvent, SocketIOEvent, NotUsed> testDisconnectFlow = Flow.fromSinkAndSource(Sink.ignore(),
        disconnectPair.second());
    SourceQueueWithComplete<SocketIOEvent> testDisconnectQueue = disconnectPair.first();

    Codec codec = new Codec();

    return socketIO.createBuilder()
        .onConnect((req, sid) -> {
          if (req.queryString("fail").filter("true"::equals).isPresent()) {
            throw new RuntimeException("failed");
          } else {
            return NotUsed.getInstance();
          }
        }).defaultNamespace(codec, Flow.create())
        .addNamespace(codec, (session, namespace) -> {
          if (namespace.equals("/test")) {
            return Optional.of(
                Flow.<SocketIOEvent>create()
                    .takeWhile(e -> !e.name().equals("disconnect me"))
                    .watchTermination((notUsed, future) ->
                        future.whenComplete((d, t) -> testDisconnectQueue.offer(
                            new SocketIOEvent("test disconnect",
                                CollectionConverters.asScala(
                                    Collections.<Either<JsValue, ByteString>>singletonList(Left.apply(JsString.apply(session.sid())))
                                ).toSeq(), Option.empty())
                        ))
                    )
            );
          } else {
            return Optional.empty();
          }
        }).addNamespace("/failable", codec,
            Flow.<SocketIOEvent>create().map(event -> {
              if (event.name().equals("fail me")) {
                throw new RuntimeException("you failed");
              } else {
                return event;
              }
            })
        ).addNamespace("/test-disconnect-listener", codec, testDisconnectFlow)
        .createController();
  }

  public static class Codec extends SocketIOEventCodec<SocketIOEvent, SocketIOEvent> {
    {
      addDecoder(event -> true, event -> event);
      addEncoder(event -> true, event -> event);
    }
  }
}
