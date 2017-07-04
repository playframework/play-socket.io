package play.engineio

import java.util.UUID

import akka.NotUsed
import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.routing.FromConfig
import akka.stream._
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import play.api.{Configuration, Logger}
import play.api.http.HttpErrorHandler
import play.api.libs.json.{JsString, JsValue}
import play.api.mvc.{RequestHeader, _}
import play.engineio.EngineIOManagerActor._
import play.engineio.protocol._

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class EngineIOConfig(
  pingInterval: FiniteDuration = 25.seconds,
  pingTimeout: FiniteDuration = 60.seconds,
  transports: Seq[EngineIOTransport] = Seq(EngineIOTransport.WebSocket, EngineIOTransport.Polling),
  actorName: String = "engine.io",
  routerName: Option[String] = None,
  useRole: Option[String] = None,
  socketIOConfig: SocketIOConfig = SocketIOConfig()
)

object EngineIOConfig {
  def fromConfiguration(configuration: Configuration) = {
    val config = configuration.get[Configuration]("play.engine-io")
    EngineIOConfig(
      pingInterval = config.get[FiniteDuration]("ping-interval"),
      pingTimeout = config.get[FiniteDuration]("ping-timeout"),
      transports = config.get[Seq[String]]("transports").map(EngineIOTransport.fromName),
      actorName = config.get[String]("actor-name"),
      routerName = config.get[Option[String]]("router-name"),
      useRole = config.get[Option[String]]("use-role"),
      socketIOConfig = SocketIOConfig.fromConfiguration(config)
    )
  }
}

case class SocketIOConfig(
  ackDeadline: FiniteDuration = 60.seconds
)

object SocketIOConfig {
  def fromConfiguration(configuration: Configuration) = {
    val config = configuration.get[Configuration]("socket-io")
    SocketIOConfig(
      ackDeadline = config.get[FiniteDuration]("ack-deadline")
    )
  }
}

/**
  * An engine.io controller.
  *
  * This provides one handler, the [[endpoint()]] method. This should be routed to for all `GET` and `POST` requests for
  * anything on the path for engine.io (for socket.io, this defaults to `/socket.io/` unless configured otherwise on
  * the client.
  *
  * The `transport` parameter should be extracted from the `transport` query parameter with the request.
  *
  * For example:
  *
  * ```
  * GET     /socket.io/        play.engineio.EngineIOController.endpoint(transport)
  * POST    /socket.io/        play.engineio.EngineIOController.endpoint(transport)
  * ```
  */
class EngineIOController(config: EngineIOConfig, httpErrorHandler: HttpErrorHandler, controllerComponents: ControllerComponents,
  actorSystem: ActorSystem, engineIOManager: ActorRef)(implicit ec: ExecutionContext) extends AbstractController(controllerComponents) {

  private val log = Logger(classOf[EngineIOController])
  private implicit val timeout = Timeout(config.pingTimeout)

  /**
    * The endpoint to route to from a router.
    *
    * @param transport The transport to use.
    */
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
        (engineIOManager ? Retrieve(sid, transport, requestId)).map {
          case Close(_, _, _) => Ok(EngineIOPacket(EngineIOPacketType.Close))
          case Packets(_, _, Nil, _, _) => Ok(EngineIOPacket(EngineIOPacketType.Noop))
          case Packets(_, _, packets, _, _) => Ok(EngineIOPayload(packets))
        }

      // No sid, we're creating a new session
      case (None, _) =>
        val sid = UUID.randomUUID().toString
        (engineIOManager ? Connect(sid, transport, request, requestId)).mapTo[EngineIOPacket].map { packet =>
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
        val sid = UUID.randomUUID().toString
        (engineIOManager ? Connect(sid, transport, request, requestId)).mapTo[Utf8EngineIOPacket].map { openPacket =>
          if (openPacket.typeId == EngineIOPacketType.Open) {
            Right(webSocketFlow(sid, requestId).prepend(Source.single(openPacket)))
          } else {
            Right(Flow.fromSinkAndSource(Sink.ignore, Source.single(openPacket)))
          }
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
      case Success(s) =>
        engineIOManager ! Close(sid, transport, requestId)
      case Failure(t) =>
        log.warn("Error on incoming WebSocket", t)
    }))

    val out = Source.repeat(NotUsed).mapAsync(1) { _ =>
      val asked = engineIOManager ? Retrieve(sid, transport, requestId)
      asked.onComplete {
        case Success(s) =>
        case Failure(t) =>
          log.warn("Error on outgoing WebSocket", t)
      }
      asked
    } takeWhile(!_.isInstanceOf[Close]) takeWhile({
      case Packets(_, _, _, _, lastPacket) => !lastPacket
    }, inclusive = true) mapConcat {
      case Packets(_, _, packets, _, _) => packets.to[immutable.Seq]
    }

    Flow.fromSinkAndSourceCoupled(in, out)
  }

}

