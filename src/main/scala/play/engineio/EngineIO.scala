/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.engineio

import java.util.UUID
import javax.inject.{ Inject, Provider, Singleton }

import akka.NotUsed
import akka.pattern.ask
import akka.actor.{ ActorRef, ActorSystem }
import akka.routing.FromConfig
import akka.stream._
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.util.Timeout
import play.api.{ Configuration, Environment, Logger }
import play.api.http.HttpErrorHandler
import play.api.inject.Module
import play.api.mvc._
import play.engineio.EngineIOManagerActor._
import play.engineio.protocol._
import play.socketio.scaladsl.SocketIO

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

case class EngineIOConfig(
  pingInterval: FiniteDuration         = 25.seconds,
  pingTimeout:  FiniteDuration         = 60.seconds,
  transports:   Seq[EngineIOTransport] = Seq(EngineIOTransport.WebSocket, EngineIOTransport.Polling),
  actorName:    String                 = "engine.io",
  routerName:   Option[String]         = None,
  useRole:      Option[String]         = None
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
      useRole = config.get[Option[String]]("use-role")
    )
  }
}

@Singleton
class EngineIOConfigProvider @Inject() (configuration: Configuration) extends Provider[EngineIOConfig] {
  override lazy val get: EngineIOConfig = EngineIOConfig.fromConfiguration(configuration)
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
 * {{{
 * GET     /socket.io/        play.engineio.EngineIOController.endpoint(transport)
 * POST    /socket.io/        play.engineio.EngineIOController.endpoint(transport)
 * }}}
 */
final class EngineIOController(config: EngineIOConfig, httpErrorHandler: HttpErrorHandler, controllerComponents: ControllerComponents,
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
      case EngineIOTransport.Polling   => pollingEndpoint
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
        log.debug(s"Received push request for $sid")

        (engineIOManager ? Packets(sid, transport, payload.packets, requestId)).map { _ =>
          Ok("ok")
        }

      // sid no payload, we're retrieving packets
      case (Some(sid), None) =>
        log.debug(s"Received poll request for $sid")

        (engineIOManager ? Retrieve(sid, transport, requestId)).map {
          case Close(_, _, _)            => Ok(EngineIOPacket(EngineIOPacketType.Close))
          case Packets(_, _, Nil, _)     => Ok(EngineIOPacket(EngineIOPacketType.Noop))
          case Packets(_, _, packets, _) => Ok(EngineIOPayload(packets))
        }

      // No sid, we're creating a new session
      case (None, _) =>
        val sid = UUID.randomUUID().toString

        log.debug(s"Received new connection for $sid")

        (engineIOManager ? Connect(sid, transport, request, requestId)).mapTo[Packets].map { packets =>
          Ok(EngineIOPayload(packets.packets))
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
        (engineIOManager ? Connect(sid, transport, request, requestId)).mapTo[Packets].map { packets =>
          if (packets.packets.headOption.exists(_.typeId == EngineIOPacketType.Open)) {
            Right(webSocketFlow(sid, requestId).prepend(Source.fromIterator(() => packets.packets.iterator)))
          } else {
            Right(Flow.fromSinkAndSource(Sink.ignore, Source.fromIterator(() => packets.packets.iterator)))
          }
        }

      case Some(sid) =>
        Future.successful(Right(webSocketFlow(sid, requestId)))
    }
  }

  private def webSocketFlow(sid: String, requestId: String): Flow[EngineIOPacket, EngineIOPacket, _] = {
    val transport = EngineIOTransport.WebSocket

    log.debug(s"Received WebSocket request for $sid")

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
    } takeWhile (!_.isInstanceOf[Close]) mapConcat {
      case Packets(_, _, packets, _) => packets.to[immutable.Seq]
    }

    Flow.fromSinkAndSourceCoupled(in, out)
  }

}

/**
 * The engine.io system. Allows you to create engine.io controllers for handling engine.io connections.
 */
@Singleton
final class EngineIO @Inject() (config: EngineIOConfig, httpErrorHandler: HttpErrorHandler, controllerComponents: ControllerComponents,
                                actorSystem: ActorSystem)(implicit ec: ExecutionContext, mat: Materializer) {

  private val log = Logger(classOf[EngineIO])

  /**
   * Build the engine.io controller.
   */
  def createController(handler: EngineIOSessionHandler): EngineIOController = {
    def startManager(): ActorRef = {
      val sessionProps = EngineIOSessionActor.props(config, handler)
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
}

/**
 * Provides engine.io components
 *
 * Mix this trait into your application cake to get an instance of [[EngineIO]] to build your engine.io engine with.
 */
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

/**
 * The engine.io module.
 *
 * Provides engine.io components to Play's runtime dependency injection implementation.
 */
class EngineIOModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[EngineIOConfig].toProvider[EngineIOConfigProvider],
    bind[EngineIO].toSelf
  )
}