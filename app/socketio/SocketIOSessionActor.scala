package socketio

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetterSuppression, Props, Status}
import akka.pattern.pipe
import akka.stream.scaladsl._
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import play.api.libs.json.{JsString, JsValue, Json}
import play.api.libs.typedmap.TypedMap
import play.api.mvc.{Headers, RequestHeader}
import play.api.mvc.request.{RemoteConnection, RequestTarget}
import socketio.SocketIOSessionActor._
import socketio.protocol._

import scala.concurrent.Future
import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success}

object SocketIOSessionActor {
  case object ConnectionRefused
  case class MaybeDisconnect(namespace: String, error: Option[String]) extends DeadLetterSuppression
  case class PulledPackets(packets: Seq[EngineIOPacket])
  case class SendAck(namespace: Option[String], args: Seq[Either[JsValue, ByteString]], id: Long)
  case object Tick extends DeadLetterSuppression
  case object PacketsPushed

  def props[S](config: EngineIOConfig,
    onConnect: (RequestHeader, String) => Future[Option[SocketIOSession[S]]],
    defaultNamespace: SocketIOSession[S] => Flow[SocketIOEvent, SocketIOEvent, _],
    connectToNamespace: (SocketIOSession[S], String) => Option[Flow[SocketIOEvent, SocketIOEvent, _]])
    (implicit mat: Materializer) = Props {

    new SocketIOSessionActor[S](config, onConnect, defaultNamespace, connectToNamespace)
  }
}

