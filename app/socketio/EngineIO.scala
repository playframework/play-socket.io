package socketio

import akka.NotUsed
import akka.pattern.{AskTimeoutException, ask}
import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
import akka.stream.scaladsl.{Flow, Sink, Source}
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
import scala.util.{Failure, Success}

case class EngineIOConfig(
  pingInterval: FiniteDuration = 25.seconds,
  pingTimeout: FiniteDuration = 60.seconds,
  transports: Seq[EngineIOTransport] = Seq(EngineIOTransport.WebSocket, EngineIOTransport.Polling),
  ackDeadline: FiniteDuration = 60.seconds,
  retrieveRequestIdleResponse: FiniteDuration = 8.seconds,
  receiveRequestTimeout: FiniteDuration = 10.seconds,
  autoCreate: Boolean = false
)

class EngineIO(config: EngineIOConfig, httpErrorHandler: HttpErrorHandler, controllerComponents: ControllerComponents,
  actorSystem: ActorSystem, engineIOManager: ActorRef)(implicit ec: ExecutionContext) extends AbstractController(controllerComponents) {

  private val log = Logger(classOf[EngineIO])

  private implicit val timeout = Timeout(config.receiveRequestTimeout)

  def endpoint(transport: String): Handler = {
    EngineIOTransport.fromName(transport) match {
      case EngineIOTransport.Polling => pollingEndpoint
      case EngineIOTransport.WebSocket => webSocketEndpoint
    }
  }

  private def pollingEndpoint = Action.async(EngineIOPayload.parser(parse)) { implicit request =>
    val maybeSid = request.getQueryString("sid")
    val requestId = request.getQueryString("t").getOrElse(request.id.toString)
    val transport = EngineIOTransport.Polling

    (maybeSid, request.body) match {
      // sid and payload, we're posting packets
      case (Some(sid), Some(payload)) =>
        (engineIOManager ? Packets(sid, transport, payload.packets, requestId)).map { _ =>
          Ok("ok")
        }

      // sid no payload, we're retrieving packets
      case (Some(sid), None) =>
        (engineIOManager ? Retrieve(sid, transport, requestId)).recover {
          case e: AskTimeoutException => Packets(sid, transport, Seq(EngineIOPacket(EngineIOPacketType.Noop)))
        }.map {
          case GoAway | Packets(_, _, Nil, _) => Ok(EngineIOPacket(EngineIOPacketType.Noop))
          case Packets(_, _, packets, _) => Ok(EngineIOPayload(packets))
        }

      // No sid, no packets, we're creating a new session
      case (None, None) =>
        (engineIOManager ? Connect(transport, request)).mapTo[EngineIOPacket].map { packet =>
          Ok(packet)
        }

    }
  }

  private def webSocketEndpoint = WebSocket.acceptOrResult { request =>
    val maybeSid = request.getQueryString("sid")
    val requestId = request.getQueryString("t").getOrElse(request.id.toString)
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
    }.to(Sink.ignore.mapMaterializedValue(_.onComplete {
      case Success(_) =>
        log.debug(s"$sid : $requestId@$transport - Incoming stream terminated")
      case Failure(e) =>
        log.warn(s"$sid : $requestId@$transport - Incoming stream failed", e)
    }))

    val out = (Source.repeat(NotUsed).mapAsync(1) { _ =>
      (engineIOManager ? Retrieve(sid, transport, requestId)).recover {
        case e: AskTimeoutException => Packets(sid, transport, Nil)
      }
    } map { message =>
      message
    } takeWhile(_ != GoAway) mapConcat {
        case Packets(_, _, packets, _) => packets.to[immutable.Seq]
    }).watchTermination() { (m1, f) =>
      f.onComplete {
        case Success(_) =>
          log.debug(s"$sid : $requestId@$transport - Outgoing stream terminated")
        case Failure(e) =>
          log.warn(s"$sid : $requestId@$transport - Outgoing stream failed", e)
      }
      m1
    }

    Flow.fromSinkAndSourceCoupled(in, out)
  }

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

