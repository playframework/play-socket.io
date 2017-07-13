package play.socketio.scaladsl

import akka.NotUsed
import akka.util.ByteString
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import play.socketio.{SocketIOEvent, SocketIOEventAck}

/**
  * DSL for creating a codec for socket.io.
  */
object SocketIOEventCodec {

  type SocketIOEventsDecoder[+T] = PartialFunction[SocketIOEvent, T]
  type SocketIOEventDecoder[+T] = SocketIOEvent => T

  type SocketIOEventsEncoder[-T] = PartialFunction[T, SocketIOEvent]
  type SocketIOEventEncoder[-T] = T => SocketIOEvent

  /**
    * Create a socket.io events decoder that decodes events by event name.
    *
    * For example:
    *
    * ```
    * val chatDecoder = decodeByName {
    *   case "chat message" => decodeJson[String]
    * }
    * ```
    */
  def decodeByName[T](decoders: PartialFunction[String, SocketIOEventDecoder[T]]): SocketIOEventsDecoder[T] =
    new PartialFunction[SocketIOEvent, T] {
      override def isDefinedAt(event: SocketIOEvent) = decoders.isDefinedAt(event.name)
      override def apply(event: SocketIOEvent) = decoders(event.name)(event)
      override def applyOrElse[A1 <: SocketIOEvent, B1 >: T](event: A1, default: (A1) => B1) = {
        decoders.andThen(decoder => decoder.apply(event))
          .applyOrElse(event.name, { _: String => default(event) })
      }
    }

  /**
    * Create a socket.io decoder that decodes a single argument as JSON, to the given type T.
    */
  def decodeJson[T: Reads]: SocketIOArgDecoder[T] = SocketIOArgDecoder.json[T]

  /**
    * Create a socket.io decoder that decodes a single argument as binary.
    */
  def decodeBytes: SocketIOArgDecoder[ByteString] = SocketIOArgDecoder {
    case Left(_) => throw new SocketIOEventCodecException("Expected binary argument")
    case Right(bytes) => bytes
  }

  /**
    * Create a socket.io decoder that decodes no arguments.
    */
  def decodeNoArgs: SocketIOArgsDecoder[NotUsed] = SocketIOArgsDecoder(_ => NotUsed)

  /**
    * Create a socket.io events encoder that encodes events by type.
    *
    * The function should return a tuple of the event name and the encoder to use for the event.
    *
    * For example:
    *
    * ```
    * val chatEncoder = encodeByName[String] {
    *   case _: String => "chat message" -> encodeJson[String]
    * }
    * ```
    */
  def encodeByType[T](encoders: PartialFunction[T, (String, SocketIOEventEncoder[_ <: T])]): SocketIOEventsEncoder[T] = {
    new PartialFunction[T, SocketIOEvent] {
      override def isDefinedAt(t: T) = encoders.isDefinedAt(t)
      override def apply(t: T) = {
        val (name, encoder) = encoders.apply(t)
        encoder.asInstanceOf[Function[Any, SocketIOEvent]](t).copy(name = name)
      }
      override def applyOrElse[T1 <: T, B1 >: SocketIOEvent](t: T1, default: (T1) => B1) = {
        encoders.andThen {
          case (name, encoder) => encoder.asInstanceOf[Function[Any, SocketIOEvent]](t).copy(name = name)
        }.applyOrElse(t, default)
      }
    }
  }

  /**
    * Encode a single argument to JSON from the given type T.
    */
  def encodeJson[T: Writes]: SocketIOArgEncoder[T] = SocketIOArgEncoder.json[T]

  /**
    * Encode a single [[ByteString]] argument to binary.
    */
  def encodeBytes: SocketIOArgEncoder[ByteString] = SocketIOArgEncoder(Right(_))

  /**
    * Encode no arguments.
    */
  def encodeNoArgs: SocketIOArgsEncoder[NotUsed] = SocketIOArgsEncoder(_ => Nil)

