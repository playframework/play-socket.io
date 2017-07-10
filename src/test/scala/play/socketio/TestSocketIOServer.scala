package play.socketio

import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source}
import play.api.{ApplicationLoader, BuiltInComponentsFromContext, Environment, LoggerConfigurator}
import play.core.server.{AkkaHttpServer, ServerConfig}
import play.engineio._
import com.softwaremill.macwire._
import controllers.{AssetsComponents, ExternalAssets}
import play.api.libs.json.JsString
import play.api.mvc.EssentialAction
import play.api.routing.sird._
import play.api.routing.Router
import play.socketio.SocketIOEventCodec.{SocketIOEventsDecoder, SocketIOEventsEncoder}

import scala.concurrent.ExecutionContext

/**
  * Test server that can be
  */
object TestSocketIOServer {

  def start(config: ServerConfig = ServerConfig()): AkkaHttpServer = {
    AkkaHttpServer.fromApplication(
      new BuiltInComponentsFromContext(ApplicationLoader.createContext(Environment.simple()))
        with SocketIOComponents
        with AssetsComponents {

        LoggerConfigurator(environment.classLoader).foreach(_.configure(environment))

        lazy val testEngine = wire[TestEngine]
        lazy val controller: EngineIOController = testEngine.controller
        lazy val extAssets: (String => EssentialAction) =
          new ExternalAssets(environment)(executionContext, fileMimeTypes).at("src/test/javascript", _)

        override lazy val router = Router.from {
          case GET(p"/socket.io/") ? q"transport=$transport" => controller.endpoint(transport)
          case POST(p"/socket.io/") ? q"transport=$transport" => controller.endpoint(transport)
          case GET(p"$path*") => EssentialAction { rh =>
            (if (path.endsWith("/")) {
              extAssets(path + "index.html")
            } else {
              extAssets(path)
            }).apply(rh).map(_.withHeaders("Cache-Control" -> "no-cache"))
          }
        }

        override def httpFilters = Nil
      }.application, config
    )
  }

  def main(args: Array[String]) = {
    val server = start()
    System.in.read()
    server.stop()
  }

  class TestEngine(socketIO: SocketIO)(implicit materializer: Materializer, ec: ExecutionContext) {

    val decoder: SocketIOEventsDecoder[SocketIOEvent] = { case e => e }
    val encoder: SocketIOEventsEncoder[SocketIOEvent] = { case e => e }

    val (testDisconnectQueue, testDisconnectFlow) = {
      val (sourceQueue, source) = Source.queue[SocketIOEvent](10, OverflowStrategy.backpressure).toMat(BroadcastHub.sink)(Keep.both).run
      (sourceQueue, Flow.fromSinkAndSource(Sink.ignore, source))
    }

    val controller = socketIO.builder
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
