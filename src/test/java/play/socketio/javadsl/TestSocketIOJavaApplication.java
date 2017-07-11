package play.socketio.javadsl;


import akka.NotUsed;
import akka.japi.Pair;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.*;
import com.google.inject.AbstractModule;
import controllers.ExternalAssets;
import play.api.Application;
import play.api.libs.json.JsString;
import play.api.routing.Router;
import play.engineio.EngineIOController;
import play.inject.guice.GuiceApplicationBuilder;
import play.socketio.SocketIOEvent;
import play.socketio.TestSocketIOApplication;
import play.socketio.TestSocketIOServer;
import scala.Function3;
import scala.Option;
import scala.concurrent.ExecutionContext;
import scala.util.Left;
import scala.collection.JavaConverters;

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
        .build().getWrappedApplication();

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
          if ("true".equals(req.getQueryString("fail"))) {
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
                                JavaConverters.asScalaBuffer(
                                    Collections.singletonList(Left.apply(JsString.apply(session.sid())))
                                ), Option.empty())
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
