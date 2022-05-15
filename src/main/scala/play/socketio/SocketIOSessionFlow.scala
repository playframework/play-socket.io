/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.ByteString
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import play.engineio._
import play.socketio.protocol._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Deadline
import scala.util.control.NonFatal

/**
 * Utility to handle engine.io sessions as socket.io sessions.
 */
object SocketIOSessionFlow {

  /**
   * Create an engine.io session handler handles the engine.io session as a socket.io session.
   *
   * @param config The socket.io configuration.
   * @param connectCallback The connect callback for the socket.io session.
   * @param errorHandler An error handler.
   * @param defaultNamespaceCallback The callback for creating the default namespace.
   * @param connectToNamespaceCallback Factory for all other namespaces.
   */
  def createEngineIOSessionHandler[SessionData](
      config: SocketIOConfig,
      connectCallback: (RequestHeader, String) => Future[SessionData],
      errorHandler: PartialFunction[Throwable, JsValue],
      defaultNamespaceCallback: SocketIOSession[SessionData] => Flow[SocketIOEvent, SocketIOEvent, _],
      connectToNamespaceCallback: PartialFunction[
        (SocketIOSession[SessionData], String),
        Flow[SocketIOEvent, SocketIOEvent, _]
      ]
  )(implicit ec: ExecutionContext, mat: Materializer): EngineIOSessionHandler = {

    new EngineIOSessionHandler {
      override def onConnect(request: RequestHeader, sid: String) = {
        connectCallback(request, sid).map { sessionData =>
          val session = SocketIOSession(sid, sessionData)

          val stage = new SocketIOSessionStage[SessionData](
            config,
            session,
            errorHandler,
            defaultNamespaceCallback,
            connectToNamespaceCallback
          )

          BidiFlow
            .fromGraph(stage)
            .joinMat(
              Flow.fromSinkAndSourceMat(
                BroadcastHub.sink[NamespacedSocketIOMessage],
                MergeHub.source[NamespacedSocketIOMessage]
              )(Keep.both)
            ) {
              case (receiver, (broadcastSource, mergeSink)) =>
                val demuxMuxer = new NamespaceDemuxMuxer(broadcastSource, mergeSink)
                receiver.provideNamespaceDemuxMuxer(demuxMuxer)
                NotUsed
            }
        }
      }
    }
  }
}

/**
 * Base class for all exceptions related to the socket.io session.
 */
abstract class SocketIOSessionException(message: String, cause: Throwable)
    extends RuntimeException(message, cause, true, false) {
  def this(message: String) = this(message, null)
}
object SocketIOSessionException {
  private def ns(namespace: Option[String]) = namespace.getOrElse("/")

  case class NamespaceNotFound(namespace: Option[String])
      extends SocketIOSessionException("Namespace not found: " + ns(namespace))
  case class NamespaceAlreadyConnected(namespace: Option[String])
      extends SocketIOSessionException("Namespace already connected: " + ns(namespace))
  case class NamespaceNotConnected(namespace: Option[String])
      extends SocketIOSessionException("Namespace not connected: " + ns(namespace))
  case class UnknownAckId(id: Long)                     extends SocketIOSessionException("Unknown ack id: " + id)
  case class UnexpectedPacketException(message: String) extends SocketIOSessionException(message)
  case class ErrorFromClient(error: JsValue)
      extends SocketIOSessionException("Error from client: " + Json.stringify(error))
}

/**
 * Handles the connection to the EngineIO flow, adapting it to a flow of namespaced socket IO messages.
 */
