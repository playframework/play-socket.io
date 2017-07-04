package play.engineio

import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetterSuppression, Props, Status}
import akka.pattern.pipe
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import akka.{Done, NotUsed}
import play.api.libs.json.{JsString, JsValue, Json}
import play.api.mvc.RequestHeader
import play.engineio.protocol.EngineIOTransport.Polling
import play.engineio.protocol._

import scala.concurrent.Future
import scala.concurrent.duration.Deadline
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object SocketIOSessionActor {
  case class ConnectionRefused(e: Throwable)
  case class MaybeDisconnect(namespace: String, error: Option[Throwable]) extends DeadLetterSuppression
  case class PulledPackets(packets: Seq[EngineIOPacket])
  case class SendAck(namespace: Option[String], args: Seq[Either[JsValue, ByteString]], id: Long)
  case object Tick extends DeadLetterSuppression
  case object PacketsPushed

  def props[SessionData](config: EngineIOConfig,
    connectCallback: (RequestHeader, String) => Future[SessionData],
    errorHandler: PartialFunction[Throwable, JsValue],
    defaultNamespaceCallback: SocketIOSession[SessionData] => Flow[SocketIOEvent, SocketIOEvent, _],
    connectToNamespaceCallback: PartialFunction[(SocketIOSession[SessionData], String), Flow[SocketIOEvent, SocketIOEvent, _]]
  )(implicit mat: Materializer) = Props {

    new SocketIOSessionActor[SessionData](config, connectCallback, errorHandler, defaultNamespaceCallback,
      connectToNamespaceCallback)
  }
}

