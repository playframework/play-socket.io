package socketio

import akka.NotUsed
import akka.pattern.{AskTimeoutException, ask}
import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.Timeout
import play.api.Logger
import play.api.http.HttpErrorHandler
import play.api.libs.json.Json
import play.api.mvc._
import socketio.EngineIOManagerActor.{Connect, GoAway, Packets, Retrieve}
import socketio.protocol._

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

case class EngineIOConfig(
  pingInterval: FiniteDuration = 25.seconds,
  pingTimeout: FiniteDuration = 60.seconds,
  ackDeadline: FiniteDuration = 60.seconds,
  requestTimeout: FiniteDuration = 10.seconds,
  autoCreate: Boolean = false
)

class EngineIO(config: EngineIOConfig, httpErrorHandler: HttpErrorHandler, controllerComponents: ControllerComponents,
  actorSystem: ActorSystem, engineIOManager: ActorRef)(implicit ec: ExecutionContext) extends AbstractController(controllerComponents) {

  private val log = Logger(classOf[EngineIO])

  private implicit val timeout = Timeout(config.requestTimeout)

  def endpoint(transport: String): Handler = {
    EngineIOTransport.fromName(transport) match {
      case EngineIOTransport.Polling => pollingEndpoint
      case EngineIOTransport.WebSocket => webSocketEndpoint
    }
  }

  private def pollingEndpoint = Action.async(EngineIOPayload.parser(parse)) { implicit request =>
    val maybeSid = request.getQueryString("sid")
    val requestId = request.getQueryString("t").getOrElse("<none>")
    val transport = EngineIOTransport.Polling

    (maybeSid, request.body) match {
      // sid and payload, we're posting packets
      case (Some(sid), Some(payload)) =>
        (engineIOManager ? Packets(sid, transport, payload.packets, requestId)).map { _ =>
          Ok("ok").withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN -> "*")
        }

      // sid no payload, we're retrieving packets
      case (Some(sid), None) =>
        (engineIOManager ? Retrieve(sid, transport, requestId)).recover {
          case e: AskTimeoutException => Packets(sid, transport, Seq(EngineIOPacket(EngineIOPacketType.Noop)))
        }.map {
          case GoAway => Ok(EngineIOPacket(EngineIOPacketType.Noop)).withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN -> "*")
          case Packets(_, _, packets, _) => Ok(EngineIOPayload(packets)).withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN -> "*")
        }

      // No sid, no packets, we're creating a new session
      case (None, None) =>
        (engineIOManager ? Connect(transport, request)).mapTo[EngineIOPacket].map { packet =>
          Ok(packet).withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN -> "*")
        }

    }
  }

  private def webSocketEndpoint = WebSocket.acceptOrResult { request =>
    val maybeSid = request.getQueryString("sid")
    val requestId = request.getQueryString("t").getOrElse("<none>")
    val transport = EngineIOTransport.WebSocket

    maybeSid match {

      case None =>
        // No sid, first we have to create a session, then we can start the flow, sending the open packet
        // as the first message.
        (engineIOManager ? Connect(transport, request)).mapTo[Utf8EngineIOPacket].map { openPacket =>
          // Decode the packet to find the sid
          val sid = Json.parse(openPacket.text).as[EngineIOOpenMessage].sid
          Right(webSocketFlow(sid, requestId).prepend(Source.single(openPacket)))
        }

      case Some(sid) =>
          Future.successful(Right(webSocketFlow(sid, requestId)))
    }
  }

  private def webSocketFlow(sid: String, requestId: String): Flow[EngineIOPacket, EngineIOPacket, _] = {
    val transport = EngineIOTransport.WebSocket

    val in = Flow[EngineIOPacket].batch(4, Vector(_))(_ :+ _).mapAsync(1) { packets =>
      engineIOManager ? Packets(sid, transport, packets, requestId)
    }.to(Sink.ignore)

    val out = Source.repeat(NotUsed).mapAsync(1) { _ =>
      engineIOManager ? Retrieve(sid, transport, requestId)
    } recover {
      case e: AskTimeoutException => Packets(sid, transport, Nil)
    } via completeWhen(_ == GoAway) mapConcat {
        case Packets(_, _, packets, _) => packets.to[immutable.Seq]
    }

    Flow.fromSinkAndSourceCoupled(in, out)
  }

  private def clientError(message: String): Handler = {
    Action.async { request =>
      httpErrorHandler.onClientError(request, BAD_REQUEST, message)
    }
  }

  private def completeWhen[T](predicate: T => Boolean): Flow[T, T, NotUsed] = Flow.fromGraph(new GraphStage[FlowShape[T, T]] {
    val in = Inlet[T]("CompleteWhen.in")
    val out = Outlet[T]("CompleteWhen.out")
    override def shape = FlowShape(in, out)
    override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush() = {
          grab(in) match {
            case complete if predicate(complete) =>
              completeStage()
            case other =>
              push(out, other)
          }
        }
      })
      setHandler(out, new OutHandler {
        override def onPull() = pull(in)
      })
    }
  })
}

class EngineIOFactory(config: EngineIOConfig, httpErrorHandler: HttpErrorHandler, controllerComponents: ControllerComponents,
  actorSystem: ActorSystem)(implicit ec: ExecutionContext, mat: Materializer) {

  def apply[S](name: String, onConnect: (RequestHeader, String) => Future[Option[SocketIOSession[S]]])
    (defaultNamespace: SocketIOSession[S] => Flow[SocketIOEvent, SocketIOEvent, _])
    (connectToNamespace: (SocketIOSession[S], String) => Option[Flow[SocketIOEvent, SocketIOEvent, _]]): EngineIO = {

    val sessionProps = SocketIOSessionActor.props(config, onConnect, defaultNamespace, connectToNamespace)

    val managerProps = EngineIOManagerActor.props(config, sessionProps)

    val manager = actorSystem.actorOf(managerProps, name)

    new EngineIO(config, httpErrorHandler, controllerComponents, actorSystem, manager)
  }

}

object EngineIO {

  def namespace[In, Out](decoder: SocketIOEventDecoder[In], encoder: SocketIOEventEncoder[Out])(flow: Flow[In, Out, _]): Flow[SocketIOEvent, SocketIOEvent, _] = {
    Flow[SocketIOEvent] map decoder.decode via flow map encoder.encode
  }
}