private class SocketIOSessionStage[SessionData](
    config: SocketIOConfig,
    session: SocketIOSession[SessionData],
    errorHandler: PartialFunction[Throwable, JsValue],
    defaultNamespaceCallback: SocketIOSession[SessionData] => Flow[SocketIOEvent, SocketIOEvent, _],
    connectToNamespaceCallback: PartialFunction[
      (SocketIOSession[SessionData], String),
      Flow[SocketIOEvent, SocketIOEvent, _]
    ]
) extends GraphStageWithMaterializedValue[
      BidiShape[EngineIOMessage, NamespacedSocketIOMessage, NamespacedSocketIOMessage, Seq[EngineIOMessage]],
      DemuxMuxReceiver
    ] {

  val engineIOIn  = Inlet[EngineIOMessage]("EngineIOIn")
  val socketIOOut = Outlet[NamespacedSocketIOMessage]("SocketIOOut")
  val socketIOIn  = Inlet[NamespacedSocketIOMessage]("SocketIOIn")
  val engineIOOut = Outlet[Seq[EngineIOMessage]]("EngineIOOut")

  override def shape = BidiShape(engineIOIn, socketIOOut, socketIOIn, engineIOOut)
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val logic = new GraphStageLogic(shape) with DemuxMuxReceiver {

      var demuxMuxer: NamespaceDemuxMuxer = _

      def provideNamespaceDemuxMuxer(dm: NamespaceDemuxMuxer): Unit = {
        demuxMuxer = dm
      }

      val engineIOHandler = new EngineIOHandler
      setHandlers(engineIOIn, engineIOOut, engineIOHandler)
      val socketIOHandler = new SocketIOHandler
      setHandlers(socketIOIn, socketIOOut, socketIOHandler)

      override def preStart() = {
        // Check that we have a demuxMuxer
        if (demuxMuxer == null) {
          throw new IllegalStateException("Flow started before the NamespaceDemuxMuxer was provided")
        }

        // And connect to the default namespace
        connectNamespace(None, defaultNamespaceCallback(session))
      }

      import SocketIOSessionException._

      // Callback used to push messages directly to engine.io, used for acks
      val packetCallback = getAsyncCallback[SocketIOPacket](pushSocketIOPacket)

      // Buffer for outgoing engine.io messages. Needed because we are merging two sources,
      // plus acks can come in at any time and need to be added to the buffer.
      var engineIOBuffer = Seq.empty[EngineIOMessage]

      // Binary packet tracking, since a binary socket.io packet gets fragmented across many
      // engine.io packets
      var currentBinaryPacket: Option[SocketIOPacket] = None
      var currentBinaryPacketFragments                = IndexedSeq.empty[ByteString]
      var currentBinaryPacketRemaining                = 0

      // The active namespaces
      var activeNamespaces = Set.empty[Option[String]]

      // Counter for generating ACK ids.
      private var ackIdCounter = 0L
      // The ack functions that we've sent to the client. Each function is identified by a unique id
      // to the session, so when the client sends the ack, it includes that id, we can then load the
      // function and invoke it. There is also a deadline, since the client may choose not to ack,
      // or the ack may get lost, so we periodically clear out ack functions that haven't been invoked
      // that are overdue, so as to avoid leaking memory.
      private var ackFunctions: Map[Long, (Deadline, SocketIOEventAck)] = Map.empty

      class EngineIOHandler extends InHandler with OutHandler {
        override def onPush() = {
          (grab(engineIOIn), currentBinaryPacket) match {

            // handle unexpected binary/text messages first
            case (_: TextEngineIOMessage, Some(_)) =>
              pushError(
                None,
                UnexpectedPacketException(
                  "Received a text engine.io message while waiting for a binary message to complete a socket.io binary packet"
                )
              )

            case (_: BinaryEngineIOMessage, None) =>
              pushError(
                None,
                UnexpectedPacketException("Received unexpected binary engine.io message")
              )

            case (TextEngineIOMessage(text), None) =>
              try {
                val (packet, remaining) = SocketIOPacket.decode(text)
                if (remaining > 0) {
                  currentBinaryPacket = Some(packet)
                  currentBinaryPacketRemaining = remaining
                  pull(engineIOIn)
                } else {
                  handleSocketIOPacket(packet)
                }
              } catch {
                case e: SocketIOEncodingException =>
                  pushError(None, e)
              }

            case (BinaryEngineIOMessage(bytes), Some(binaryPacket)) =>
              currentBinaryPacketFragments :+= bytes
              currentBinaryPacketRemaining -= 1
              if (currentBinaryPacketRemaining == 0) {
                binaryPacket match {
                  case SocketIOBinaryEventPacket(namespace, data, id) =>
                    handleSocketIOPacket(
                      SocketIOBinaryEventPacket(
                        namespace,
                        SocketIOPacket.replacePlaceholders(data, currentBinaryPacketFragments),
                        id
                      )
                    )
                  case SocketIOBinaryAckPacket(namespace, data, id) =>
                    handleSocketIOPacket(
                      SocketIOBinaryAckPacket(
                        namespace,
                        SocketIOPacket.replacePlaceholders(data, currentBinaryPacketFragments),
                        id
                      )
                    )
                  case _ =>
                    pushError(
                      None,
                      UnexpectedPacketException(
                        "Received unexpected non binary fragmented socket.io packet with binary fragments"
                      )
                    )
                }
                currentBinaryPacketFragments = IndexedSeq.empty
                currentBinaryPacket = None
              } else {
                pull(engineIOIn)
              }
          }
        }

        override def onPull() = {
          if (engineIOBuffer.nonEmpty) {
            push(engineIOOut, engineIOBuffer)
            engineIOBuffer = Nil
            if (isClosed(socketIOIn)) {
              complete(engineIOOut)
            }
          } else {
            if (isAvailable(socketIOOut)) {
              maybePull(engineIOIn)
            }
            maybePull(socketIOIn)
          }
        }

        override def onUpstreamFinish() = {
          complete(socketIOOut)
          cancel(socketIOIn)
          if (engineIOBuffer.isEmpty) {
            complete(engineIOOut)
          }
        }

        override def onUpstreamFailure(ex: Throwable) = {
          fail(socketIOOut, ex)
          cancel(socketIOIn)
        }
      }

      class SocketIOHandler extends InHandler with OutHandler {
        override def onPush() = {
          grab(socketIOIn) match {

            case DisconnectSocketIONamespace(namespace, error) =>
              if (activeNamespaces(namespace)) {
                error.foreach(pushError(namespace, _))
                pushSocketIOPacket(SocketIODisconnectPacket(namespace))
              } else {
                // Ignore, the client doesn't want to hear about it
                pull(socketIOIn)
              }

            case NamespacedSocketIOEvent(namespace, event) =>
              val maybeAckId = event.ack.map { ackFunction =>
                val ackId = ackIdCounter
                ackFunctions += (ackIdCounter -> (config.ackDeadline.fromNow, ackFunction))
                ackIdCounter += 1
                if (ackIdCounter % config.ackCleanupEvery == 0) {
                  cleanupAcks()
                }
                ackId
              }

              val packet = if (event.arguments.forall(_.isLeft)) {
                val data = JsString(event.name) +: event.arguments.collect {
                  case Left(arg) => arg
                }
                SocketIOEventPacket(namespace, data, maybeAckId)
              } else {
                SocketIOBinaryEventPacket(namespace, Left(JsString(event.name)) +: event.arguments, maybeAckId)
              }
              pushSocketIOPacket(packet)
          }
        }

        override def onPull() = {
          if (isAvailable(engineIOOut)) {
            pull(engineIOIn)
          }
        }

        override def onDownstreamFinish() = {
          // Cancel the ins so we can't get any more events
          cancel(engineIOIn)
          cancel(socketIOIn)
        }
      }

      def handleSocketIOPacket(packet: SocketIOPacket): Unit = packet match {

        case packet @ SocketIOConnectPacket(nsWithQuery) =>
          val namespace = nsWithQuery.map(_.takeWhile(_ != '?'))

          if (activeNamespaces(namespace)) {
            pushError(namespace, NamespaceAlreadyConnected(namespace))
          } else {
            try {
              nsWithQuery match {
                case None =>
                  connectNamespace(namespace, defaultNamespaceCallback(session))
                case Some(ns) =>
                  connectNamespace(
                    namespace,
                    connectToNamespaceCallback.applyOrElse(
                      (session, ns), { _: (SocketIOSession[SessionData], String) => throw NamespaceNotFound(namespace) }
                    )
                  )
              }
            } catch {
              case NonFatal(e) =>
                pushError(namespace, e)
            }
          }

        case SocketIODisconnectPacket(namespace) =>
          validateNamespace(namespace) {
            push(socketIOOut, DisconnectSocketIONamespace(namespace, None))
            // The socket.io client forgets about a namespace as soon as it sends a disconnect,
            // so there's no point in us waiting around to receive the disconnect message out of
            // the namespace before we remove it
            activeNamespaces -= namespace
          }

        case SocketIOEventPacket(namespace, data, maybeAckId) =>
          validateNamespace(namespace) {
            val eventName = (for {
              firstArg <- data.headOption
              asString <- firstArg.asOpt[String]
            } yield asString).getOrElse("")

            val ack = maybeAckId.map(ackId => new FlowCallbackAck(packetCallback.invoke, namespace, ackId))

            push(
              socketIOOut,
              NamespacedSocketIOEvent(namespace, SocketIOEvent(eventName, data.drop(1).map(Left.apply), ack))
            )
          }

        case SocketIOAckPacket(namespace, args, ackId) =>
          validateNamespace(namespace) {
            ackFunctions.get(ackId) match {
              case Some((_, ackFunction)) =>
                ackFunction(args.map(Left.apply))
                ackFunctions -= ackId
                pull(engineIOIn)
              case None =>
                pushError(namespace, UnknownAckId(ackId))
            }
          }

        case SocketIOErrorPacket(namespace, error) =>
          push(socketIOOut, DisconnectSocketIONamespace(namespace, Some(ErrorFromClient(error))))

        case SocketIOBinaryEventPacket(namespace, data, maybeAckId) =>
          validateNamespace(namespace) {
            val eventName = (for {
              firstArg <- data.headOption
              asJson   <- firstArg.left.toOption
              asString <- asJson.asOpt[String]
            } yield asString).getOrElse("")

            val ack = maybeAckId.map(ackId => new FlowCallbackAck(packetCallback.invoke, namespace, ackId))

            push(socketIOOut, NamespacedSocketIOEvent(namespace, SocketIOEvent(eventName, data.drop(1), ack)))
          }

        case SocketIOBinaryAckPacket(namespace, args, ackId) =>
          validateNamespace(namespace) {
            ackFunctions.get(ackId) match {
              case Some((_, ackFunction)) =>
                ackFunction(args)
                ackFunctions -= ackId
                pull(engineIOIn)
              case None =>
                pushError(namespace, UnknownAckId(ackId))
            }
          }
      }

      def validateNamespace(namespace: Option[String])(block: => Unit) = {
        if (!activeNamespaces(namespace)) {
          pushError(namespace, NamespaceNotConnected(namespace))
        } else {
          block
        }
      }

      def connectNamespace(namespace: Option[String], flow: Flow[SocketIOEvent, SocketIOEvent, _]) = {
        activeNamespaces += namespace
        demuxMuxer.addNamespace(namespace, flow)
        pushSocketIOPacket(SocketIOConnectPacket(namespace))
      }

      def maybePull(in: Inlet[_]) = {
        if (!hasBeenPulled(in)) {
          pull(in)
        }
      }

      def cleanupAcks(): Unit = {
        ackFunctions = ackFunctions.filterNot {
          case (_, (deadline, _)) => deadline.isOverdue()
        }
      }

      def pushError(namespace: Option[String], error: Throwable) = {
        pushSocketIOPacket(SocketIOErrorPacket(namespace, errorHandler(error)))
      }

      def pushSocketIOPacket(packet: SocketIOPacket) = {
        engineIOBuffer ++= SocketIOPacket.encode(packet)
        if (isAvailable(engineIOOut)) {
          push(engineIOOut, engineIOBuffer)
          engineIOBuffer = Nil
        }
      }

    }

    (logic, logic)
  }
}