  implicit class SocketIOSingleArgDecoderBuilder[T](first: SocketIOArgDecoder[T]) {
    def and[T1](second: SocketIOArgDecoder[T1]): SocketIOArgsDecoder[(T, T1)] = {
      SocketIOArgsDecoder { args =>
        if (args.length < 2) {
          throw new SocketIOEventCodecException("Needed 2 arguments to decode, but got " + args.length)
        }
        (first.decodeArg(args(0)), second.decodeArg(args(1)))
      }
    }

    def ~[T1](second: SocketIOArgDecoder[T1]): SocketIOArgsDecoder[(T, T1)] = and(second)

    def withAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T, A => Unit)] = { event =>
      val ack = event.ack.getOrElse {
        throw new SocketIOEventCodecException("Expected ack")
      }
      (first(event), ack.compose(encoder.encodeArgs))
    }

    def withMaybeAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T, Option[A => Unit])] = { event =>
      (first(event), event.ack.map { ack =>
        ack.compose(encoder.encodeArgs)
      })
    }
  }

  implicit class SocketIOTwoArgDecoderBuilder[T1, T2](init: SocketIOArgsDecoder[(T1, T2)]) {
    def and[T3](third: SocketIOArgDecoder[T3]): SocketIOArgsDecoder[(T1, T2, T3)] = {
      SocketIOArgsDecoder { args =>
        if (args.length < 3) {
          throw new SocketIOEventCodecException("Needed 3 arguments to decode, but got " + args.length)
        }
        val initArgs = init.decodeArgs(args)
        (initArgs._1, initArgs._2, third.decodeArg(args(2)))
      }
    }

    def ~[T3](third: SocketIOArgDecoder[T3]): SocketIOArgsDecoder[(T1, T2, T3)] = and(third)

    def withAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T1, T2, A => Unit)] = { event =>
      val ack = event.ack.getOrElse {
        throw new SocketIOEventCodecException("Expected ack")
      }
      val args = init(event)
      (args._1, args._2, ack.compose(encoder.encodeArgs))
    }

    def withMaybeAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T1, T2, Option[A => Unit])] = { event =>
      val args = init(event)
      (args._1, args._2, event.ack.map { ack =>
        ack.compose(encoder.encodeArgs)
      })
    }
  }

  implicit class SocketIOThreeArgDecoderBuilder[T1, T2, T3](init: SocketIOArgsDecoder[(T1, T2, T3)]) {
    def and[T4](fourth: SocketIOArgDecoder[T4]): SocketIOArgsDecoder[(T1, T2, T3, T4)] = {
      SocketIOArgsDecoder { args =>
        if (args.length < 4) {
          throw new SocketIOEventCodecException("Needed 4 arguments to decode, but got " + args.length)
        }
        val initArgs = init.decodeArgs(args)
        (initArgs._1, initArgs._2, initArgs._3, fourth.decodeArg(args(3)))
      }
    }

    def ~[T4](fourth: SocketIOArgDecoder[T4]): SocketIOArgsDecoder[(T1, T2, T3, T4)] = and(fourth)

    def withAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T1, T2, T3, A => Unit)] = { event =>
      val ack = event.ack.getOrElse {
        throw new SocketIOEventCodecException("Expected ack")
      }
      val args = init(event)
      (args._1, args._2, args._3, ack.compose(encoder.encodeArgs))
    }

    def withMaybeAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T1, T2, T3, Option[A => Unit])] = { event =>
      val args = init(event)
      (args._1, args._2, args._3, event.ack.map { ack =>
        ack.compose(encoder.encodeArgs)
      })
    }
  }

  implicit class SocketIOSingleArgEncoderBuilder[T](first: SocketIOArgEncoder[T]) {
    def and[T1](second: SocketIOArgEncoder[T1]): SocketIOArgsEncoder[(T, T1)] = {
      SocketIOArgsEncoder {
        case (t, t1) => Seq(first.encodeArg(t), second.encodeArg(t1))
      }
    }

    def ~[T1](second: SocketIOArgEncoder[T1]): SocketIOArgsEncoder[(T, T1)] = and(second)

    def withAckDecoder[A](decoder: SocketIOArgsDecoder[A]): SocketIOEventCodec.SocketIOEventEncoder[(T, A => Unit)] = {
      case (t, ack) =>
        SocketIOEvent.unnamed(Seq(first.encodeArg(t)), Some(SocketIOEventAck(ack.compose(decoder.decodeArgs))))
    }
  }

  implicit class SocketIOTwoArgEncoderBuilder[T1, T2](init: SocketIOArgsEncoder[(T1, T2)]) {
    def and[T3](third: SocketIOArgEncoder[T3]): SocketIOArgsEncoder[(T1, T2, T3)] = {
      SocketIOArgsEncoder {
        case (t1, t2, t3) =>
          val initArgs = init.encodeArgs((t1, t2))
          initArgs :+ third.encodeArg(t3)
      }
    }

    def ~[T3](third: SocketIOArgEncoder[T3]): SocketIOArgsEncoder[(T1, T2, T3)] = and(third)

    def withAckDecoder[A](decoder: SocketIOArgsDecoder[A]): SocketIOEventCodec.SocketIOEventEncoder[(T1, T2, A => Unit)] = {
      case (t1, t2, ack) =>
        SocketIOEvent.unnamed(init.encodeArgs((t1, t2)), Some(SocketIOEventAck(ack.compose(decoder.decodeArgs))))
    }
  }

  implicit class SocketIOThreeArgEncoderBuilder[T1, T2, T3](init: SocketIOArgsEncoder[(T1, T2, T3)]) {
    def and[T4](fourth: SocketIOArgEncoder[T4]): SocketIOArgsEncoder[(T1, T2, T3, T4)] = {
      SocketIOArgsEncoder {
        case (t1, t2, t3, t4) =>
          val initArgs = init.encodeArgs((t1, t2, t3))
          initArgs :+ fourth.encodeArg(t4)
      }
    }

    def ~[T4](fourth: SocketIOArgEncoder[T4]): SocketIOArgsEncoder[(T1, T2, T3, T4)] = and(fourth)

    def withAckDecoder[A](decoder: SocketIOArgsDecoder[A]): SocketIOEventCodec.SocketIOEventEncoder[(T1, T2, T3, A => Unit)] = {
      case (t1, t2, t3, ack) =>
        SocketIOEvent.unnamed(init.encodeArgs((t1, t2, t3)), Some(SocketIOEventAck(ack.compose(decoder.decodeArgs))))
    }
  }
}