class SocketIOSessionActor[SessionData](
  config: EngineIOConfig,
  connectCallback: (RequestHeader, String) => Future[SessionData],
  errorHandler: PartialFunction[Throwable, JsValue],
  defaultNamespaceCallback: SocketIOSession[SessionData] => Flow[SocketIOEvent, SocketIOEvent, _],
  connectToNamespaceCallback: PartialFunction[(SocketIOSession[SessionData], String), Flow[SocketIOEvent, SocketIOEvent, _]]
)(implicit mat: Materializer) extends Actor with ActorLogging {

  private case class Connected(session: SocketIOSession[SessionData])

  import context.dispatcher

  private val sid = context.self.path.name

  // The current transport. Pings must be responded to on the same transport, while all other
  // messages should only go to the requester for the current transport.
  private var activeTransport: EngineIOTransport = _

  // The namespaces that we're currently connected to. Closing the kill switch will ensure that
  // the namespace is removed from our broadcast and merge, and will also ensure complete/cancel
  // signals are sent to the namespace flow.
  private var activeNamespaces = Map.empty[String, KillSwitch]

  // A buffer of packets to send. There are two sources of things that may add to this, either
  // a poll on the sink queue, or certain messages can be added directly, such as acks and
  // namespace disconnects. If this is non empty, packetRequester must be None.
  private var packetsToSend = Seq.empty[EngineIOPacket]
  // The current connection requesting packets. This is set when a batch of packets is requested.
  // There can only be one active packet requester. If this is Some, then packetsToSend must be
  // empty.
  private var retrieveRequesters = Map.empty[EngineIOTransport, RetrieveRequester]

  // Whether we are currently pulling packets from the sink queue or not. Must be true if
  // packetsToSend is empty.
  private var currentlyPullingPackets: Boolean = false
  // Since ping/pongs must be by transport (since probing is done using ping/pongs), if there's
  // no active requester for that transport, we need to store the pong.
  private var packetsToSendByNonActiveTransport = Map.empty[EngineIOTransport, Seq[EngineIOPacket]]

  // A buffer of received packets from the client, waiting to be sent to the source queue.
  private var packetsReceived = Seq.empty[NamespacedEventOrDisconnect]
  // The senders waiting for their packets to be sent to the source queue.
  private var packetsReceivedSenders = Set.empty[ActorRef]
  // Whether we are currently pushing packets to the source code. If this is false, packetsReceived
  // and packetsReceivedSenders must be empty.
  private var currentlyPushingPackets: Boolean = false

  // The session information
  private var session: SocketIOSession[SessionData] = _

  // The source queue. We push messages in batches as we have them, but only one batch at a time,
  // waiting for acknowledgement before pushing the next.
  private var sourceQueue: SourceQueueWithComplete[Seq[NamespacedEventOrDisconnect]] = _
  // The broadcast source, this broadcasts the events to all namespaces, where they will then
  // filter only the events for themselves.
  private var broadcastSource: Source[NamespacedEventOrDisconnect, NotUsed] = _
  // A merge sink, that merges the incoming events from all namespaces into one flow.
  private var mergeSink: Sink[NamespacedSocketIOEvent, NotUsed] = _
  // The sink queue. We retrieve messages in batches, and continue retrieving up to a buffer.
  private var sinkQueue: SinkQueueWithCancel[Seq[EngineIOPacket]] = _

  // Counter for generating ACK ids.
  private var ackIdCounter = 0l
  // The ack functions that we've sent to the client. Each function is identified by a unique id
  // to the session, so when the client sends the ack, it includes that id, we can then load the
  // function and invoke it. There is also a deadline, since the client may choose not to ack,
  // or the ack may get lost, so we periodically clear out ack functions that haven't been invoked
  // that are overdue, so as to avoid leaking memory.
  private var ackFunctions: Map[Long, (Deadline, SocketIOEventAck)] = Map.empty

  // Socket IO binary packets are encoded to multiple underlying engine IO packets. This is used
  // to track that there is a current binary packet, and how many parts it has.
  private var currentReceivingBinaryPacket: Option[(SocketIOPacket, Int)] = None
  // The in progress parts for the current binary packet.
  private var currentBinaryPacketParts = IndexedSeq.empty[ByteString]

  // A deadline by which if no communication is received from the client, we terminate the session.
  private var sessionTimeout = config.pingTimeout.fromNow

  // This works around a race condition in the engine.io client that is described in detail here:
  // https://github.com/googollee/go-engine.io/issues/33
  // When set to true, then whenever we get a retrieve from a polling transport and we don't have
  // packets for it, we tell it to go away.
  private var upgradingFromPollingHack = false

  import EngineIOManagerActor._
  import SocketIOSessionActor._

  private val sessionTick = context.system.scheduler.schedule(config.pingInterval, config.pingInterval, self, Tick)

  override def postStop() = {
    retrieveRequesters.foreach {
      case (transport, (RetrieveRequester(requester, requestId))) =>
        requester ! Close(sid, transport, requestId)
    }

    if (sourceQueue != null) {
      sourceQueue.complete()
    }
    if (sinkQueue != null) {
      sinkQueue.cancel()
    }
    sessionTick.cancel()

    log.debug("{} - Session terminated", sid)
  }

  override def receive = notYetConnected

  private def notYetConnected: Receive = {
    case Tick =>
      tick()

    case Connect(_, transport, request, requestId) =>
      debug(requestId, transport, "Received connect attempt for new session")
      activeTransport = transport
      tryConnect(sid, request)

    case Close(_, transport, requestId) =>
      debug(requestId, transport, "Requested to close session")
      context.stop(self)
      sender ! Done

    case message: SessionMessage =>
      debug(message.requestId, message.transport, s"Unknown session id")

      sender ! Packets(sid, message.transport,
        Seq(SocketIOErrorPacket.encode(None, errorHandler(SocketIOSessionException.UnknownSessionId(sid)))),
        message.requestId, lastPacket = true
      )
      context.stop(self)
  }

  def tryConnect(sid: String, request: RequestHeader) = {
    try {
      connectCallback(request, sid).map(data => Connected(SocketIOSession(sid, data)))
        .recover {
          case e => ConnectionRefused(e)
        }
        .pipeTo(self)(sender)
    } catch {
      case NonFatal(e) => self.tell(ConnectionRefused(e), sender)
    }
    context.become(connecting)
  }

  private def connecting: Receive = {
    case Tick =>
      tick()

    case Connected(session) =>
      log.debug("{} - Connection successful", sid)
      sender ! EngineIOPacket(EngineIOPacketType.Open,
        EngineIOOpenMessage(sid, config.transports.filterNot(_ == activeTransport), pingInterval = config.pingInterval,
          pingTimeout = config.pingTimeout))

      doConnect(session)

    case ConnectionRefused(e) =>
      sender ! SocketIOErrorPacket.encode(None, errorHandler(e))

    case Close(_, transport, requestId) =>
      debug(requestId, transport, "Requested to close session")
      context.stop(self)
      sender ! Done
  }

  private def tick(): Unit = {
    // If the session has timed out (ie, we haven't received anything from the client)
    // stop.
    if (sessionTimeout.isOverdue()) {
      log.debug("{} - Shutting down session due to timeout", sid)
      context.stop(self)
    } else {
      ackFunctions = ackFunctions.collect {
        case item @ (_, (deadline, _)) if deadline.hasTimeLeft() => item
      }
    }
  }

  private def resetTimeout(): Unit = {
    sessionTimeout = config.pingTimeout.fromNow
  }

  private def doConnect(session: SocketIOSession[SessionData]): Unit = {

    this.session = session

    // Set up inputs and outputs
    val (sourceQ, bSource) = Source.queue[Seq[NamespacedEventOrDisconnect]](1, OverflowStrategy.backpressure)
      .expand(_.iterator)
      .toMat(BroadcastHub.sink[NamespacedEventOrDisconnect])(Keep.both).run


    // Merge all namespaces into an output sink queue
    val (mSink, sinkQ) = MergeHub.source[NamespacedSocketIOEvent].mapConcat { event =>

      // If there's an ack function, store it
      val id = event.event.ack.map { ack =>
        ackIdCounter += 1
        val deadline = config.socketIOConfig.ackDeadline.fromNow
        ackFunctions += (ackIdCounter -> (deadline, ack))
        ackIdCounter
      }

      // Convert to event packet or binary packet
      val packet = if (event.event.arguments.forall(_.isLeft)) {
        SocketIOEventPacket(optNamespace(event.namespace), JsString(event.event.name) +: event.event.arguments.map(_.left.get), id)
      } else {
        SocketIOBinaryEventPacket(optNamespace(event.namespace), Left(JsString(event.event.name)) +: event.event.arguments, id)
      }

      SocketIOPacket.encode(packet)

    }.batch(4, Seq(_))(_ :+ _).toMat(Sink.queue[Seq[EngineIOPacket]]())(Keep.both).run

    sourceQueue = sourceQ
    broadcastSource = bSource
    mergeSink = mSink
    sinkQueue = sinkQ

    requestMorePackets()

    // Automatically connect to the / namespace
    connectNamespace("/")

    context.become(connected)
  }

  private def connected: Receive = {
    case Tick =>
      tick()

    case MaybeDisconnect(namespace, error) =>
      activeNamespaces.get(namespace) match {
        case Some(_) =>
          error.foreach(sendError(namespace, _))
          sendDisconnect(namespace)
          activeNamespaces -= namespace
        case None =>
          // Ignore
      }

    case Retrieve(_, transport, requestId) =>

      // First, if there's already a requester, then replace it
      retrieveRequesters.get(transport) match {

        case Some(RetrieveRequester(existing, existingRequestId)) =>
          debug(requestId, transport, "Retrieve with existing requester: {}, telling request to go away", existingRequestId)
          existing ! Close(sid, transport, requestId)
          storeRequester(sender, requestId, transport)

        case None if transport != activeTransport =>
          packetsToSendByNonActiveTransport.get(transport) match {
            case Some(packets) =>
              debug(requestId, transport, "Sending message to non active transport")
              sender ! Packets(sid, transport, packets, requestId)
            case None =>
              debug(requestId, transport, "Retrieve from non active transport, waiting")
              storeRequester(sender, requestId, transport)
          }

        case None if packetsToSend.isEmpty =>
          debug(requestId, transport, "Retrieve while no packets, waiting")
          storeRequester(sender, requestId, transport)

        case None =>
          // We have packets to send
          debug(requestId, transport, "Retrieve with {} packets to send", packetsToSend.length)
          sender ! Packets(sid, transport, packetsToSend, requestId)
          packetsToSend = Nil
          requestMorePackets()
      }

    case PulledPackets(packets) =>
      log.debug("{} - Pulled {} packets", sid, packets.size)

      currentlyPullingPackets = false

      retrieveRequesters.get(activeTransport) match {
        case Some(RetrieveRequester(requester, requestId)) =>
          debug(requestId, activeTransport, "Sending {} packets", packets.size)
          requester ! Packets(sid, activeTransport, packets, requestId)
          retrieveRequesters -= activeTransport
          requestMorePackets()
        case None =>
          packetsToSend ++= packets
          if (packetsToSend.size < 8) {
            requestMorePackets()
          }
      }

    case Packets(_, transport, packets, requestId, _) =>
      debug(requestId, transport, "Received {} incoming packets", packets.size)
      resetTimeout()

      handleIncomingEngineIOPackets(transport, packets, requestId)

    case PacketsPushed =>
      log.debug("{} - Pushed packet buffer", sid)
      currentlyPushingPackets = false

      if (packetsReceived.nonEmpty) {
        sourceQueue.offer(packetsReceived).map(_ => PacketsPushed) pipeTo self
        currentlyPushingPackets = true
        packetsReceivedSenders.foreach(_ ! Done)
        packetsReceivedSenders = Set.empty
        packetsReceived = Nil
      }

    case SendAck(namespace, args, id) =>
      log.debug("{} - Sending ack {}", sid, id)
      if (args.forall(_.isLeft)) {
        sendSocketIOPacket(SocketIOAckPacket(namespace, args.map(_.left.get), id))
      } else {
        sendSocketIOPacket(SocketIOBinaryAckPacket(namespace, args, id))
      }

    case Close(_, transport, requestId) =>
      if (transport == activeTransport) {
        debug(requestId, transport, "Requested to close session")
        context.stop(self)
      }

  }

  private def handleIncomingEngineIOPackets(transport: EngineIOTransport, packets: Seq[EngineIOPacket], requestId: String) = {
    var eventsToPush = Seq.empty[NamespacedEventOrDisconnect]

    packets.foreach {
      case BinaryEngineIOPacket(EngineIOPacketType.Message, bytes) =>
        eventsToPush ++= handleBinaryPacket(transport, requestId, bytes)

      case Utf8EngineIOPacket(EngineIOPacketType.Message, text) =>
        cleanUpCurrentBinary()

        val (socketIOPacket, remaining) = SocketIOPacket.decode(text)
        if (remaining > 0) {
          currentReceivingBinaryPacket = Some((socketIOPacket, remaining))
        } else {
          eventsToPush ++= handleIncomingSocketIOPacket(socketIOPacket)
        }

      case Utf8EngineIOPacket(EngineIOPacketType.Close, _) =>
        context.stop(self)

      case Utf8EngineIOPacket(EngineIOPacketType.Upgrade, _) =>
        handleUpgrade(transport, requestId)

      case Utf8EngineIOPacket(EngineIOPacketType.Noop, _) =>
        // Noop, ignore.

      case Utf8EngineIOPacket(EngineIOPacketType.Ping, data) =>
        handlePing(transport, requestId, data)

      case unexpected =>
        // Shouldn't happen
        sendError("/", SocketIOSessionException.UnexpectedPacketException(s"Unexpected ${unexpected.packetEncodingName} packet with type ${unexpected.typeId}"))
    }

    if (eventsToPush.nonEmpty) {
      if (currentlyPushingPackets) {
        packetsReceived ++= eventsToPush
        packetsReceivedSenders += sender
      } else {
        sourceQueue.offer(eventsToPush).map(_ => PacketsPushed) pipeTo self
        currentlyPushingPackets = true
        sender ! Done
      }
    } else {
      sender ! Done
    }
  }

  private def handlePing(transport: EngineIOTransport, requestId: String, data: String) = {
    if (data == "probe" && activeTransport == Polling) {
      upgradingFromPollingHack = true
      retrieveRequesters.get(Polling).foreach { requester =>
        debug(requestId, transport, "Telling {}@{} to go away to work around engine.io upgrade race condition",
          requester.requestId, Polling)
        requester.requester ! Packets(sid, Polling, Seq(Utf8EngineIOPacket(EngineIOPacketType.Noop, "")), requestId)
        retrieveRequesters -= Polling
      }
    }
    sendPackets(Seq(Utf8EngineIOPacket(EngineIOPacketType.Pong, data)), transport)
  }

  private def handleUpgrade(transport: EngineIOTransport, requestId: String) = {
    upgradingFromPollingHack = false
    debug(requestId, transport, "Upgrading from {}", activeTransport)
    activeTransport = transport
    retrieveRequesters.foreach {
      case (thisTransport, RetrieveRequester(requester, retrieveRequestId)) if thisTransport == activeTransport =>
        if (packetsToSend.nonEmpty) {
          requester ! Packets(sid, transport, packetsToSend, retrieveRequestId)
          packetsToSend = Nil
          retrieveRequesters -= transport
        }
      case (requesterTransport, RetrieveRequester(requester, requesterRequestId)) =>
        requester ! Close(sid, requesterTransport, requesterRequestId)
        retrieveRequesters -= transport
    }
  }

  private def handleBinaryPacket(transport: EngineIOTransport, requestId: String, bytes: ByteString): Seq[NamespacedEventOrDisconnect] = {
    currentReceivingBinaryPacket match {
      case Some((packet, parts)) =>
        currentBinaryPacketParts :+= bytes
        if (parts == currentBinaryPacketParts.size) {

          currentBinaryPacketParts = IndexedSeq.empty
          currentReceivingBinaryPacket = None

          handleIncomingSocketIOPacket(packet).toSeq
        } else {
          Nil
        }

      case None =>
        sendError("/", SocketIOSessionException.UnexpectedPacketException(
          "Received a binary engine.io packet, but not currently receiving a binary socket.io packet."))
        Nil
    }
  }

  private def handleIncomingSocketIOPacket(packet: SocketIOPacket): Option[NamespacedEventOrDisconnect] = {
    packet match {

      case SocketIOConnectPacket(namespace) =>
        connectNamespace(namespace.getOrElse("/"))
        None

      case SocketIODisconnectPacket(namespace) =>
        validateNamespace(namespace) {
          val ns = namespace.getOrElse("/")
          activeNamespaces -= ns
          DisconnectNamespace(ns)
        }

      case SocketIOEventPacket(namespace, data, maybeId) =>
        validateNamespace(namespace) {
          val eventName = (for {
            firstArg <- data.headOption
            asString <- firstArg.asOpt[String]
          } yield asString).getOrElse("")

          val ack = maybeId.map(id => new ActorSocketIOEventAck(self, namespace, id))

          NamespacedSocketIOEvent(namespace.getOrElse("/"),
            SocketIOEvent(eventName, data.drop(1).map(Left.apply), ack))
        }

      case SocketIOAckPacket(namespace, data, id) =>
        ackFunctions.get(id) match {
          case Some((_, ackFunction)) =>
            ackFunction(data.map(Left.apply))
            ackFunctions -= id
          case None =>
            sendError(namespace.getOrElse("/"), SocketIOSessionException.UnknownAckId(id))
        }
        None

      case SocketIOErrorPacket(namespace, error) =>
        // todo Allow end user to handle, possibly stop the actor?
        log.warning(s"Received error from client on namespace $namespace: ${Json.stringify(error)}")
        None

      case SocketIOBinaryEventPacket(namespace, data, maybeId) =>
        validateNamespace(namespace) {
          val completeData = SocketIOPacket.replacePlaceholders(data, currentBinaryPacketParts)

          val eventName = (for {
            firstArg <- completeData.headOption
            asJson <- firstArg.left.toOption
            asString <- asJson.asOpt[String]
          } yield asString).getOrElse("")

          val ack = maybeId.map(id => new ActorSocketIOEventAck(self, namespace, id))

          NamespacedSocketIOEvent(namespace.getOrElse("/"),
            SocketIOEvent(eventName, completeData.drop(1), ack))
        }

      case SocketIOBinaryAckPacket(namespace, data, id) =>
        ackFunctions.get(id) match {
          case Some((_, ackFunction)) =>
            val completeData = SocketIOPacket.replacePlaceholders(data, currentBinaryPacketParts)
            ackFunction(completeData)
            ackFunctions -= id
          case None =>
            sendError(namespace.getOrElse("/"), SocketIOSessionException.UnknownAckId(id))
        }
        None
    }
  }

  private def validateNamespace[T](namespace: Option[String])(block: => T): Option[T] = {
    val ns = namespace.getOrElse("/")
    if (activeNamespaces.contains(ns)) {
      Some(block)
    } else {
      sendError(ns, SocketIOSessionException.NamespaceNotConnected(ns))
      None
    }
  }

  private def cleanUpCurrentBinary() = {
    if (currentReceivingBinaryPacket.isDefined) {
      sendError("/", SocketIOSessionException.UnexpectedPacketException(
        "Received non binary engine.io packet while in the middle of receiving a binary socket.io packet"))
      currentReceivingBinaryPacket = None
      currentBinaryPacketParts = IndexedSeq.empty
    }
  }

  /**
    * Connect to a namespace.
    */
  private def connectNamespace(namespace: String) = {
    activeNamespaces.get(namespace) match {
      case None =>

        try {
          val namespaceFlow = if (namespace == "/") {
            defaultNamespaceCallback(session)
          } else {
            connectToNamespaceCallback.applyOrElse((session, namespace), { _: (SocketIOSession[SessionData], String) =>
              throw SocketIOSessionException.NamespaceNotFound(namespace)
            })
          }

          val (terminationFlow, killSwitch) = namespaceDisconnectHandler(namespace)
          broadcastSource via
            handleNamespaceEventOrDisconnect(namespace) via
            terminationFlow via
            namespaceFlow via
            terminationFlow map { event =>
            NamespacedSocketIOEvent(namespace, event)
          } runWith mergeSink

          activeNamespaces += (namespace -> killSwitch)
          sendSocketIOPacket(SocketIOConnectPacket(optNamespace(namespace)))
        } catch {
          case NonFatal(e) =>
            sendError(namespace, e)
        }

      case Some(_) =>
        sendError(namespace, SocketIOSessionException.NamespaceAlreadyConnected(namespace))
    }
  }

  private def handleNamespaceEventOrDisconnect(namespace: String): Flow[NamespacedEventOrDisconnect, SocketIOEvent, _] = {
    Flow.fromGraph(new GraphStage[FlowShape[NamespacedEventOrDisconnect, SocketIOEvent]] {
      val in = Inlet[NamespacedEventOrDisconnect]("MultiplexedStreamNamespacedStream")
      val out = Outlet[SocketIOEvent]("StreamForSingleNamespace")
      override def shape = FlowShape(in, out)
      override def createLogic(inheritedAttributes: Attributes) = {
        new GraphStageLogic(shape) {
          setHandler(in, new InHandler {
            override def onPush() = {
              grab(in) match {
                case DisconnectNamespace(ns) if ns == namespace =>
                  completeStage()
                case NamespacedSocketIOEvent(ns, event) if ns == namespace =>
                  push(out, event)
                case _ =>
                  pull(in)
              }
            }
          })
          setHandler(out, new OutHandler {
            override def onPull() = {
              pull(in)
            }
          })
        }
      }
    })
  }

  private def requestMorePackets() = {
    if (!currentlyPullingPackets) {
      sinkQueue.pull().map {
        case Some(packets) => PulledPackets(packets)
        case None => PulledPackets(Nil)
      } pipeTo self
      currentlyPullingPackets = true
    }
  }

  /**
    * The idea of this is to correctly namespace disconnection. It may be disconnected because the client requested it,
    * in which case the namespace will no longer be in the active namespaces and so we don't send a disconnect to the
    * client, or we may encounter a termination, error or cancel from the namespace flow, in which case we need to
    * tell the client to disconnect and potentially send the error.
    */
  private def namespaceDisconnectHandler(namespace: String): (Flow[SocketIOEvent, SocketIOEvent, _], KillSwitch) = {
    val killSwitch = KillSwitches.shared(namespace)

    // We shutdown the kill switch because the kill switch is inserted in two places, before the user supplied flow,
    // and after it, and that flow may not carry errors/cancellations through, so we have to ensure the other side
    // is killed.
    val flow = Flow[SocketIOEvent].via(killSwitch.flow).watchTermination() { (m, future) =>
      future.onComplete {
        case Success(_) =>
          killSwitch.shutdown()
          self ! MaybeDisconnect(namespace, None)
        case Failure(e) =>
          killSwitch.shutdown()
          self ! MaybeDisconnect(namespace, Some(e))
      }
      m
    }

    (flow, killSwitch)
  }

  private def sendDisconnect(namespace: String) = {
    SocketIODisconnectPacket(optNamespace(namespace))
  }

  private def sendError(namespace: String, exception: Throwable) = {
    sendSocketIOPacket(SocketIOErrorPacket(optNamespace(namespace), errorHandler(exception)))
  }

  private def sendSocketIOPacket(packet: SocketIOPacket) = {
    sendPackets(SocketIOPacket.encode(packet))
  }

  private def sendPackets(packets: Seq[EngineIOPacket], transport: EngineIOTransport = activeTransport) = {
    retrieveRequesters.get(transport) match {
      case Some(RetrieveRequester(requester, requestId)) =>
        debug(requestId, transport, "Sending {} packets direct", packets.size)
        requester ! Packets(sid, transport, packets, requestId)
        retrieveRequesters -= transport
      case None if transport == activeTransport =>
        packetsToSend ++= packets
      case None =>
        packetsToSendByNonActiveTransport += (transport -> packets)
    }
  }

  private def optNamespace(namespace: String): Option[String] =
    Some(namespace).filterNot(_ == "/")

  private def storeRequester(requester: ActorRef, requestId: String, transport: EngineIOTransport) = {
    if (upgradingFromPollingHack && transport == Polling) {
      // Don't store, tell it to go away
      debug(requestId, transport, "Telling poller to go away to work around engine.io upgrade race condition")
      requester ! Packets(sid, Polling, Seq(Utf8EngineIOPacket(EngineIOPacketType.Noop, "")), requestId)
    } else {
      retrieveRequesters += (transport -> RetrieveRequester(sender, requestId))
    }
  }

  private def debug(requestId: String, transport: EngineIOTransport, message: String, args: Any*): Unit = {
    if (log.isDebugEnabled) {
      log.debug(log.format(s"$sid : $requestId@$transport - $message", args: _*))
    }
  }
}