/**
 * This handles adding namespaces into the demux/mux flow.
 */
private class NamespaceDemuxMuxer(
    broadcastSource: Source[NamespacedSocketIOMessage, NotUsed],
    mergeSink: Sink[NamespacedSocketIOMessage, NotUsed]
)(implicit materializer: Materializer) {

  def addNamespace(namespace: Option[String], flow: Flow[SocketIOEvent, SocketIOEvent, _]): Unit = {
    broadcastSource
    // Demux to only handle events for this namespace
      .filter(_.namespace == namespace)
      // Take until we get a disconnect message, either terminating or propagating errors to th eflow
      .takeWhile {
        case _: NamespacedSocketIOEvent => true
        case DisconnectSocketIONamespace(_, Some(error)) =>
          throw error
        case _ => false
      }
      // Remove the namespace
      .collect {
        case NamespacedSocketIOEvent(_, event) => event
      }
      // And send through the namespace flow
      .via(flow)
      // Namespace the outgoing events
      .map(NamespacedSocketIOEvent(namespace, _))
      // Append a disconnect message to the output
      .concat(Source.single(DisconnectSocketIONamespace(namespace, None)))
      // And if there's an error, translate it to a disconnect message
      .recover {
        case e => DisconnectSocketIONamespace(namespace, Some(e))
      }
      // And feed it into the muxer
      .runWith(mergeSink)
  }
}

private trait DemuxMuxReceiver {
  def provideNamespaceDemuxMuxer(namespaceDemuxMuxer: NamespaceDemuxMuxer): Unit
}

/**
 * A namespaced socket.io message.
 *
 * Either an event, or a disconnect.
 */
private sealed trait NamespacedSocketIOMessage {
  def namespace: Option[String]
}
private case class NamespacedSocketIOEvent(namespace: Option[String], event: SocketIOEvent)
    extends NamespacedSocketIOMessage
private case class DisconnectSocketIONamespace(namespace: Option[String], error: Option[Throwable])
    extends NamespacedSocketIOMessage

private class FlowCallbackAck(callback: SocketIOPacket => Unit, namespace: Option[String], id: Long)
    extends SocketIOEventAck {
  override def apply(args: Seq[Either[JsValue, ByteString]]): Unit = {
    if (args.forall(_.isLeft)) {
      callback(SocketIOAckPacket(namespace, args.collect {
        case Left(jsValue) => jsValue
      }, id))
    } else {
      callback(SocketIOBinaryAckPacket(namespace, args, id))
    }
  }
}
