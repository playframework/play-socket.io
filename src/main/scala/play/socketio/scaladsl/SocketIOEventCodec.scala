/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.scaladsl

import akka.NotUsed
import akka.util.ByteString
import play.api.libs.json.{ JsValue, Json, Reads, Writes }
import play.socketio.{ SocketIOEvent, SocketIOEventAck }

import scala.language.existentials

/**
 * DSL for creating a codec for socket.io.
 */
object SocketIOEventCodec {

  /**
   * Decoder type for decoding multiple events.
   *
   * The decoder is simply a partial function, which allows decoders to easily by composed together using `orElse`.
   */
  type SocketIOEventsDecoder[+T] = PartialFunction[SocketIOEvent, T]

  /**
   * Decoder type for decoding a single event.
   *
   * The decoder is simply a function, which allows it to be composed using the `andThen` and `compose` functions.
   */
  type SocketIOEventDecoder[+T] = SocketIOEvent => T

  /**
   * Encoder type for encoding multiple events.
   *
   * The encoder is simply a partial function, which allows encoders to easily by composed together using `orElse`.
   */
  type SocketIOEventsEncoder[-T] = PartialFunction[T, SocketIOEvent]

  /**
   * Encoder type for encoding a single event.
   *
   * The encoder is simply a function, which allows it to be composed using the `andThen` and `compose` functions.
   */
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
    case Left(_)      => throw new SocketIOEventCodecException("Expected binary argument")
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

