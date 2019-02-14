/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */

package play.engineio

import akka.actor.{ Actor, ActorLogging, ActorRef, DeadLetterSuppression, Props, Status }
import akka.pattern.pipe
import akka.stream._
import akka.stream.scaladsl._
import akka.{ Done, NotUsed }
import play.api.mvc.RequestHeader
import play.engineio.protocol.EngineIOTransport.Polling
import play.engineio.protocol._

import scala.util.control.NonFatal

/**
 * Actor that provides engine.io sessions.
 *
 * All the messages below are sent from this actor to itself.
 */
object EngineIOSessionActor {

  /**
   * The the result of the engine.io handler connect operation.
   */
  case class Connected(flow: Flow[EngineIOMessage, Seq[EngineIOMessage], NotUsed], requestId: String)

  /**
   * Sent when the engine.io handler throws an exception.
   */
  case class ConnectionRefused(e: Throwable)

  /**
   * The result of pulling from the sink queue.
   */
  case class PulledPackets(packets: Seq[EngineIOPacket]) extends DeadLetterSuppression

  /**
   * Message sent when the source queue acknowledges that they've been sent.
   */
  case object MessagesPushed

  /**
   * A tick.
   */
  case object Tick extends DeadLetterSuppression

  def props[SessionData](
    config:  EngineIOConfig,
    handler: EngineIOSessionHandler)(implicit mat: Materializer) = Props {

    new EngineIOSessionActor[SessionData](config, handler)
  }
}

/**
 * Actor that provides engine.io sessions.
 */
