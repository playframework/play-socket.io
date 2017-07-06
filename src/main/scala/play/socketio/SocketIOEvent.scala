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

/**
  * A socket.io ack function.
  */
trait SocketIOEventAck extends (Seq[Either[JsValue, ByteString]] => Unit)

/**
  * Convenient to create an ack function.
  */
object SocketIOEventAck {
  def apply(ack: Seq[Either[JsValue, ByteString]] => Unit): SocketIOEventAck = new SocketIOEventAck {
    override def apply(args: Seq[Either[JsValue, ByteString]]) = ack(args)
  }
}

object SocketIOEventCodec {

  def decoders[T](decoders: SocketIOEventDecoder[T]*): SocketIOEventDecoder[T] =
    SocketIOEventDecoder.compose(decoders: _*)

  def decodeJson[T: Reads](name: String): SocketIOEventDecoder[T] =
    SocketIOEventDecoder.json[T](name)

  def decodeJsonAck[T: Reads]: SocketIOAckDecoder[T] =
    SocketIOAckDecoder.json[T]

  def encoders[T](encoders: PartialFunction[Any, SocketIOEventEncoder[_ <: T]]): SocketIOEventEncoder[T] =
    SocketIOEventEncoder.compose[T](encoders)

  def encodeJson[T: Writes](name: String): SocketIOEventEncoder[T] =
    SocketIOEventEncoder.json[T](name)

  def encodeJsonAck[T: Writes]: SocketIOAckEncoder[T] =
    SocketIOAckEncoder.json[T]
}

trait SocketIOEventDecoder[+T] {
  self =>

  def decode: PartialFunction[SocketIOEvent, T]

  def map[S](f: T => S) = new SocketIOEventDecoder[S] {
    override def decode = self.decode.andThen(f)
  }

  def zip[A](decoder: SocketIOEventDecoder[A]): SocketIOEventDecoder[(T, A)] = new SocketIOEventDecoder[(T, A)] {
    override def decode = new PartialFunction[SocketIOEvent, (T, A)] {
      override def isDefinedAt(e: SocketIOEvent) = self.decode.isDefinedAt(e)
      override def apply(e: SocketIOEvent) = applyOrElse(e, (_: SocketIOEvent) => throw new MatchError(e))
      override def applyOrElse[E1 <: SocketIOEvent, B1 >: (T, A)](e: E1, default: (E1) => B1) = {
        self.decode.andThen { t =>
          (t, decoder.decode(e))
        }.applyOrElse(e, default)
      }
    }
  }

  def zipWith[A, S](decoder: SocketIOEventDecoder[A])(f: (T, A) => S): SocketIOEventDecoder[S] =
    zip(decoder).map(f.tupled)

  def withMaybeAck[A](ackEncoder: SocketIOAckEncoder[A]): SocketIOEventDecoder[(T, Option[A => Unit])] =
    zip(new SocketIOEventDecoder[Option[A => Unit]] {
      def decode = {
        case SocketIOEvent(_, _, None) => None
        case SocketIOEvent(_, _, Some(ack)) =>
          Some(ack.compose(ackEncoder.encode))
      }
    })

  def withAck[A](ackEncoder: SocketIOAckEncoder[A]): SocketIOEventDecoder[(T, A => Unit)] =
    withMaybeAck(ackEncoder).map {
      case (t, maybeAck) => (t, maybeAck.getOrElse(throw new RuntimeException("Excepted ack but none found")))
    }

}

object SocketIOEventDecoder {

  def raw: SocketIOEventDecoder[SocketIOEvent] = new SocketIOEventDecoder[SocketIOEvent] {
    override def decode = {
      case event => event
    }
  }

  def apply[T](name: String)(decoder: SocketIOEvent => T): SocketIOEventDecoder[T] = new SocketIOEventDecoder[T] {
    override def decode = {
      case event if event.name == name => decoder(event)
    }
  }

  def compose[T](decoders: SocketIOEventDecoder[T]*): SocketIOEventDecoder[T] = new SocketIOEventDecoder[T] {
    override def decode = {
      decoders.foldLeft(PartialFunction.empty[SocketIOEvent, T])(_ orElse _.decode)
    }
  }

  def json[T: Reads](name: String): SocketIOEventDecoder[T] = apply(name) { event =>
    event.arguments.headOption.flatMap(_.left.toOption) match {
      case Some(jsValue) => jsValue.as[T]
      case None => throw new RuntimeException("No arguments found to decode")
    }
  }
}

trait SocketIOEventEncoder[T] { self =>

  def encode: PartialFunction[Any, SocketIOEvent]

  def contramap[S](f: S => T) = SocketIOEventEncoder {
    case s: S => self.encode(f(s))
  }

  def withAck[A, S](ackDecoder: SocketIOAckDecoder[A]): SocketIOEventEncoder[(T, A => Unit)] = new SocketIOEventEncoder[(T, A => Unit)] {
    override def encode = {
      case (t: T, ack: (A => Unit)) =>
        val decodedAck = SocketIOEventAck { args =>
          ack(ackDecoder.decode(args))
        }
        self.encode(t).copy(ack = Some(decodedAck))
    }
  }
}

object SocketIOEventEncoder {

  def raw: SocketIOEventEncoder[SocketIOEvent] = new SocketIOEventEncoder[SocketIOEvent] {
    override def encode = {
      case event: SocketIOEvent => event
    }
  }

  def apply[T](encoder: PartialFunction[Any, SocketIOEvent]): SocketIOEventEncoder[T] = new SocketIOEventEncoder[T] {
    override def encode = encoder
  }

  def compose[T](encoders: PartialFunction[Any, SocketIOEventEncoder[_ <: T]]): SocketIOEventEncoder[T] = new SocketIOEventEncoder[T] {
    override def encode = Function.unlift { t: Any =>
      encoders.lift(t).flatMap { encoder =>
        encoder.encode.lift(t)
      }
    }
  }

  def json[T: Writes](name: String): SocketIOEventEncoder[T] = apply {
    case t: T => SocketIOEvent(name, Seq(Left(Json.toJson(t))), None)
  }
}

trait SocketIOAckDecoder[+T] { self =>

  def decode(args: Seq[Either[JsValue, ByteString]]): T

  def map[S](f: T => S): SocketIOAckDecoder[S] = SocketIOAckDecoder[S] { args =>
    f(self.decode(args))
  }
}

object SocketIOAckDecoder {

  def apply[T](decoder: Seq[Either[JsValue, ByteString]] => T): SocketIOAckDecoder[T] = new SocketIOAckDecoder[T] {
    override def decode(args: Seq[Either[JsValue, ByteString]]) = decoder(args)
  }

  def json[T: Reads]: SocketIOAckDecoder[T] = SocketIOAckDecoder { args =>
    args.headOption.flatMap(_.left.toOption) match {
      case Some(jsValue) => jsValue.as[T]
      case None => throw new RuntimeException("No arguments found to decode")
    }
  }
}

trait SocketIOAckEncoder[T] { self =>

  def encode(t: T): Seq[Either[JsValue, ByteString]]

  def contramap[S](f: S => T): SocketIOAckEncoder[S] = SocketIOAckEncoder { s =>
    self.encode(f(s))
  }
}

object SocketIOAckEncoder {

  def apply[T](encoder: T => Seq[Either[JsValue, ByteString]]): SocketIOAckEncoder[T] = new SocketIOAckEncoder[T] {
    override def encode(t: T) = encoder(t)
  }

  def json[T: Writes]: SocketIOAckEncoder[T] = SocketIOAckEncoder { t: T =>
    Seq(Left(Json.toJson(t)))
  }
}