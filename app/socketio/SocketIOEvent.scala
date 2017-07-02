package socketio

import akka.util.ByteString
import play.api.libs.json.{JsValue, Json, Reads, Writes}

case class SocketIOSession[T](sid: String, data: T)

case class SocketIOEvent(name: String, arguments: Seq[Either[JsValue, ByteString]], ack: Option[SocketIOEventAck])

trait SocketIOEventAck extends (Seq[Either[JsValue, ByteString]] => Unit)

object SocketIOEventAck {
  def apply(ack: Seq[Either[JsValue, ByteString]] => Unit): SocketIOEventAck = new SocketIOEventAck {
    override def apply(args: Seq[Either[JsValue, ByteString]]) = ack(args)
  }
}

trait SocketIOEventDecoder[+T] {
  self =>

  def decode: PartialFunction[SocketIOEvent, T]

  def andThen[S](f: T => S) = new SocketIOEventDecoder[S] {
    override def decode = self.decode.andThen(f)
  }

  def zip[A](decoder: SocketIOEventDecoder[A]): SocketIOEventDecoder[(T, A)] = new SocketIOEventDecoder[(T, A)] {
    override def decode = new PartialFunction[SocketIOEvent, (T, A)] {
      override def isDefinedAt(e: SocketIOEvent) = self.decode.isDefinedAt(e)
      override def apply(e: SocketIOEvent) = applyOrElse(e, throw new MatchError(e))

      override def applyOrElse[E1 <: SocketIOEvent, B1 >: (T, A)](e: E1, default: (E1) => B1) =
        self.decode.andThen { t =>
          (t, decoder.decode(e))
        }.applyOrElse(e, default)
    }
  }

  def zipWith[A, S](decoder: SocketIOEventDecoder[A])(f: (T, A) => S): SocketIOEventDecoder[S] =
    zip(decoder).andThen(f.tupled)

  def withMaybeAck[A](ackEncoder: SocketIOEventEncoder[A]): SocketIOEventDecoder[(T, Option[A => Unit])] =
    zip(new SocketIOEventDecoder[Option[A => Unit]] {
      def decode = {
        case SocketIOEvent(_, _, None) => None
        case SocketIOEvent(_, _, Some(ack)) =>
          Some(ack.compose { a: A =>
            ackEncoder.encode.applyOrElse(a, throw new MatchError(a)).arguments
          })
      }
    })

  def withAck[A](ackEncoder: SocketIOEventEncoder[A]): SocketIOEventDecoder[(T, A => Unit)] =
    withMaybeAck(ackEncoder).andThen {
      case (t, maybeAck) => (t, maybeAck.getOrElse(throw new RuntimeException("Excepted ack but none found")))
    }

}

object SocketIOEventDecoder {

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

  def withAck[A](ackDecoder: SocketIOEventDecoder[A])(ackExtractor: T => (A => Unit)): SocketIOEventEncoder[T] = new SocketIOEventEncoder[T] {
    override def encode = new PartialFunction[Any, SocketIOEvent] {
      override def isDefinedAt(e: Any) = self.encode.isDefinedAt(e)
      override def apply(e: Any) = applyOrElse(e, throw new MatchError(e))
      override def applyOrElse[A1 <: Any, B1 >: SocketIOEvent](e: A1, default: (A1) => B1): B1 = {
        self.encode.andThen { event =>
          val ack = { args: Seq[Either[JsValue, ByteString]] =>
            val extractedAck = ackExtractor(e.asInstanceOf[T])
            extractedAck(ackDecoder.decode(SocketIOEvent("ack", args, None)))
          }
          event.copy(ack = Some(SocketIOEventAck(ack)))
        }.applyOrElse(e, default)
      }
    }
  }
}

object SocketIOEventEncoder {

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