// todo implement absolute limit on incoming packets
class SocketIOSessionActor[S](
  config: EngineIOConfig,
  onConnect: (RequestHeader, String) => Future[Option[SocketIOSession[S]]],
  defaultNamespace: SocketIOSession[S] => Flow[SocketIOEvent, SocketIOEvent, _],
  connectToNamespace: (SocketIOSession[S], String) => Option[Flow[SocketIOEvent, SocketIOEvent, _]])
  (implicit mat: Materializer) extends Actor with ActorLogging {

  private case class Connected(session: SocketIOSession[S])

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
  private var packetRequesters = Map.empty[EngineIOTransport, (ActorRef, String)]
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
  private var session: SocketIOSession[S] = _

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

  import EngineIOManagerActor._

  private val sessionTick = context.system.scheduler.schedule(config.pingInterval, config.pingInterval, self, Tick)

  override def postStop() = {
    if (sourceQueue != null) {
      sourceQueue.complete()
    }
    if (sinkQueue != null) {
      sinkQueue.cancel()
    }
    sessionTick.cancel()
  }

  override def receive = notYetConnected

  private def notYetConnected: Receive = {
    case Tick =>
      tick()

    case Connect(transport, request) =>
      log.debug("{} - Received connect attempt for new session", sid)
      activeTransport = transport
      onConnect(request, sid).map {
        case Some(session) => Connected(session)
        case None => ConnectionRefused
      }.pipeTo(self)(sender)
      context.become(connecting)

    case _ if config.autoCreate =>
      log.debug("{} - Auto creating connection for unknown session ", sid)
      onConnect(new RequestHeader {
        override def connection = RemoteConnection("", false, None)
        override def method = "GET"
        override def version = "HTTP/1.1"
        override def attrs = TypedMap.empty
        override def headers = Headers()
        override def target = RequestTarget("/", "/", Map.empty)
      }, sid).map {
        case Some(session) => Connected(session)
        case None => ConnectionRefused
      }.pipeTo(self)(sender)
      context.become(connecting)

    case _ =>
      log.debug(s"{} - Unknown session id", sid)

      sender ! Status.Failure(new RuntimeException("Unknown session ID"))
      context.stop(self)
  }

  private def connecting: Receive = {
    case Tick =>
      tick()

    case Connected(session) =>
      log.debug("{} - Connection successful", sid)
      sender ! EngineIOPacket(EngineIOPacketType.Open,
        EngineIOOpenMessage(sid, Seq(), pingInterval = config.pingInterval,
          pingTimeout = config.pingTimeout))

      doConnect(session)

    case ConnectionRefused =>
      sender ! Status.Failure(new RuntimeException("Connection refused"))
      context.stop(self)
  }

  private def tick(): Unit = {
    // If the session has timed out (ie, we haven't received anything from the client)
    // stop.
    if (sessionTimeout.isOverdue()) {
      log.debug("{} - Shutting down session due to timeout {}", sid, sessionTimeout)
      context.stop(self)
    } else {
      ackFunctions = ackFunctions.collect {
        case item @ (_, (deadline, _)) if deadline.hasTimeLeft() => item
      }
    }
  }

  private def doConnect(session: SocketIOSession[S]): Unit = {

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
        val deadline = Deadline(config.ackDeadline)
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
    sendSocketIOPacket(SocketIOConnectPacket(None))

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

      sessionTimeout = config.pingTimeout.fromNow

      // First, if there's already a requester, then replace it
      packetRequesters.get(transport) match {
        case Some((existing, existingRequestId)) =>
          log.debug("{} : {}@{} - Retrieve with existing requester: {}, telling request to go away", sid, requestId, transport, existingRequestId)
          existing ! GoAway
          packetRequesters += (transport -> (sender, requestId))
        case None if transport != activeTransport =>
          packetsToSendByNonActiveTransport.get(transport) match {
            case Some(packets) =>
              log.debug("{} : {}@{} - Sending message to non active transport", sid, requestId, transport)
              sender ! Packets(sid, transport, packets, requestId)
            case None =>
              log.debug("{} : {}@{} - Retrieve from non active transport, waiting", sid, requestId, transport)
              packetRequesters += (transport -> (sender, requestId))
          }
        case None if packetsToSend.isEmpty =>
          log.debug("{} : {}@{} - Retrieve while no packets, waiting", sid, requestId, transport)
          packetRequesters += (transport -> (sender, requestId))
        case None =>
          // We have packets to send
          log.debug("{} : {}@{} - Retrieve with {} packets to send", sid, requestId, transport, packetsToSend.length)
          sender ! Packets(sid, transport, packetsToSend, requestId)
          packetsToSend = Nil
          requestMorePackets()
      }

    case PulledPackets(packets) =>
      log.debug("{} - Pulled {} packets", sid, packets.size)

      currentlyPullingPackets = false

      packetRequesters.get(activeTransport) match {
        case Some((requester, requestId)) =>
          log.debug("{} : {}@{} - Sending {} packets", sid, requestId, activeTransport, packets.size)
          requester ! Packets(sid, activeTransport, packets, requestId)
          packetRequesters -= activeTransport
        case None =>
          packetsToSend ++= packets
          if (packetsToSend.size < 8) {
            requestMorePackets()
          }
      }

    case Packets(_, transport, packets, requestId) =>
      log.debug("{} : {}@{} - Received {} incoming packets", sid, requestId, transport, packets.size)
      sessionTimeout = config.pingTimeout.fromNow

      handleIncomingEngineIOPackets(transport, packets)

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

  }

  private def handleIncomingEngineIOPackets(transport: EngineIOTransport, packets: Seq[EngineIOPacket]) = {
    var eventsToPush = Seq.empty[NamespacedEventOrDisconnect]

    packets.foreach {
      case BinaryEngineIOPacket(EngineIOPacketType.Message, bytes) =>
        currentReceivingBinaryPacket match {
          case Some((packet, parts)) =>
            currentBinaryPacketParts :+= bytes
            if (parts == currentBinaryPacketParts.size) {

              eventsToPush ++= handleIncomingSocketIOPacket(packet)

              currentBinaryPacketParts = IndexedSeq.empty
              currentReceivingBinaryPacket = None
            }

          case None =>
            sendError("/", "Received a binary engine.io packet, but not currently receiving a binary socket.io packet.")
        }

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
        log.debug("{} - {} Upgrading")
        activeTransport = transport
        packetRequesters.foreach {
          case (thisTransport, (requester, requestId)) if thisTransport == activeTransport =>
            if (packetsToSend.nonEmpty) {
              requester ! Packets(sid, transport, packetsToSend, requestId)
              packetsToSend = Nil
              packetRequesters -= transport
            }
          case (_, (requester, _)) =>
            requester ! GoAway
            packetRequesters -= transport

        }

      case Utf8EngineIOPacket(EngineIOPacketType.Noop, _) =>
        // Noop, ignore.

      case Utf8EngineIOPacket(EngineIOPacketType.Ping, data) =>
        sendPackets(Seq(Utf8EngineIOPacket(EngineIOPacketType.Pong, data)), transport)

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
            sendError(namespace.getOrElse("/"), "Unknown ack id: " + id)
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
            sendError(namespace.getOrElse("/"), "Unknown ack id: " + id)
        }
        None
    }
  }

  private def validateNamespace[T](namespace: Option[String])(block: => T): Option[T] = {
    val ns = namespace.getOrElse("/")
    if (activeNamespaces.contains(ns)) {
      Some(block)
    } else {
      sendError(ns, "Namespace not connected")
      None
    }
  }

  private def cleanUpCurrentBinary() = {
    if (currentReceivingBinaryPacket.isDefined) {
      sendError("/", "Received non binary engine.io packet while in the middle of receiving a binary socket.io packet")
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

        val namespaceFlow = if (namespace == "/") {
          Some(defaultNamespace(session))
        } else {
          connectToNamespace(session, namespace)
        }

        namespaceFlow match {
          case Some(flow) =>

            val (terminationFlow, killSwitch) = namespaceDisconnectHandler(namespace)
            broadcastSource via
              handleNamespaceEventOrDisconnect(namespace) via
              terminationFlow via
              flow via
              terminationFlow map { event =>
                NamespacedSocketIOEvent(namespace, event)
              } runWith mergeSink

            activeNamespaces += (namespace -> killSwitch)

          case None =>
            sendError(namespace, "No such namespace")
            sendDisconnect(namespace)
        }

      case Some(_) =>
        sendError(namespace, "Already connected to namespace")
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
      log.debug("Pulling packets for {}", sid)
      sinkQueue.pull().map {
        case Some(packets) => PulledPackets(packets)
        case None =>
          log.debug("Pull on completed stream for {}", sid)
          PulledPackets(Nil)
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
          self ! MaybeDisconnect(namespace, Some(e.getMessage))
      }
      m
    }

    (flow, killSwitch)
  }

  private def sendDisconnect(namespace: String) = {
    SocketIODisconnectPacket(optNamespace(namespace))
  }

  private def sendError(namespace: String, error: String) = {
    sendSocketIOPacket(SocketIOErrorPacket(optNamespace(namespace), JsString(error)))
  }

  private def sendSocketIOPacket(packet: SocketIOPacket) = {
    sendPackets(SocketIOPacket.encode(packet))
  }

  private def sendPackets(packets: Seq[EngineIOPacket], transport: EngineIOTransport = activeTransport) = {
    packetRequesters.get(transport) match {
      case Some((actor, requestId)) =>
        log.debug("{} : {}@{} - Sending {} packets direct", sid, requestId, transport, packets.size)
        actor ! Packets(sid, transport, packets)
        packetRequesters -= transport
      case None if transport == activeTransport =>
        packetsToSend ++= packets
      case None =>
        packetsToSendByNonActiveTransport += (transport -> packets)
    }
  }

  private def optNamespace(namespace: String): Option[String] =
    Some(namespace).filterNot(_ == "/")

}

private sealed trait NamespacedEventOrDisconnect
private case class NamespacedSocketIOEvent(namespace: String, event: SocketIOEvent) extends NamespacedEventOrDisconnect
private case class DisconnectNamespace(namespace: String) extends NamespacedEventOrDisconnect

private class ActorSocketIOEventAck(sessionActor: ActorRef, namespace: Option[String], id: Long) extends SocketIOEventAck {
  override def apply(args: Seq[Either[JsValue, ByteString]]) = sessionActor ! SendAck(namespace, args, id)
}