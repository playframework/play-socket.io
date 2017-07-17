/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.protocol

import akka.util.ByteString
import play.api.libs.json._
import play.engineio.protocol._
import play.engineio.{ BinaryEngineIOMessage, EngineIOMessage, TextEngineIOMessage }

import scala.collection.immutable

/**
 * A socket.io packet type.
 */
sealed abstract class SocketIOPacketType private (val id: Int) {
  // Encode the packet as a char.
  val asChar = id.toString.head
}

object SocketIOPacketType {
  case object Connect extends SocketIOPacketType(0)
  case object Disconnect extends SocketIOPacketType(1)
  case object Event extends SocketIOPacketType(2)
  case object Ack extends SocketIOPacketType(3)
  case object Error extends SocketIOPacketType(4)
  case object BinaryEvent extends SocketIOPacketType(5)
  case object BinaryAck extends SocketIOPacketType(6)

  def fromChar(char: Char) = char match {
    case '0' => Connect
    case '1' => Disconnect
    case '2' => Event
    case '3' => Ack
    case '4' => Error
    case '5' => BinaryEvent
    case '6' => BinaryAck
    case _   => throw SocketIOEncodingException("", "Unknown socket.io packet type: " + char)
  }
}

/**
 * A socket.io packet.
 */
sealed trait SocketIOPacket {
  def packetType: SocketIOPacketType
  val namespace: Option[String]
}

case class SocketIOConnectPacket(namespace: Option[String]) extends SocketIOPacket {
  override def packetType = SocketIOPacketType.Connect
}

case class SocketIODisconnectPacket(namespace: Option[String]) extends SocketIOPacket {
  override def packetType = SocketIOPacketType.Disconnect
}

case class SocketIOEventPacket(namespace: Option[String], data: Seq[JsValue], id: Option[Long]) extends SocketIOPacket {
  override def packetType = SocketIOPacketType.Event
}

case class SocketIOAckPacket(namespace: Option[String], data: Seq[JsValue], id: Long) extends SocketIOPacket {
  override def packetType = SocketIOPacketType.Ack
}

case class SocketIOErrorPacket(namespace: Option[String], data: JsValue) extends SocketIOPacket {
  override def packetType = SocketIOPacketType.Error
}

object SocketIOErrorPacket {
  def encode(namespace: Option[String], data: JsValue): Utf8EngineIOPacket = {
    val message = new StringBuilder
    message += SocketIOPacketType.Error.asChar
    namespace.foreach { ns =>
      message ++= ns
      message += ','
    }
    message ++= Json.stringify(data)
    Utf8EngineIOPacket(EngineIOPacketType.Message, message.toString)
  }
}

case class SocketIOBinaryEventPacket(namespace: Option[String], data: Seq[Either[JsValue, ByteString]], id: Option[Long]) extends SocketIOPacket {
  override def packetType = SocketIOPacketType.BinaryEvent
}

case class SocketIOBinaryAckPacket(namespace: Option[String], data: Seq[Either[JsValue, ByteString]], id: Long) extends SocketIOPacket {
  override def packetType = SocketIOPacketType.BinaryAck
}

object SocketIOPacket {

  /**
   * When encoding a binary packet, each binary data element will result in an additional packet being sent.
   */
  def encode(packet: SocketIOPacket): List[EngineIOMessage] = {

    // First, write packet type. Binary packets get handled specially.
    val message = new StringBuilder
    packet match {
      case SocketIOBinaryEventPacket(_, data, _) =>
        val placeholders = data.count(_.isRight)
        if (placeholders == 0) {
          message += SocketIOPacketType.Event.asChar
        } else {
          message += packet.packetType.asChar
          message ++= placeholders.toString
          message += '-'
        }
      case SocketIOBinaryAckPacket(_, data, id) =>
        val placeholders = data.count(_.isRight)
        if (placeholders == 0) {
          message += SocketIOPacketType.Ack.asChar
        } else {
          message += packet.packetType.asChar
          message ++= placeholders.toString
          message += '-'
        }
      case _ =>
        message += packet.packetType.asChar
    }

    // Encode namespace
    packet.namespace.foreach { ns =>
      message ++= ns
    }

    def encodeData(data: JsValue, id: Option[Long]): Unit = {
      if (packet.namespace.isDefined) {
        message += ','
      }
      id.foreach(id =>
        message ++= id.toString)
      message ++= Json.stringify(data)
    }

    // Now the payload
    val extraPackets = packet match {
      case SocketIOEventPacket(_, data, id) =>
        encodeData(JsArray(data), id)
        Nil
      case SocketIOAckPacket(_, data, id) =>
        encodeData(JsArray(data), Some(id))
        Nil
      case SocketIOErrorPacket(_, data) =>
        encodeData(data, None)
        Nil
      case SocketIOBinaryEventPacket(_, data, id) =>
        val (dataWithPlaceholders, extraPackets) = fillInPlaceholders(data)
        encodeData(JsArray(dataWithPlaceholders), id)
        extraPackets
      case SocketIOBinaryAckPacket(_, data, id) =>
        val (dataWithPlaceholders, extraPackets) = fillInPlaceholders(data)
        encodeData(JsArray(dataWithPlaceholders), Some(id))
        extraPackets
      case _ =>
        // All other packets have no additional data
        Nil
    }

    TextEngineIOMessage(message.toString) ::
      extraPackets.map(BinaryEngineIOMessage)
  }