class EngineIOSessionActor[SessionData](
  config:  EngineIOConfig,
  handler: EngineIOSessionHandler)(implicit mat: Materializer) extends Actor with ActorLogging {

  import context.dispatcher

  private val sid = context.self.path.name

  // The current transport. Pings must be responded to on the same transport, while all other
  // messages should only go to the requester for the current transport.
  private var activeTransport: EngineIOTransport = _

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
  private var messagesReceived = Seq.empty[EngineIOMessage]
  // The senders waiting for their packets to be sent to the source queue.
  private var messagesReceivedSenders = Set.empty[ActorRef]
  // Whether we are currently pushing packets to the source code. If this is false, packetsReceived
  // and packetsReceivedSenders must be empty.
  private var currentlyPushingMessages: Boolean = false

  // The source queue. We push messages in batches as we have them, but only one batch at a time,
  // waiting for acknowledgement before pushing the next.
  private var sourceQueue: SourceQueueWithComplete[Seq[EngineIOMessage]] = _
  // The sink queue. We retrieve messages in batches, and continue retrieving up to a buffer.
  private var sinkQueue: SinkQueueWithCancel[Seq[EngineIOPacket]] = _

  // A deadline by which if no communication is received from the client, we terminate the session.
  private var sessionTimeout = config.pingTimeout.fromNow

  // This works around a race condition in the engine.io client that is described in detail here:
  // https://github.com/googollee/go-engine.io/issues/33
  // When set to true, then whenever we get a retrieve from a polling transport and we don't have
  // packets for it, we tell it to go away.
  private var upgradingFromPollingHack = false

  import EngineIOManagerActor._
  import EngineIOSessionActor._

  private val sessionTick = context.system.scheduler.schedule(config.pingInterval, config.pingInterval, self, Tick)

  override def postStop() = {
    retrieveRequesters.foreach {
      case (transport, (RetrieveRequester(requester, requestId))) =>
        requester ! Close(sid, transport, requestId)
    }
    messagesReceivedSenders.foreach { messageSender =>
      messageSender ! Status.Failure(SessionClosed)
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
      tryConnect(sid, request, requestId)

    case Close(_, transport, requestId) =>
      debug(requestId, transport, "Requested to close session")
      context.stop(self)
      sender ! Done

    case message: SessionMessage =>
      debug(message.requestId, message.transport, s"Unknown session id")
      sender ! Status.Failure(UnknownSessionId(sid))
      context.stop(self)
  }

  def tryConnect(sid: String, request: RequestHeader, requestId: String) = {
    try {
      handler.onConnect(request, sid)
        .map(flow => Connected(flow, requestId))
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

    case Connected(flow, requestId) =>
      log.debug("{} - Connection successful", sid)
      sender ! Packets(sid, activeTransport, Seq(EngineIOPacket(
        EngineIOPacketType.Open,
        EngineIOOpenMessage(sid, config.transports.filterNot(_ == activeTransport), pingInterval = config.pingInterval,
          pingTimeout = config.pingTimeout))), requestId)

      doConnect(flow)

    case ConnectionRefused(e) =>
      sender ! Status.Failure(e)

    case Close(_, transport, requestId) =>
      debug(requestId, transport, "Requested to close session")
      sender ! Done
      context.stop(self)
  }

  private def tick(): Unit = {
    // If the session has timed out (ie, we haven't received anything from the client)
    // stop.
    if (sessionTimeout.isOverdue()) {
      log.debug("{} - Shutting down session due to timeout", sid)
      context.stop(self)
    }
  }

  private def resetTimeout(): Unit = {
    sessionTimeout = config.pingTimeout.fromNow
  }

  private def doConnect(flow: Flow[EngineIOMessage, Seq[EngineIOMessage], NotUsed]): Unit = {

    def messagesToPackets(messages: Seq[EngineIOMessage]) = messages.map {
      case TextEngineIOMessage(text)    => Utf8EngineIOPacket(EngineIOPacketType.Message, text)
      case BinaryEngineIOMessage(bytes) => BinaryEngineIOPacket(EngineIOPacketType.Message, bytes)
    }

    // Set up flow
    val (sourceQ, sinkQ) = Source.queue[Seq[EngineIOMessage]](1, OverflowStrategy.backpressure)
      .expand(_.iterator)
      .via(flow)
      .batch(4, messagesToPackets)(_ ++ messagesToPackets(_))
      .toMat(Sink.queue[Seq[EngineIOPacket]])(Keep.both)
      .run

    sourceQueue = sourceQ
    sinkQueue = sinkQ

    requestMorePackets()

    context.become(connected)
  }

  private def connected: Receive = {
    case Tick =>
      tick()

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

    case Packets(_, transport, packets, requestId) =>
      debug(requestId, transport, "Received {} incoming packets", packets.size)
      resetTimeout()

      handleIncomingEngineIOPackets(transport, packets, requestId)

    case MessagesPushed =>
      log.debug("{} - Pushed packet buffer", sid)
      currentlyPushingMessages = false

      if (messagesReceived.nonEmpty) {
        sourceQueue.offer(messagesReceived).map(_ => MessagesPushed) pipeTo self
        currentlyPushingMessages = true
        messagesReceivedSenders.foreach(_ ! Done)
        messagesReceivedSenders = Set.empty
        messagesReceived = Nil
      }

    case Close(_, transport, requestId) =>
      if (transport == activeTransport) {
        debug(requestId, transport, "Requested to close session")
        context.stop(self)
      }

  }

  private def handleIncomingEngineIOPackets(transport: EngineIOTransport, packets: Seq[EngineIOPacket], requestId: String) = {
    var messagesToPush = Seq.empty[EngineIOMessage]

    packets.foreach { packet =>

      packet.typeId match {
        case EngineIOPacketType.Message =>
          packet match {
            case BinaryEngineIOPacket(_, bytes) =>
              messagesToPush :+= BinaryEngineIOMessage(bytes)
            case Utf8EngineIOPacket(_, text) =>
              messagesToPush :+= TextEngineIOMessage(text)
          }

        case EngineIOPacketType.Ping =>
          packet match {
            case Utf8EngineIOPacket(_, data) =>
              handlePing(transport, requestId, data)
            case BinaryEngineIOPacket(_, data) =>
              // Can we get binary pings?
              sendPackets(Seq(BinaryEngineIOPacket(EngineIOPacketType.Pong, data)), transport)
          }

        case EngineIOPacketType.Upgrade =>
          handleUpgrade(transport, requestId)

        case EngineIOPacketType.Close =>
          context.stop(self)

        case EngineIOPacketType.Pong =>
        // We don't send pings, so we're not likely to get a pong, but who cares, ignore it

        case EngineIOPacketType.Noop =>
        // Noop, ignore.

        case EngineIOPacketType.Open =>
        // We shouldn't get this, ignore
      }
    }

    if (messagesToPush.nonEmpty) {
      if (currentlyPushingMessages) {
        messagesReceived ++= messagesToPush
        messagesReceivedSenders += sender
      } else {
        sourceQueue.offer(messagesToPush).map(_ => MessagesPushed) pipeTo self
        currentlyPushingMessages = true
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

  private def requestMorePackets() = {
    if (!currentlyPullingPackets) {
      sinkQueue.pull().map {
        case Some(packets) => PulledPackets(packets)
        case None          => PulledPackets(Nil)
      } pipeTo self
      currentlyPullingPackets = true
    }
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

private case class RetrieveRequester(requester: ActorRef, requestId: String)