private sealed trait NamespacedEventOrDisconnect
private case class NamespacedSocketIOEvent(namespace: String, event: SocketIOEvent) extends NamespacedEventOrDisconnect
private case class DisconnectNamespace(namespace: String) extends NamespacedEventOrDisconnect

private class ActorSocketIOEventAck(sessionActor: ActorRef, namespace: Option[String], id: Long) extends SocketIOEventAck {
  override def apply(args: Seq[Either[JsValue, ByteString]]) = sessionActor ! SocketIOSessionActor.SendAck(namespace, args, id)
}

private case class RetrieveRequester(requester: ActorRef, requestId: String)

/**
  * Base class for all exceptions related to the socket.io session.
  */
abstract class SocketIOSessionException(message: String, cause: Throwable) extends RuntimeException(message, cause) {
  def this(message: String) = this(message, null)
}
object SocketIOSessionException {
  case class UnknownSessionId(sid: String) extends SocketIOSessionException("Unknown session id: " + sid)
  case class NamespaceNotFound(namespace: String) extends SocketIOSessionException("Namespace not found: " + namespace)
  case class NamespaceAlreadyConnected(namespace: String) extends SocketIOSessionException("Namespace already connected: " + namespace)
  case class NamespaceNotConnected(namespace: String) extends SocketIOSessionException("Namespace not connected: " + namespace)
  case class UnknownAckId(id: Long) extends SocketIOSessionException("Unknown ack id: " + id)
  case class UnexpectedPacketException(message: String) extends SocketIOSessionException(message)
}
