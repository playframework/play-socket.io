/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.scaladsl

import akka.util.ByteString
import org.scalatest.OptionValues
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.socketio.SocketIOEvent
import play.socketio.SocketIOEventAck
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SocketIOEventCodecSpec extends AnyWordSpec with Matchers with OptionValues {

  import play.socketio.scaladsl.SocketIOEventCodec._

  private class CapturingAck extends SocketIOEventAck {
    var args: Option[Seq[Either[JsValue, ByteString]]]         = None
    override def apply(args: Seq[Either[JsValue, ByteString]]) = this.args = Some(args)
  }

  def decodeStringArgs[T](
      decoder: SocketIOEventDecoder[T],
      args: Seq[String],
      ack: Option[SocketIOEventAck] = None
  ): T = {
    decoder(SocketIOEvent.unnamed(stringsToArgs(args: _*), ack))
  }

  def stringsToArgs(args: String*): Seq[Either[JsValue, ByteString]] = {
    args.map(s => Left(JsString(s)))
  }

  "The socket.io event codec DSL" should {

    "allow decoding single argument events" in {
      decodeStringArgs(decodeJson[String], Seq("one")) should ===("one")
    }

    "allow decoding single argument events with ack" in {
      val capture = new CapturingAck
      val (result, ack) =
        decodeStringArgs(decodeJson[String].withAckEncoder(encodeJson[String]), Seq("one"), Some(capture))
      result should ===("one")
      ack("ack")
      capture.args.value should ===(stringsToArgs("ack"))
    }

    "allow decoding single argument events with multi argument acks" in {
      val capture = new CapturingAck
      val (result, ack) = decodeStringArgs(
        decodeJson[String].withAckEncoder(encodeJson[String] ~ encodeJson[String]),
        Seq("one"),
        Some(capture)
      )
      result should ===("one")
      ack(("ack1", "ack2"))
      capture.args.value should ===(stringsToArgs("ack1", "ack2"))
    }

    "allow decoding two argument events" in {
      decodeStringArgs(decodeJson[String] ~ decodeJson[String], Seq("one", "two")) should ===(("one", "two"))
    }

    "allow decoding two argument events with ack" in {
      val capture = new CapturingAck
      val (arg1, arg2, ack) = decodeStringArgs(
        (decodeJson[String] ~ decodeJson[String]).withAckEncoder(encodeJson[String]),
        Seq("one", "two"),
        Some(capture)
      )
      (arg1, arg2) should ===(("one", "two"))
      ack("ack")
      capture.args.value should ===(stringsToArgs("ack"))
    }

    "allow decoding three argument events" in {
      decodeStringArgs(decodeJson[String] ~ decodeJson[String] ~ decodeJson[String], Seq("one", "two", "three")) should ===(
        ("one", "two", "three")
      )
    }

    "allow decoding three argument events with ack" in {
      val capture = new CapturingAck
      val (arg1, arg2, arg3, ack) = decodeStringArgs(
        (decodeJson[String] ~ decodeJson[String] ~ decodeJson[String]).withAckEncoder(encodeJson[String]),
        Seq("one", "two", "three"),
        Some(capture)
      )
      (arg1, arg2, arg3) should ===(("one", "two", "three"))
      ack("ack")
      capture.args.value should ===(stringsToArgs("ack"))
    }

    "allow decoding four argument events" in {
      decodeStringArgs(
        decodeJson[String] ~ decodeJson[String] ~ decodeJson[String] ~ decodeJson[String],
        Seq("one", "two", "three", "four")
      ) should ===(("one", "two", "three", "four"))
    }

    "allow encoding single argument events" in {
      encodeJson[String].apply("one").arguments should ===(stringsToArgs("one"))
    }

    "allow encoding single argument events with ack" in {
      var arg: Option[String] = None
      val event = encodeJson[String]
        .withAckDecoder(decodeJson[String])
        .apply(("one", a => arg = Some(a)))

      event.arguments should ===(stringsToArgs("one"))
      event.ack.value(stringsToArgs("ack"))
      arg.value should ===("ack")
    }

    "allow encoding single argument events with multi argument acks" in {
      var arg: Option[(String, String)] = None
      val event = encodeJson[String]
        .withAckDecoder(decodeJson[String] ~ decodeJson[String])
        .apply(("one", a => arg = Some(a)))

      event.arguments should ===(stringsToArgs("one"))
      event.ack.value(stringsToArgs("ack1", "ack2"))
      arg.value should ===(("ack1", "ack2"))
    }

    "allow encoding two argument events" in {
      (encodeJson[String] ~ encodeJson[String])
        .apply(("one", "two"))
        .arguments should ===(stringsToArgs("one", "two"))
    }

    "allow encoding two argument events with ack" in {
      var arg: Option[String] = None
      val event = (encodeJson[String] ~ encodeJson[String])
        .withAckDecoder(decodeJson[String])
        .apply(("one", "two", a => arg = Some(a)))

      event.arguments should ===(stringsToArgs("one", "two"))
      event.ack.value(stringsToArgs("ack"))
      arg.value should ===("ack")
    }

    "allow encoding three argument events" in {
      (encodeJson[String] ~ encodeJson[String] ~ encodeJson[String])
        .apply(("one", "two", "three"))
        .arguments should ===(stringsToArgs("one", "two", "three"))
    }

    "allow encoding three argument events with ack" in {
      var arg: Option[String] = None
      val event = (encodeJson[String] ~ encodeJson[String] ~ encodeJson[String])
        .withAckDecoder(decodeJson[String])
        .apply(("one", "two", "three", a => arg = Some(a)))

      event.arguments should ===(stringsToArgs("one", "two", "three"))
      event.ack.value(stringsToArgs("ack"))
      arg.value should ===("ack")
    }

    "allow encoding four argument events" in {
      (encodeJson[String] ~ encodeJson[String] ~ encodeJson[String] ~ encodeJson[String])
        .apply(("one", "two", "three", "four"))
        .arguments should ===(stringsToArgs("one", "two", "three", "four"))
    }

    "allow combining decoders by name" in {
      val decoder = decodeByName {
        case "foo" => decodeJson[String].andThen(s => "foo: " + s)
        case "bar" => decodeJson[String].andThen(s => "bar: " + s)
      }

      decoder(SocketIOEvent("foo", stringsToArgs("arg"), None)) should ===("foo: arg")
      decoder(SocketIOEvent("bar", stringsToArgs("arg"), None)) should ===("bar: arg")
    }

    "allow combining encoders by type" in {
      val encoder = encodeByType[Any] {
        case _: String => "string" -> encodeJson[String]
        case _: Int =>
          "int" -> encodeJson[String].compose { i: Int =>
            i.toString
          }
      }

      val e1 = encoder("arg")
      e1.name should ===("string")
      e1.arguments should ===(stringsToArgs("arg"))
      val e2 = encoder(20)
      e2.name should ===("int")
      e2.arguments should ===(stringsToArgs("20"))
    }

    "allow mapping decoders" in {
      val decoder = (decodeJson[String] ~ decodeJson[String]).andThen { case (a, b) => s"$a:$b" }
      decodeStringArgs(decoder, Seq("one", "two")) should ===("one:two")
    }

    "allow composing encoders" in {
      case class Args(a: String, b: String)
      val encoder = (encodeJson[String] ~ encodeJson[String]).compose[Args] { case Args(a, b) => (a, b) }
      // This assignment is purely to ensure that the Scala compiler correctly inferred the type of encoder
      val checkTypeInference: SocketIOEventEncoder[Args] = encoder
      checkTypeInference(Args("one", "two")).arguments should ===(stringsToArgs("one", "two"))
    }
  }

}
