package play.socketio

import akka.util.ByteString
import play.api.libs.json.{JsValue, Json, Reads, Writes}

/**
  * A socket.io session.
  *
  * The socket.io session object holds any information relevant to the session. A user can define data to be stored
  * there, such as authentication data, and then use that when connecting to namespaces.
  *
  * @param sid The session ID.
  * @param data The session data.
  */
case class SocketIOSession[+T](sid: String, data: T)

/**
  * A socket.io event.
  *
  * A socket.io event consists of a name (which is used on the client side to register handlers for events), and a set
  * of arguments associated with that event.
  *
  * The arguments mirror the structure in JavaScript, when you publish an event, you pass the event name, and then
  * several arguments. When you subscribe to an event, you declare a function that takes zero or more arguments.
  * Consequently, the arguments get modelled as a sequence.
  *
  * Furthermore, socket.io allows you to pass either a structure that can be serialized to JSON (in the Play world,
  * this is a [[JsValue]]), or a binary argument (in the Play world, this is a [[ByteString]]). Hence, each argument
  * is `Either[JsValue, ByteString]`.
  *
  * Finally, as the last argument, socket.io allows the emitter to pass an ack function. This then gets passed to the
  * consumer as the last argument to their callback function, and they can invoke it, and the arguments passed to it
  * will be serialized to the wire, and passed to the function registered on the other side.
  *
  * @param name The name of the event.
  * @param arguments The list of arguments.
  * @param ack An optional ack function.
  */
case class SocketIOEvent(name: String, arguments: Seq[Either[JsValue, ByteString]], ack: Option[SocketIOEventAck])

object SocketIOEvent {
  def unnamed(arguments: Seq[Either[JsValue, ByteString]], ack: Option[SocketIOEventAck]) =
    SocketIOEvent("<unnamed>", arguments, ack)
}

/**
  * A socket.io ack function.
  */
trait SocketIOEventAck extends (Seq[Either[JsValue, ByteString]] => Unit)

/**
  * Convenience to create an ack function.
  */
object SocketIOEventAck {
  def apply(ack: Seq[Either[JsValue, ByteString]] => Unit): SocketIOEventAck = new SocketIOEventAck {
    override def apply(args: Seq[Either[JsValue, ByteString]]) = ack(args)
  }
}

/**
  * Exception thrown when there was an error encoding or decoding an event.
  */
class SocketIOEventCodecException(message: String) extends RuntimeException(message)

/**
  * DSL for creating SocketIOEvents.
  */
object SocketIOEventCodec {

  type SocketIOEventsDecoder[+T] = PartialFunction[SocketIOEvent, T]
  type SocketIOEventDecoder[+T] = SocketIOEvent => T

  type SocketIOEventsEncoder[-T] = PartialFunction[T, SocketIOEvent]
  type SocketIOEventEncoder[-T] = T => SocketIOEvent

  def decodeByName[T](decoders: PartialFunction[String, SocketIOEventDecoder[T]]): SocketIOEventsDecoder[T] =
    new PartialFunction[SocketIOEvent, T] {
      override def isDefinedAt(event: SocketIOEvent) = decoders.isDefinedAt(event.name)
      override def apply(event: SocketIOEvent) = decoders(event.name)(event)
      override def applyOrElse[A1 <: SocketIOEvent, B1 >: T](event: A1, default: (A1) => B1) = {
        decoders.andThen(decoder => decoder.apply(event))
          .applyOrElse(event.name, { _: String => default(event) })
      }
    }

  def decodeJson[T: Reads]: SocketIOArgDecoder[T] = SocketIOArgDecoder.json[T]

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

  def encodeJson[T: Writes]: SocketIOArgEncoder[T] = SocketIOArgEncoder.json[T]

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