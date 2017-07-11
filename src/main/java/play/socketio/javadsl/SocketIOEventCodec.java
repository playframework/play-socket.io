package play.socketio.javadsl;

import akka.NotUsed;
import akka.japi.Pair;
import akka.stream.javadsl.BidiFlow;
import akka.stream.javadsl.Flow;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import play.api.libs.json.*;
import play.libs.F;
import play.socketio.SocketIOEvent;
import play.socketio.SocketIOEventAck;
import scala.Option;
import scala.Some;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import scala.compat.java8.OptionConverters;
import scala.runtime.BoxedUnit;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public abstract class SocketIOEventCodec<In, Out> {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final List<Pair<Predicate<SocketIOEvent>, EventDecoder<? extends In>>> decoders = new ArrayList<>();
  private final List<Pair<Predicate<? extends Out>, EventEncoder<? extends Out>>> encoders = new ArrayList<>();

  protected final <T extends In> SocketIOEventCodec<In, Out> addDecoder(String name, EventDecoder<T> decoder) {
    return addDecoder(event -> event.name().equals(name), decoder);
  }

  protected final <T extends In> SocketIOEventCodec<In, Out> addDecoder(Predicate<SocketIOEvent> useFor, EventDecoder<T> decoder) {
    decoders.add(Pair.create(useFor, decoder));
    return this;
  }

  protected final <T extends Out> SocketIOEventCodec<In, Out> addEncoder(String name, Class<T> clazz, EventEncoder<T> encoder) {
    return addEncoder(name, clazz::isInstance, encoder);
  }

  protected final <T extends Out> SocketIOEventCodec<In, Out> addEncoder(String name, Predicate<T> useFor, EventEncoder<T> encoder) {
    return addEncoder(useFor, encoder.withName(name));
  }

  protected final <T extends Out> SocketIOEventCodec<In, Out> addEncoder(Predicate<T> useFor, NamedEventEncoder<T> encoder) {
    encoders.add(Pair.create(useFor, encoder));
    return this;
  }

  public final BidiFlow<SocketIOEvent, In, Out, SocketIOEvent, NotUsed> createFlow() {
    return doCreateFlow(ImmutableList.copyOf(this.decoders), ImmutableList.copyOf(this.encoders));
  }

  private static <In, Out> BidiFlow<SocketIOEvent, In, Out, SocketIOEvent, NotUsed> doCreateFlow(
      List<Pair<Predicate<SocketIOEvent>, EventDecoder<? extends In>>> decoders,
      List<Pair<Predicate<? extends Out>, EventEncoder<? extends Out>>> encoders
  ) {
    // Implemented in a static method to ensure we don't accidentally close over any mutable state.

    Flow<SocketIOEvent, In, NotUsed> decodeFlow = Flow.<SocketIOEvent>create().map( event -> {
      Optional<Pair<Predicate<SocketIOEvent>, EventDecoder<? extends In>>> decoderPair =
          decoders.stream().filter(pair -> pair.first().test(event)).findFirst();

      return decoderPair.map(pair -> pair.second().decodeEvent(event)).orElseGet(() -> {
        throw new RuntimeException("No decoder found to handle event named " + event.name());
      });
    });

    Flow<Out, SocketIOEvent, NotUsed> encodeFlow = Flow.<Out>create().map( event -> {
      Optional<Pair<Predicate<? extends Out>, EventEncoder<? extends Out>>> encoderPair =
          encoders.stream().filter(pair -> ((Predicate<Out>) pair.first()).test(event)).findFirst();

      return encoderPair.map(pair -> ((EventEncoder<Out>) pair.second()).encodeEvent(event)).orElseGet(() -> {
        throw new RuntimeException("No decoder found to handle event " + event);
      });
    });

    return BidiFlow.fromFlows(decodeFlow, encodeFlow);
  }

  protected <T> SingleArgumentDecoder<T> decodeJson(Class<T> clazz) {
    ObjectReader reader = objectMapper.readerFor(clazz);
    return arg -> {
      try {
        if (arg.isLeft()) {
          // Note - IntelliJ is not very good at working out what the Java signatures produced by Scala objects
          // are, especially when they are nested, so this will be red in IntelliJ, but does compile.
          JsonNode jsonNode = Reads.JsonNodeReads().reads(arg.left().get()).get();
          return reader.readValue(jsonNode);
        } else {
          return reader.readValue(arg.right().get().toArray());
        }
      } catch (IOException e) {
        throw new EventCodecException("Error decoding value", e);
      }

    };
  }

  protected SingleArgumentDecoder<ByteString> decodeBytes() {
    return arg -> arg.right().get();
  }

  protected <T> SingleArgumentEncoder<T> encodeJson() {
    return arg -> {
      JsonNode jsonNode = objectMapper.valueToTree(arg);
      // Note - IntelliJ is not very good at working out what the Java signatures produced by Scala objects
      // are, especially when they are nested, so this will be red in IntelliJ, but does compile.
      return Left.apply(Writes.JsonNodeWrites().writes(jsonNode));
    };
  }

  protected SingleArgumentEncoder<ByteString> encodeBytes() {
    return Right::apply;
  }

  protected interface EventDecoder<T> {
    T decodeEvent(SocketIOEvent event);

    default <U> EventDecoder<U> map(Function<? super T, ? extends U> f) {
      return event -> f.apply(decodeEvent(event));
    }
  }

  protected interface MultiArgumentDecoder<T> extends EventDecoder<T> {
    T decodeArgs(Seq<Either<JsValue, ByteString>> args);
    default T decodeEvent(SocketIOEvent event) {
      return decodeArgs(event.arguments());
    }
  }

  protected interface SingleArgumentDecoder<T> extends MultiArgumentDecoder<T> {
    T decodeArg(Either<JsValue, ByteString> arg);

    default <T2> TwoArgumentDecoder<T, T2> and(SingleArgumentDecoder<T2> decoder) {
      return args -> {
        if (args.size() < 2) {
          throw new EventCodecException("Needed two arguments to decode, but only found " + args.size());
        } else {
          return Pair.create(decodeArg(args.apply(0)), decoder.decodeArg(args.apply(1)));
        }
      };
    }

    default <A> EventDecoder<Pair<T, Optional<Consumer<A>>>> withMaybeAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        return Pair.create(decodeEvent(event), OptionConverters.toJava(event.ack()).map(ack -> {
          return a -> ack.apply(encoder.encodeArgs(a));
        }));
      };
    }

    default <A> EventDecoder<Pair<T, Consumer<A>>> withAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        SocketIOEventAck ack = OptionConverters.toJava(event.ack()).orElseGet(() -> {
          throw new EventCodecException("Expected ack");
        });
        return Pair.create(decodeEvent(event), a -> ack.apply(encoder.encodeArgs(a)));
      };
    }

    default T decodeArgs(Seq<Either<JsValue, ByteString>> args) {
      if (args.size() < 1) {
        throw new EventCodecException("Tried to decode 1 argument, but there were no arguments to decode");
      } else {
        return decodeArg(args.head());
      }
    }
  }

  protected interface TwoArgumentDecoder<T1, T2> extends MultiArgumentDecoder<Pair<T1, T2>> {
    default <T3> ThreeArgumentDecoder<T1, T2, T3> and(SingleArgumentDecoder<T3> decoder) {
      return args -> {
        if (args.size() < 3) {
          throw new EventCodecException("Needed 3 arguments to decode, but only found " + args.size());
        } else {
          Pair<T1, T2> pair = decodeArgs(args);
          return new F.Tuple3<>(pair.first(), pair.second(), decoder.decodeArg(args.apply(2)));
        }
      };
    }

    default <A> EventDecoder<F.Tuple3<T1, T2, Optional<Consumer<A>>>> withMaybeAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        Pair<T1, T2> pair = decodeEvent(event);
        return new F.Tuple3<>(pair.first(), pair.second(), OptionConverters.toJava(event.ack()).map(ack -> {
          return a -> ack.apply(encoder.encodeArgs(a));
        }));
      };
    }

    default <A> EventDecoder<F.Tuple3<T1, T2, Consumer<A>>> withAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        SocketIOEventAck ack = OptionConverters.toJava(event.ack()).orElseGet(() -> {
          throw new EventCodecException("Expected ack");
        });
        Pair<T1, T2> pair = decodeEvent(event);
        return new F.Tuple3<>(pair.first(), pair.second(), a -> ack.apply(encoder.encodeArgs(a)));
      };
    }
  }

  protected interface ThreeArgumentDecoder<T1, T2, T3> extends MultiArgumentDecoder<F.Tuple3<T1, T2, T3>> {
    default <T4> MultiArgumentDecoder<F.Tuple4<T1, T2, T3, T4>> and(SingleArgumentDecoder<T4> decoder) {
      return args -> {
        if (args.size() < 4) {
          throw new EventCodecException("Needed 4 arguments to decode, but only found " + args.size());
        } else {
          F.Tuple3<T1, T2, T3> tuple = decodeArgs(args);
          return new F.Tuple4<>(tuple._1, tuple._2, tuple._3, decoder.decodeArg(args.apply(1)));
        }
      };
    }

    default <A> EventDecoder<F.Tuple4<T1, T2, T3, Optional<Consumer<A>>>> withMaybeAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        F.Tuple3<T1, T2, T3> tuple = decodeEvent(event);
        return new F.Tuple4<>(tuple._1, tuple._2, tuple._3, OptionConverters.toJava(event.ack()).map(ack -> {
          return a -> ack.apply(encoder.encodeArgs(a));
        }));
      };
    }

    default <A> EventDecoder<F.Tuple4<T1, T2, T3, Consumer<A>>> withAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        SocketIOEventAck ack = OptionConverters.toJava(event.ack()).orElseGet(() -> {
          throw new EventCodecException("Expected ack");
        });
        F.Tuple3<T1, T2, T3> tuple = decodeEvent(event);
        return new F.Tuple4<>(tuple._1, tuple._2, tuple._3, a -> ack.apply(encoder.encodeArgs(a)));
      };
    }
  }

  protected interface NamedEventEncoder<T> extends EventEncoder<T> {}

  protected interface EventEncoder<T> {
    SocketIOEvent encodeEvent(T t);

    default <U> EventEncoder<U> contramap(Function<? super U, ? extends T> f) {
      return u -> encodeEvent(f.apply(u));
    }

    default NamedEventEncoder<T> withName(String name) {
      return t -> {
        SocketIOEvent event = encodeEvent(t);
        return new SocketIOEvent(name, event.arguments(), event.ack());
      };
    }
  }

  protected interface MultiArgumentEncoder<T> extends EventEncoder<T> {
    Seq<Either<JsValue, ByteString>> encodeArgs(T t);
    default SocketIOEvent encodeEvent(T t) {
      return SocketIOEvent.unnamed(encodeArgs(t), Option.apply(null));
    }
  }

  protected interface SingleArgumentEncoder<T> extends MultiArgumentEncoder<T> {
    Either<JsValue, ByteString> encodeArg(T t);

    default <T2> TwoArgumentEncoder<T, T2> and(SingleArgumentEncoder<T2> encoder) {
      return args -> {
        return JavaConverters.asScalaBuffer(Arrays.asList(encodeArg(args.first()), encoder.encodeArg(args.second())));
      };
    }

    default Seq<Either<JsValue, ByteString>> encodeArgs(T t) {
      return JavaConverters.asScalaBuffer(Collections.singletonList(encodeArg(t)));
    }

    default <A> EventEncoder<Pair<T, Consumer<A>>> withAckDecoder(MultiArgumentDecoder<A> decoder) {
      return pair -> {
        return SocketIOEvent.unnamed(encodeArgs(pair.first()), Some.apply(args -> {
          pair.second().accept(decoder.decodeArgs(args));
          return BoxedUnit.UNIT;
        }));
      };
    }
  }

  protected interface TwoArgumentEncoder<T1, T2> extends MultiArgumentEncoder<Pair<T1, T2>> {
    default <T3> ThreeArgumentEncoder<T1, T2, T3> and(SingleArgumentEncoder<T3> encoder) {
      return args -> {
        Seq<Either<JsValue, ByteString>> init = encodeArgs(Pair.create(args._1, args._2));
        List<Either<JsValue, ByteString>> result = new ArrayList<>(JavaConverters.asJavaCollection(init));
        result.add(encoder.encodeArg(args._3));
        return JavaConverters.asScalaBuffer(result);
      };
    }

    default <A> EventEncoder<F.Tuple3<T1, T2, Consumer<A>>> withAckDecoder(MultiArgumentDecoder<A> decoder) {
      return tuple -> {
        return SocketIOEvent.unnamed(encodeArgs(Pair.create(tuple._1, tuple._2)), Some.apply(args -> {
          tuple._3.accept(decoder.decodeArgs(args));
          return BoxedUnit.UNIT;
        }));
      };
    }
  }

  protected interface ThreeArgumentEncoder<T1, T2, T3> extends MultiArgumentEncoder<F.Tuple3<T1, T2, T3>> {
    default <T4> MultiArgumentEncoder<F.Tuple4<T1, T2, T3, T4>> and(SingleArgumentEncoder<T4> encoder) {
      return args -> {
        Seq<Either<JsValue, ByteString>> init = encodeArgs(new F.Tuple3<>(args._1, args._2, args._3));
        List<Either<JsValue, ByteString>> result = new ArrayList<>(JavaConverters.asJavaCollection(init));
        result.add(encoder.encodeArg(args._4));
        return JavaConverters.asScalaBuffer(result);
      };
    }

    default <A> EventEncoder<F.Tuple4<T1, T2, T3, Consumer<A>>> withAckDecoder(MultiArgumentDecoder<A> decoder) {
      return tuple -> {
        return SocketIOEvent.unnamed(encodeArgs(new F.Tuple3<>(tuple._1, tuple._2, tuple._3)), Some.apply(args -> {
          tuple._4.accept(decoder.decodeArgs(args));
          return BoxedUnit.UNIT;
        }));
      };
    }
  }

  public static class EventCodecException extends RuntimeException {
    public EventCodecException(String message) {
      super(message);
    }

    public EventCodecException(String message, Throwable cause) {
      super(message, cause);
    }
  }

}
