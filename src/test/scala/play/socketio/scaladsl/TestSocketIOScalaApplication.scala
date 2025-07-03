/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.scaladsl

import scala.concurrent.ExecutionContext

import controllers.AssetsComponents
import controllers.ExternalAssets
import org.apache.pekko.stream.scaladsl.BroadcastHub
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.OverflowStrategy
import play.api._
import play.api.libs.json.JsString
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.engineio.EngineIOController
import play.socketio.scaladsl.SocketIOEventCodec.SocketIOEventsDecoder
import play.socketio.scaladsl.SocketIOEventCodec.SocketIOEventsEncoder
import play.socketio.SocketIOEvent
import play.socketio.TestSocketIOApplication
import play.socketio.TestSocketIOServer

object TestSocketIOScalaApplication extends TestSocketIOScalaApplication(Map.empty) {
  @annotation.varargs
  def main(args: String*): Unit = {
    TestSocketIOServer.main(this)
  }
}

class TestSocketIOScalaApplication(initialSettings: Map[String, AnyRef]) extends TestSocketIOApplication {

  def createApplication(
      routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router
  ): Application = {

    val components = createComponents(routerBuilder)

    // eager init the application before logging that we've started
    components.application

    println("Started Scala application.")

    components.application
  }

  def createComponents(
      routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router
  ): BuiltInComponents = {

    val components: BuiltInComponentsFromContext with SocketIOComponents with AssetsComponents =
      new BuiltInComponentsFromContext(
        ApplicationLoader.Context.create(
          Environment.simple(),
          initialSettings = initialSettings
        )
      ) with SocketIOComponents with AssetsComponents {

        LoggerConfigurator(environment.classLoader).foreach(_.configure(environment))
        lazy val extAssets = new ExternalAssets(environment)(executionContext, fileMimeTypes)

        override lazy val router: Router               = routerBuilder(extAssets, createController(socketIO), executionContext)
        override def httpFilters: Seq[EssentialFilter] = Nil
      }
    components
  }

  def createController(socketIO: SocketIO)(implicit mat: Materializer, ec: ExecutionContext): EngineIOController = {
    val decoder: SocketIOEventsDecoder[SocketIOEvent] = {
      case e =>
        e
    }
    val encoder: SocketIOEventsEncoder[SocketIOEvent] = {
      case e =>
        e
    }

    val (testDisconnectQueue, testDisconnectFlow) = {
      val (sourceQueue, source) =
        Source.queue[SocketIOEvent](10, OverflowStrategy.backpressure).toMat(BroadcastHub.sink)(Keep.both).run
      (sourceQueue, Flow.fromSinkAndSource(Sink.ignore, source))
    }

    socketIO.builder
      .onConnect { (request, sid) =>
        if (request.getQueryString("fail").contains("true")) {
          sys.error("failed")
        } else {
          ()
        }
      }
      .defaultNamespace(decoder, encoder, Flow[SocketIOEvent])
      .addNamespace(decoder, encoder) {
        case (session, "/test") =>
          Flow[SocketIOEvent].takeWhile(_.name != "disconnect me").watchTermination() { (_, terminated) =>
            terminated.onComplete { _ =>
              testDisconnectQueue.offer(SocketIOEvent("test disconnect", Seq(Left(JsString(session.sid))), None))
            }
          }
      }
      .addNamespace(decoder, encoder) {
        case (_, "/failable") =>
          Flow[SocketIOEvent].map { event =>
            if (event.name == "fail me") {
              throw new RuntimeException("you failed")
            }
            event
          }
      }
      .addNamespace("/test-disconnect-listener", decoder, encoder, testDisconnectFlow)
      .createController()

  }
}