  /**
   * Implicit conversion to enrich socket io decoders for composition.
   */
  implicit class SocketIOSingleArgDecoderBuilder[T](first: SocketIOArgDecoder[T]) {

    /**
     * Combine this single argument decoder with a second single argument decoder.
     *
     * The resultant decoder uses both decoders to decode two arguments into a tuple of the two results.
     */
    def and[T1](second: SocketIOArgDecoder[T1]): SocketIOArgsDecoder[(T, T1)] = {
      SocketIOArgsDecoder { args =>
        if (args.length < 2) {
          throw new SocketIOEventCodecException("Needed 2 arguments to decode, but got " + args.length)
        }
        (first.decodeArg(args(0)), second.decodeArg(args(1)))
      }
    }

    /**
     * Combine this single argument decoder with a second single argument decoder.
     *
     * The resultant decoder uses both decoders to decode two arguments into a tuple of the two results.
     */
    def ~[T1](second: SocketIOArgDecoder[T1]): SocketIOArgsDecoder[(T, T1)] = and(second)

    /**
     * Combine this decoder with an encoder to encode arguments of an ack function.
     *
     * When the ack is invoked, the argument passed to it will be encoded using the passed in encoder before being sent
     * to the client.
     *
     * The returned value is a tuple of the argument decoded by this decoder, and the ack function.
     *
     * If no ack is supplied by the client, this decoder will fail.
     */
    def withAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T, A => Unit)] = { event =>
      val ack = event.ack.getOrElse {
        throw new SocketIOEventCodecException("Expected ack")
      }
      (first(event), ack.compose(encoder.encodeArgs))
    }

    /**
     * Combine this decoder with an encoder to encode arguments of an ack function.
     *
     * When the ack is invoked, the argument passed to it will be encoded using the passed in encoder before being sent
     * to the client.
     *
     * The returned value is a tuple of the argument decoded by this decoder, and the ack function, if supplied by the
     * client.
     */
    def withMaybeAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T, Option[A => Unit])] = { event =>
      (first(event), event.ack.map { ack =>
        ack.compose(encoder.encodeArgs)
      })
    }
  }

  /**
   * Implicit conversion to enrich socket io decoders for composition.
   */
  implicit class SocketIOTwoArgDecoderBuilder[T1, T2](init: SocketIOArgsDecoder[(T1, T2)]) {

    /**
     * Combine this two argument decoder with a third single argument decoder.
     *
     * The resultant decoder uses all decoders to decode three arguments into a tuple of the three results.
     */
    def and[T3](third: SocketIOArgDecoder[T3]): SocketIOArgsDecoder[(T1, T2, T3)] = {
      SocketIOArgsDecoder { args =>
        if (args.length < 3) {
          throw new SocketIOEventCodecException("Needed 3 arguments to decode, but got " + args.length)
        }
        val initArgs = init.decodeArgs(args)
        (initArgs._1, initArgs._2, third.decodeArg(args(2)))
      }
    }

    /**
     * Combine this two argument decoder with a third single argument decoder.
     *
     * The resultant decoder uses all decoders to decode three arguments into a tuple of the three results.
     */
    def ~[T3](third: SocketIOArgDecoder[T3]): SocketIOArgsDecoder[(T1, T2, T3)] = and(third)

    /**
     * Combine this decoder with an encoder to encode arguments of an ack function.
     *
     * When the ack is invoked, the argument passed to it will be encoded using the passed in encoder before being sent
     * to the client.
     *
     * The returned value is a tuple of the arguments decoded by this decoder, and the ack function.
     *
     * If no ack is supplied by the client, this decoder will fail.
     */
    def withAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T1, T2, A => Unit)] = { event =>
      val ack = event.ack.getOrElse {
        throw new SocketIOEventCodecException("Expected ack")
      }
      val args = init(event)
      (args._1, args._2, ack.compose(encoder.encodeArgs))
    }

    /**
     * Combine this decoder with an encoder to encode arguments of an ack function.
     *
     * When the ack is invoked, the argument passed to it will be encoded using the passed in encoder before being sent
     * to the client.
     *
     * The returned value is a tuple of the arguments decoded by this decoder, and the ack function, if supplied by the
     * client.
     */
    def withMaybeAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T1, T2, Option[A => Unit])] = { event =>
      val args = init(event)
      (args._1, args._2, event.ack.map { ack =>
        ack.compose(encoder.encodeArgs)
      })
    }
  }

  /**
   * Implicit conversion to enrich socket io decoders for composition.
   */
  implicit class SocketIOThreeArgDecoderBuilder[T1, T2, T3](init: SocketIOArgsDecoder[(T1, T2, T3)]) {

    /**
     * Combine this four argument decoder with a fourth single argument decoder.
     *
     * The resultant decoder uses all decoders to decode four arguments into a tuple of the four results.
     */
    def and[T4](fourth: SocketIOArgDecoder[T4]): SocketIOArgsDecoder[(T1, T2, T3, T4)] = {
      SocketIOArgsDecoder { args =>
        if (args.length < 4) {
          throw new SocketIOEventCodecException("Needed 4 arguments to decode, but got " + args.length)
        }
        val initArgs = init.decodeArgs(args)
        (initArgs._1, initArgs._2, initArgs._3, fourth.decodeArg(args(3)))
      }
    }

    /**
     * Combine this four argument decoder with a fourth single argument decoder.
     *
     * The resultant decoder uses all decoders to decode four arguments into a tuple of the four results.
     */
    def ~[T4](fourth: SocketIOArgDecoder[T4]): SocketIOArgsDecoder[(T1, T2, T3, T4)] = and(fourth)

    /**
     * Combine this decoder with an encoder to encode arguments of an ack function.
     *
     * When the ack is invoked, the argument passed to it will be encoded using the passed in encoder before being sent
     * to the client.
     *
     * The returned value is a tuple of the arguments decoded by this decoder, and the ack function.
     *
     * If no ack is supplied by the client, this decoder will fail.
     */
    def withAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T1, T2, T3, A => Unit)] = { event =>
      val ack = event.ack.getOrElse {
        throw new SocketIOEventCodecException("Expected ack")
      }
      val args = init(event)
      (args._1, args._2, args._3, ack.compose(encoder.encodeArgs))
    }

    /**
     * Combine this decoder with an encoder to encode arguments of an ack function.
     *
     * When the ack is invoked, the argument passed to it will be encoded using the passed in encoder before being sent
     * to the client.
     *
     * The returned value is a tuple of the arguments decoded by this decoder, and the ack function, if supplied by the
     * client.
     */
    def withMaybeAckEncoder[A](encoder: SocketIOArgsEncoder[A]): SocketIOEventCodec.SocketIOEventDecoder[(T1, T2, T3, Option[A => Unit])] = { event =>
      val args = init(event)
      (args._1, args._2, args._3, event.ack.map { ack =>
        ack.compose(encoder.encodeArgs)
      })
    }
  }

  /**
   * Implicit conversion to enrich socket io encoders for composition.
   */
  implicit class SocketIOSingleArgEncoderBuilder[T](first: SocketIOArgEncoder[T]) {

    /**
     * Combine this single argument encoder with a second single argument encoder.
     *
     * The resultant encoder uses both encoders to encode a tuple into two arguments.
     */
    def and[T1](second: SocketIOArgEncoder[T1]): SocketIOArgsEncoder[(T, T1)] = {
      SocketIOArgsEncoder {
        case (t, t1) => Seq(first.encodeArg(t), second.encodeArg(t1))
      }
    }

    /**
     * Combine this single argument encoder with a second single argument encoder.
     *
     * The resultant encoder uses both encoders to encode a tuple into two arguments.
     */
    def ~[T1](second: SocketIOArgEncoder[T1]): SocketIOArgsEncoder[(T, T1)] = and(second)

    /**
     * Combine this encoder with an decoder to decode arguments of an ack function.
     *
     * When the client invokes the ack, and the ack is received by the server, it will be decoded by this decoder
     * before being passed into the supplied function.
     *
     * The encoded value is a tuple of the argument to be encoded by this encoder, and the ack function.
     */
    def withAckDecoder[A](decoder: SocketIOArgsDecoder[A]): SocketIOEventCodec.SocketIOEventEncoder[(T, A => Unit)] = {
      case (t, ack) =>
        SocketIOEvent.unnamed(Seq(first.encodeArg(t)), Some(SocketIOEventAck.fromScala(ack.compose(decoder.decodeArgs))))
    }
  }

  /**
   * Implicit conversion to enrich socket io encoders for composition.
   */
  implicit class SocketIOTwoArgEncoderBuilder[T1, T2](init: SocketIOArgsEncoder[(T1, T2)]) {

    /**
     * Combine this two argument encoder with a third single argument encoder.
     *
     * The resultant encoder uses all encoders to encode a tuple into three arguments.
     */
    def and[T3](third: SocketIOArgEncoder[T3]): SocketIOArgsEncoder[(T1, T2, T3)] = {
      SocketIOArgsEncoder {
        case (t1, t2, t3) =>
          val initArgs = init.encodeArgs((t1, t2))
          initArgs :+ third.encodeArg(t3)
      }
    }

    /**
     * Combine this two argument encoder with a third single argument encoder.
     *
     * The resultant encoder uses all encoders to encode a tuple into three arguments.
     */
    def ~[T3](third: SocketIOArgEncoder[T3]): SocketIOArgsEncoder[(T1, T2, T3)] = and(third)

    /**
     * Combine this encoder with an decoder to decode arguments of an ack function.
     *
     * When the client invokes the ack, and the ack is received by the server, it will be decoded by this decoder
     * before being passed into the supplied function.
     *
     * The encoded value is a tuple of the arguments to be encoded by this encoder, and the ack function.
     */
    def withAckDecoder[A](decoder: SocketIOArgsDecoder[A]): SocketIOEventCodec.SocketIOEventEncoder[(T1, T2, A => Unit)] = {
      case (t1, t2, ack) =>
        SocketIOEvent.unnamed(init.encodeArgs((t1, t2)), Some(SocketIOEventAck.fromScala(ack.compose(decoder.decodeArgs))))
    }
  }

  /**
   * Implicit conversion to enrich socket io encoders for composition.
   */
  implicit class SocketIOThreeArgEncoderBuilder[T1, T2, T3](init: SocketIOArgsEncoder[(T1, T2, T3)]) {

    /**
     * Combine this three argument encoder with a fourth single argument encoder.
     *
     * The resultant encoder uses all encoders to encode a tuple into four arguments.
     */
    def and[T4](fourth: SocketIOArgEncoder[T4]): SocketIOArgsEncoder[(T1, T2, T3, T4)] = {
      SocketIOArgsEncoder {
        case (t1, t2, t3, t4) =>
          val initArgs = init.encodeArgs((t1, t2, t3))
          initArgs :+ fourth.encodeArg(t4)
      }
    }

    /**
     * Combine this three argument encoder with a fourth single argument encoder.
     *
     * The resultant encoder uses all encoders to encode a tuple into four arguments.
     */
    def ~[T4](fourth: SocketIOArgEncoder[T4]): SocketIOArgsEncoder[(T1, T2, T3, T4)] = and(fourth)

    /**
     * Combine this encoder with an decoder to decode arguments of an ack function.
     *
     * When the client invokes the ack, and the ack is received by the server, it will be decoded by this decoder
     * before being passed into the supplied function.
     *
     * The encoded value is a tuple of the arguments to be encoded by this encoder, and the ack function.
     */
    def withAckDecoder[A](decoder: SocketIOArgsDecoder[A]): SocketIOEventCodec.SocketIOEventEncoder[(T1, T2, T3, A => Unit)] = {
      case (t1, t2, t3, ack) =>
        SocketIOEvent.unnamed(init.encodeArgs((t1, t2, t3)), Some(SocketIOEventAck.fromScala(ack.compose(decoder.decodeArgs))))
    }
  }
}