/**
  * Exception thrown when there was an error encoding or decoding an event.
  */
class SocketIOEventCodecException(message: String) extends RuntimeException(message)



trait SocketIOArgsDecoder[+T] extends SocketIOEventCodec.SocketIOEventDecoder[T] {
  def decodeArgs(args: Seq[Either[JsValue, ByteString]]): T

  override def apply(event: SocketIOEvent) = decodeArgs(event.arguments)
}

object SocketIOArgsDecoder {
  def apply[T](decoder: Seq[Either[JsValue, ByteString]] => T): SocketIOArgsDecoder[T] = new SocketIOArgsDecoder[T] {
    override def decodeArgs(args: Seq[Either[JsValue, ByteString]]) = decoder(args)
  }
}

trait SocketIOArgDecoder[+T] extends SocketIOArgsDecoder[T] {

  override def apply(event: SocketIOEvent): T = {
    decodeArgs(event.arguments)
  }

  override def decodeArgs(args: Seq[Either[JsValue, ByteString]]): T = {
    decodeArg(args.headOption.getOrElse {
      throw new SocketIOEventCodecException("No arguments found to decode")
    })
  }

  def decodeArg(arg: Either[JsValue, ByteString]): T
}

object SocketIOArgDecoder {

  def apply[T](decode: Either[JsValue, ByteString] => T): SocketIOArgDecoder[T] = new SocketIOArgDecoder[T] {
    override def decodeArg(arg: Either[JsValue, ByteString]) = decode(arg)
  }

  def json[T: Reads]: SocketIOArgDecoder[T] = apply {
    case Left(jsValue) => jsValue.as[T]
    case Right(bytes) => Json.parse(bytes.toArray).as[T]
  }
}

trait SocketIOArgsEncoder[-T] extends SocketIOEventCodec.SocketIOEventEncoder[T] {
  def encodeArgs(t: T): Seq[Either[JsValue, ByteString]]

  override def apply(t: T) = SocketIOEvent.unnamed(encodeArgs(t), None)
}

object SocketIOArgsEncoder {
  def apply[T](encode: T => Seq[Either[JsValue, ByteString]]): SocketIOArgsEncoder[T] = new SocketIOArgsEncoder[T] {
    override def encodeArgs(t: T) = encode(t)
  }
}

trait SocketIOArgEncoder[-T] extends SocketIOArgsEncoder[T] {
  override def encodeArgs(t: T) = Seq(encodeArg(t))
  def encodeArg(t: T): Either[JsValue, ByteString]
}

object SocketIOArgEncoder {

  def apply[T](encoder: T => Either[JsValue, ByteString]): SocketIOArgEncoder[T] = new SocketIOArgEncoder[T] {
    override def encodeArg(t: T) = encoder(t)
  }

  def json[T: Writes]: SocketIOArgEncoder[T] = apply { t =>
    Left(Json.toJson(t))
  }
}