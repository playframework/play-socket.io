/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */

package play.socketio;

import akka.util.ByteString;
import play.api.libs.json.JsValue;
import scala.Function1;
import scala.Unit;
import scala.collection.Seq;
import scala.runtime.AbstractFunction1;
import scala.util.Either;

/**
 * A socket.io ack function.
 *
 * This is defined in Java so that it can be a functional interface in Scala 2.11.
 */
public interface SocketIOEventAck {
  void apply(Seq<Either<JsValue, ByteString>> args);

  default <T> Function1<T, Unit> compose(Function1<T, Seq<Either<JsValue, ByteString>>> f) {
    return new AbstractFunction1<T, Unit>() {
      @Override
      public Unit apply(T t) {
        SocketIOEventAck.this.apply(f.apply(t));
        return null;
      }
    };
  }

  /**
   * Convenience to create an ack function from Scala.
   */
  static SocketIOEventAck fromScala(Function1<Seq<Either<JsValue, ByteString>>, Unit> f) {
    return f::apply;
  }
}