/**
 * Exception thrown when there was an error encoding or decoding an event.
 */
class SocketIOEventCodecException(message: String) extends RuntimeException(message)

/**
 * A specialisation of [[SocketIOEventCodec.SocketIOEventDecoder]] that just decodes arguments.
 *
 * This allows the codec DSL to enrich implicitly with combinators for building multi argument decoders.
 */
trait SocketIOArgsDecoder[+T] extends SocketIOEventCodec.SocketIOEventDecoder[T] {

  /**
   * Decode the given arguments.
   *
   * @param args The arguments to decode.
   * @return The decoded arguments.
   */
  def decodeArgs(args: Seq[Either[JsValue, ByteString]]): T

  override def apply(event: SocketIOEvent) = decodeArgs(event.arguments)
}

/**
 * Utilities for creating [[SocketIOArgsDecoder]] instances.
 */
object SocketIOArgsDecoder {

  /**
   * Create an args decoder from the given function.
   */
  def apply[T](decoder: Seq[Either[JsValue, ByteString]] => T): SocketIOArgsDecoder[T] = new SocketIOArgsDecoder[T] {
    override def decodeArgs(args: Seq[Either[JsValue, ByteString]]) = decoder(args)
  }
}

/**
 * A specialisation of [[SocketIOArgsDecoder]] that only decodes a single argument.
 *
 * This allows the codec DSL to enrich implicitly with combinators for building multiple argument decoders.
 */