/**
  * The engine.io system. Allows you to create engine.io controllers for handling engine.io connections.
  */
final class EngineIO(config: EngineIOConfig, httpErrorHandler: HttpErrorHandler, controllerComponents: ControllerComponents,
  actorSystem: ActorSystem)(implicit ec: ExecutionContext, mat: Materializer) {

  private val log = Logger(classOf[EngineIO])

  /**
    * Create a builder.
    *
    * The builder will by default:
    *   - Accept all sessions
    *   - Send the message of each exception the client as a JSON string
    *   - Use a flow that ignores all incoming messages and produces no outgoing messages for the default namespace
    *   - Provide no other namespaces
    */
  def builder: EngineIOBuilder[Any] = {
    new EngineIOBuilder[Any](
      (_, _) => Future.successful(NotUsed),
      {
        case e if e.getMessage != null => JsString(e.getMessage)
        case e => JsString(e.getClass.getName)
      },
      _ => Flow.fromSinkAndSource(Sink.ignore, Source.maybe),
      PartialFunction.empty
    )
  }

  /**
    * A builder for engine.io instances.
    */
  class EngineIOBuilder[SessionData] private[engineio] (
    connectCallback: (RequestHeader, String) => Future[SessionData],
    errorHandler: PartialFunction[Throwable, JsValue],
    defaultNamespaceCallback: SocketIOSession[SessionData] => Flow[SocketIOEvent, SocketIOEvent, _],
    connectToNamespaceCallback: PartialFunction[(SocketIOSession[SessionData], String), Flow[SocketIOEvent, SocketIOEvent, _]]
  ) {

    /**
      * Set the onConnect callback.
      *
      * The callback takes the request header of the incoming connection and the id of the ssion, and should produce a
      * session object, which can be anything, for example, a user principal, or other authentication and/or
      * authorization details.
      *
      * If you wish to reject the connection, you can throw an exception, which will later be handled by the error
      * handler to turn it into a message to send to the client.
      */
    def onConnect[S <: SessionData](callback: (RequestHeader, String) => S): EngineIOBuilder[S] = {
      onConnectAsync((rh, sid) => Future.successful(callback(rh, sid)))
    }

    /**
      * Set the onConnect callback.
      *
      * The callback takes the request header of the incoming connection and the id of the ssion, and should produce a
      * session object, which can be anything, for example, a user principal, or other authentication and/or
      * authorization details.
      *
      * If you wish to reject the connection, you can throw an exception, which will later be handled by the error
      * handler to turn it into a message to send to the client.
      */
    def onConnectAsync[S <: SessionData](callback: (RequestHeader, String) => Future[S]): EngineIOBuilder[S] = {
      new EngineIOBuilder[S](
        callback,
        errorHandler,
        defaultNamespaceCallback,
        connectToNamespaceCallback
      )
    }

    /**
      * Set the error handler.
      *
      * If any errors are encountered, they will be serialized to JSON this function, and then passed to the client
      * using a socket.io error message.
      *
      * Any errors not handled by this partial function will fallback to the existing error handler in this builder,
      * which by default sends the exception message as a JSON string.
      */
    def withErrorHandler(handler: PartialFunction[Throwable, JsValue]): EngineIOBuilder[SessionData] = {
      new EngineIOBuilder(
        connectCallback,
        handler.orElse(errorHandler),
        defaultNamespaceCallback,
        connectToNamespaceCallback
      )
    }

    /**
      * Set the default namespace flow.
      *
      * @param decoder the decoder to use.
      * @param encoder the encoder to use.
      * @param flow the flow.
      */
    def defaultNamespace[In, Out](decoder: SocketIOEventDecoder[In], encoder: SocketIOEventEncoder[Out], flow: Flow[In, Out, _]): EngineIOBuilder[SessionData] = {
      defaultNamespace(decoder, encoder)(_ => flow)
    }

    /**
      * Set the default namespace flow.
      *
      * This variant allows you to customise the returned flow according to the session.
      *
      * @param decoder the decoder to use.
      * @param encoder the encoder to use.
      * @param callback a callback to create the flow given the session.
      */
    def defaultNamespace[In, Out](decoder: SocketIOEventDecoder[In], encoder: SocketIOEventEncoder[Out])
      (callback: SocketIOSession[SessionData] => Flow[In, Out, _]): EngineIOBuilder[SessionData] = {
      new EngineIOBuilder(
        connectCallback,
        errorHandler,
        session => createNamespace(decoder, encoder, callback(session)),
        connectToNamespaceCallback
      )
    }

    /**
      * Add a namespace.
      *
      * @param name The name of the namespace.
      * @param decoder The decoder to use to decode messages.
      * @param encoder The encoder to use to encode messages.
      * @param flow The flow to use for the namespace.
      */
    def addNamespace[In, Out](name: String, decoder: SocketIOEventDecoder[In], encoder: SocketIOEventEncoder[Out], flow: Flow[In, Out, _]): EngineIOBuilder[SessionData] = {
      addNamespace(decoder, encoder) {
        case (_, `name`) => flow
      }
    }

    /**
      * Add a namespace.
      *
      * This variant allows you to pass a callback that pattern matches on the namespace name, and uses the session
      * data to decide whether the user should be able to connect to this namespace or not.
      *
      * Any exceptions thrown here will result in an error being sent back to the client, serialized by the
      * errorHandler. Alternatively, you can simply not return a value from the partial function, which will result in
      * an error being sent to the client that the namespace does not exist.
      *
      * @param decoder The decoder to use to decode messages.
      * @param encoder The encoder to use to encode messages.
      * @param callback A callback to match the namespace and create a flow accordingly.
      */
    def addNamespace[In, Out](decoder: SocketIOEventDecoder[In], encoder: SocketIOEventEncoder[Out])
      (callback: PartialFunction[(SocketIOSession[SessionData], String), Flow[In, Out, _]]): EngineIOBuilder[SessionData] = {

      new EngineIOBuilder(
        connectCallback,
        errorHandler,
        defaultNamespaceCallback,
        connectToNamespaceCallback.orElse(callback.andThen { flow =>
          createNamespace(decoder, encoder, flow)
        })
      )
    }

    /**
      * Build the engine.io controller.
      */
    def build: EngineIOController = {
      def startManager(): ActorRef = {
        val sessionProps = SocketIOSessionActor.props(config, connectCallback, errorHandler, defaultNamespaceCallback, connectToNamespaceCallback)
        val managerProps = EngineIOManagerActor.props(config, sessionProps)
        actorSystem.actorOf(managerProps, config.actorName)
      }

      val actorRef = config.routerName match {
        case Some(routerName) =>

          // Start the manager, if we're configured to do so
          config.useRole match {
            case Some(role) =>
              Configuration(actorSystem.settings.config).getOptional[Seq[String]]("akka.cluster.roles") match {
                case None => throw new IllegalArgumentException("akka.cluster.roles is not set, are you using Akka clustering?")
                case Some(roles) if roles.contains(role) =>
                  startManager()
                case _ =>
                  log.debug("Not starting EngineIOManagerActor because we don't have the " + role + " configured on this node")
              }
            case None =>
              startManager()
          }

          actorSystem.actorOf(FromConfig.props(), routerName)

        case None =>
          startManager()
      }

      new EngineIOController(config, httpErrorHandler, controllerComponents, actorSystem, actorRef)
    }

    private def createNamespace[In, Out](decoder: SocketIOEventDecoder[In], encoder: SocketIOEventEncoder[Out], flow: Flow[In, Out, _]): Flow[SocketIOEvent, SocketIOEvent, _] = {
      Flow[SocketIOEvent] map decoder.decode via flow map encoder.encode
    }
  }
}

trait EngineIOComponents {
  def httpErrorHandler: HttpErrorHandler
  def controllerComponents: ControllerComponents
  def actorSystem: ActorSystem
  def executionContext: ExecutionContext
  def materializer: Materializer
  def configuration: Configuration

  lazy val engineIOConfig: EngineIOConfig = EngineIOConfig.fromConfiguration(configuration)
  lazy val engineIO: EngineIO = new EngineIO(engineIOConfig, httpErrorHandler,
    controllerComponents, actorSystem)(executionContext, materializer)
}