  /**
   * Decode an engine IO packet into a socket IO packet and a number of expected binary messages to follow.
   */
  def decode(text: String): (SocketIOPacket, Int) = {
    if (text.isEmpty) {
      throw SocketIOEncodingException(text, "Empty socket.io packet")
    }

    val packetType = SocketIOPacketType.fromChar(text.head)
    val (placeholders, namespaceStart) = if (packetType == SocketIOPacketType.BinaryAck || packetType == SocketIOPacketType.BinaryEvent) {
      val placeholdersSeparator = text.indexOf('-', 1)
      if (placeholdersSeparator == -1) {
        throw SocketIOEncodingException(text, s"Malformed binary socket.io packet, missing placeholder separator")
      }
      val placeholders = try {
        text.substring(1, placeholdersSeparator).toInt
      } catch {
        case _: NumberFormatException =>
          throw SocketIOEncodingException(text, "Malformed binary socket.io packet, num placeholders is not a number: '" +
            text.substring(1, placeholdersSeparator) + "'")
      }
      (placeholders, placeholdersSeparator + 1)
    } else {
      (0, 1)
    }

    val (namespace, dataStart) = if (text.length > namespaceStart && text(namespaceStart) == '/') {
      val namespaceEnd = text.indexOf(',', namespaceStart)
      if (namespaceEnd == -1) {
        throw SocketIOEncodingException(text, "Expected ',' to end namespace declaration")
      }
      (Some(text.substring(namespaceStart, namespaceEnd)), namespaceEnd + 1)
    } else {
      (None, namespaceStart)
    }

    val (index, args) = if (text.length > dataStart) {
      val argsStart = text.indexOf('[', dataStart)

      if (argsStart == -1) {
        throw SocketIOEncodingException(text, s"Expected JSON array open after data separator, but got '${text.substring(dataStart)}'")
      }

      val index = if (argsStart - dataStart >= 1) {
        try {
          Some(text.substring(dataStart, argsStart).toLong)
        } catch {
          case _: NumberFormatException =>
            throw SocketIOEncodingException(text, "Malformed socket.io packet, index is not a number")
        }
      } else {
        None
      }

      val argsData = text.substring(argsStart)
      val args = try {
        Json.parse(argsData)
      } catch {
        case e: Exception =>
          throw SocketIOEncodingException(text, "Error parsing socket.io args", e)
      }

      (index, Some(args))
    } else {
      (None, None)
    }

    val socketIOPacket = (packetType, args) match {
      case (SocketIOPacketType.Connect, None) =>
        SocketIOConnectPacket(namespace)
      case (SocketIOPacketType.Disconnect, None) =>
        SocketIODisconnectPacket(namespace)
      case (SocketIOPacketType.Event, Some(data: JsArray)) =>
        SocketIOEventPacket(namespace, data.value, index)
      case (SocketIOPacketType.Ack, Some(data: JsArray)) if index.isDefined =>
        SocketIOAckPacket(namespace, data.value, index.get)
      case (SocketIOPacketType.Error, Some(data)) =>
        SocketIOErrorPacket(namespace, data)
      case (SocketIOPacketType.BinaryEvent, Some(data: JsArray)) =>
        SocketIOBinaryEventPacket(namespace, data.value.map(Left.apply), index)
      case (SocketIOPacketType.BinaryAck, Some(data: JsArray)) if index.isDefined =>
        SocketIOBinaryAckPacket(namespace, data.value.map(Left.apply), index.get)
      case _ =>
        throw SocketIOEncodingException(text, "Malformed socket.io packet")
    }

    (socketIOPacket, placeholders)
  }

  def replacePlaceholders(data: Seq[Either[JsValue, ByteString]], parts: IndexedSeq[ByteString]): Seq[Either[JsValue, ByteString]] = {
    data.map {
      case Left(obj: JsObject) if (obj \ "_placeholder").toOption.contains(JsTrue) =>
        val num = (obj \ "num").as[Int]
        Right(parts(num))
      // Shouldn't get any rights, but anyway
      case other => other
    }
  }

  private def fillInPlaceholders(data: Seq[Either[JsValue, ByteString]]): (immutable.Seq[JsValue], List[ByteString]) = {
    data.foldLeft((List.empty[JsValue], List.empty[ByteString])) {
      case ((jsonData, extra), Left(jsValue)) =>
        (jsonData :+ jsValue, extra)
      case ((jsonData, extra), Right(binaryData)) =>
        (jsonData :+ Json.obj("_placeholder" -> true, "num" -> extra.size), extra :+ binaryData)
    }
  }
}

/**
 * Exception thrown when there was a problem encoding or decoding a socket.io packet from the underlying engine.io
 * packets.
 */
case class SocketIOEncodingException(packet: String, message: String, cause: Exception = null)
  extends RuntimeException(s"Error decoding socket IO packet '${packet.take(80)}${if (packet.length > 80) "..." else ""}': $message", cause)