trait SocketIOArgDecoder[+T] extends SocketIOArgsDecoder[T] {

  /**
   * Decode the given argument.
   *
   * @param arg The argument to decode.
   * @return The decoded argument.
   */
  def decodeArg(arg: Either[JsValue, ByteString]): T

  override def decodeArgs(args: Seq[Either[JsValue, ByteString]]): T = {
    decodeArg(args.headOption.getOrElse {
      throw new SocketIOEventCodecException("No arguments found to decode")
    })
  }
}

/**
 * Convenience functions for creating [[SocketIOArgDecoder]] instances.
 */
object SocketIOArgDecoder {

  /**
   * Create a decoder from the given decode function.
   */
  def apply[T](decode: Either[JsValue, ByteString] => T): SocketIOArgDecoder[T] = new SocketIOArgDecoder[T] {
    override def decodeArg(arg: Either[JsValue, ByteString]) = decode(arg)
  }

  /**
   * Create a JSON decoder.
   */
  def json[T: Reads]: SocketIOArgDecoder[T] = apply {
    case Left(jsValue) => jsValue.as[T]
    case Right(bytes)  => Json.parse(bytes.toArray).as[T]
  }
}

/**
 * A specialisation of [[SocketIOEventCodec.SocketIOEventEncoder]] that just encodes arguments.
 *
 * This allows the codec DSL to enrich implicitly with combinators for building multi argument encoders.
 */
trait SocketIOArgsEncoder[-T] extends SocketIOEventCodec.SocketIOEventEncoder[T] {

  /**
   * Encode the given event to arguments.
   *
   * @param t The event to encode.
   * @return The encoded arguments.
   */
  def encodeArgs(t: T): Seq[Either[JsValue, ByteString]]

  override def apply(t: T) = SocketIOEvent.unnamed(encodeArgs(t), None)
}

/**
 * Convenience functions for creating [[SocketIOArgsEncoder]] instances.
 */
object SocketIOArgsEncoder {

  /**
   * Create an args encoder from the given function.
   */
  def apply[T](encode: T => Seq[Either[JsValue, ByteString]]): SocketIOArgsEncoder[T] = new SocketIOArgsEncoder[T] {
    override def encodeArgs(t: T) = encode(t)
  }
}

/**
 * A specialisation of [[SocketIOArgsEncoder]] that only encodes a single argument.
 *
 * This allows the codec DSL to enrich implicitly with combinators for building multiple argument encoders.
 */
trait SocketIOArgEncoder[-T] extends SocketIOArgsEncoder[T] {

  /**
   * Encode the given argument.
   *
   * @param t The argument to encode.
   * @return The encoded argument.
   */
  def encodeArg(t: T): Either[JsValue, ByteString]

  override def encodeArgs(t: T) = Seq(encodeArg(t))
}

/**
 * Convenience functions for creating [[SocketIOArgEncoder]] instances.
 */
object SocketIOArgEncoder {

  /**
   * Create an arg encoder from the given function.
   */
  def apply[T](encoder: T => Either[JsValue, ByteString]): SocketIOArgEncoder[T] = new SocketIOArgEncoder[T] {
    override def encodeArg(t: T) = encoder(t)
  }

  /**
   * Create a json arg encoder.
   */
  def json[T: Writes]: SocketIOArgEncoder[T] = apply { t =>
    Left(Json.toJson(t))
  }
}