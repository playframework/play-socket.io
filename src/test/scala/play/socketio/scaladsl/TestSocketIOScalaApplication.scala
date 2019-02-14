/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */

package play.socketio.scaladsl

import akka.stream.{ Materializer, OverflowStrategy }
import akka.stream.scaladsl.{ BroadcastHub, Flow, Keep, Sink, Source }
import controllers.{ AssetsComponents, ExternalAssets }
import play.api._
import play.api.ApplicationLoader.Context
import play.api.libs.json.JsString
import play.api.routing.Router
import play.engineio.EngineIOController
import play.socketio.scaladsl.SocketIOEventCodec.{ SocketIOEventsDecoder, SocketIOEventsEncoder }
import play.socketio.{ SocketIOEvent, TestSocketIOApplication, TestSocketIOServer }

import scala.concurrent.ExecutionContext

object TestSocketIOScalaApplication extends TestSocketIOScalaApplication(Map.empty) {
  @annotation.varargs
  def main(args: String*) = {
    TestSocketIOServer.main(this)
  }
}

class TestSocketIOScalaApplication(initialSettings: Map[String, AnyRef]) extends TestSocketIOApplication {

  def createApplication(routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router): Application = {

    val components = createComponents(routerBuilder)

    // eager init the application before logging that we've started
    components.application

    println("Started Scala application.")

    components.application
  }

  def createComponents(routerBuilder: (ExternalAssets, EngineIOController, ExecutionContext) => Router): BuiltInComponents = {

    val components = new BuiltInComponentsFromContext(Context.create(
      Environment.simple(),
      initialSettings = initialSettings)) with SocketIOComponents with AssetsComponents {

      LoggerConfigurator(environment.classLoader).foreach(_.configure(environment))
      lazy val extAssets = new ExternalAssets(environment)(executionContext, fileMimeTypes)

      override lazy val router = routerBuilder(extAssets, createController(socketIO), executionContext)
      override def httpFilters = Nil
    }
    components
  }

  def createController(socketIO: SocketIO)(implicit mat: Materializer, ec: ExecutionContext) = {
    val decoder: SocketIOEventsDecoder[SocketIOEvent] = {
      case e => e
    }
    val encoder: SocketIOEventsEncoder[SocketIOEvent] = {
      case e => e
    }

    val (testDisconnectQueue, testDisconnectFlow) = {
      val (sourceQueue, source) = Source.queue[SocketIOEvent](10, OverflowStrategy.backpressure).toMat(BroadcastHub.sink)(Keep.both).run
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
        case (session, "/test") => Flow[SocketIOEvent].takeWhile(_.name != "disconnect me").watchTermination() { (_, terminated) =>
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
