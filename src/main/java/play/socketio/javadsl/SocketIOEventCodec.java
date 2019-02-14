/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */

package play.socketio.javadsl;

import akka.NotUsed;
import akka.japi.Pair;
import akka.stream.javadsl.BidiFlow;
import akka.stream.javadsl.Flow;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.collect.ImmutableList;
import play.api.libs.json.*;
import play.libs.F;
import play.socketio.SocketIOEvent;
import play.socketio.SocketIOEventAck;
import scala.Option;
import scala.Some;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import scala.collection.immutable.List$;
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

/**
 * Class for creating socket.io codecs.
 *
 * Typically this class should by extended, and used to build the codecs. Example use:
 *
 * <pre>
 *   public class MyCodec extends SocketIOEventCodec&lt;String, String&gt; {
 *     {
 *       addDecoder("chat message", decodeJson(String.class));
 *       addEncoder("chat message", String.class, encodeJson());
 *     }
 *   }
 * </pre>
 */
public class SocketIOEventCodec<In, Out> {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<Pair<Predicate<SocketIOEvent>, EventDecoder<? extends In>>> decoders = new ArrayList<>();
  private final List<Pair<Predicate<? extends Out>, EventEncoder<? extends Out>>> encoders = new ArrayList<>();

  /**
   * Add a decoder to this codec.
   *
   * @param name The name of the event that this decoder decodes.
   * @param decoder The decoder to add.
   * @return This codec builder for fluent invocation.
   */
  public final <T extends In> SocketIOEventCodec<In, Out> addDecoder(String name, EventDecoder<T> decoder) {
    return addDecoder(event -> event.name().equals(name), decoder);
  }

  /**
   * Add a decoder to this codec.
   *
   * This is a more powerful version of {@link #addDecoder(String, EventDecoder)}, allowing you to not just match
   * events on the name, but based on any predicate on the event.
   *
   * @param useFor A predicate to match events. If the predicate matches, the given decoder will be used.
   * @param decoder The decoder to add.
   * @return This codec builder for fluent invocation.
   */
  public final <T extends In> SocketIOEventCodec<In, Out> addDecoder(Predicate<SocketIOEvent> useFor, EventDecoder<T> decoder) {
    decoders.add(Pair.create(useFor, decoder));
    return this;
  }

  /**
   * Add an encoder to this codec.
   *
   * This variant of the method matches events to encode if they are an instance of the passed in class. Note that due
   * to type erasure, only the direct runtime class of the objects are matched, no generic parameters are matched.
   *
   * If the passed in encoder produces events with a name, that name will be overridden by the name passed in here.
   *
   * @param name The name of the event being encoded.
   * @param clazz The class of the event being encoded, used to match which events should be encoded.
   * @param encoder The encoder to add.
   * @return This codec builder for fluent invocation.
   */
  public final <T extends Out> SocketIOEventCodec<In, Out> addEncoder(String name, Class<T> clazz, EventEncoder<T> encoder) {
    return addEncoder(name, clazz::isInstance, encoder);
  }

  /**
   * Add an encoder to this codec.
   *
   * This variant of the method uses a predicate to match events, allowing, for example, the types of values inside
   * tuples to be matched.
   *
   * If the passed in encoder produces events with a name, that name will be overridden by the name passed in here.
   *
   * @param name The name of the event being encoded.
   * @param useFor A predicate to match events. If the predicate matches, the given encoder will be used.
   * @param encoder The encoder to add.
   * @return This codec builder for fluent invocation.
   */
  public final <T extends Out> SocketIOEventCodec<In, Out> addEncoder(String name, Predicate<T> useFor, EventEncoder<T> encoder) {
    return addEncoder(useFor, encoder.withName(name));
  }

  /**
   * Add an encoder to this codec.
   *
   * This variant of the method uses a predicate to match events, allowing, for example, the types of values inside
   * tuples to be matched.
   *
   * It also takes {@link NamedEventEncoder} instances, rather than just {@link EventEncoder}, and so expects that the
   * passed in encoder has named the event correctly.
   *
   * @param useFor A predicate to match events. If the predicate matches, the given encoder will be used.
   * @param encoder The encoder to add.
   * @return This codec builder for fluent invocation.
   */
  public final <T extends Out> SocketIOEventCodec<In, Out> addEncoder(Predicate<T> useFor, NamedEventEncoder<T> encoder) {
    encoders.add(Pair.create(useFor, encoder));
    return this;
  }

  /**
   * Create the flow to decode and encode events, using the configured decoders and encoders.
   *
   * The bidi flow can be joined by a <code>Flow&lt;In, Out, ?&gt;</code> to produce a
   * <code>Flow&lt;<SocketIOEvent, SocketIOEvent, ?&gt;</code> for handling and producing socket.io events.
   *
   * Any decoders or encoders added to this codec after this method is invoked will not be used by the returned flow.
   *
   * @return A {@link BidiFlow} for decoding and encoding {@link SocketIOEvent} streams.
   */
  public final BidiFlow<SocketIOEvent, In, Out, SocketIOEvent, NotUsed> createFlow() {
    return doCreateFlow(ImmutableList.copyOf(this.decoders), ImmutableList.copyOf(this.encoders));
  }

  @SuppressWarnings("unchecked")
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

  /**
   * Decode a single argument as JSON.
   */
  public <T> SingleArgumentDecoder<T> decodeJson(Class<T> clazz) {
    ObjectReader reader = objectMapper.readerFor(clazz);
    return arg -> {
      try {
        if (arg.isLeft()) {
          // Note - IntelliJ is not very good at working out what the Java signatures produced by Scala objects
          // are, especially when they are nested, so this will be red in IntelliJ, but does compile.
          JsonNode jsonNode = Reads$.MODULE$.JsonNodeReads().reads(arg.left().get()).get();
          return reader.readValue(jsonNode);
        } else {
          return reader.readValue(arg.right().get().toArray());
        }
      } catch (IOException e) {
        throw new EventCodecException("Error decoding value", e);
      }

    };
  }

  /**
   * Decode a single argument as a binary message.
   */
  public SingleArgumentDecoder<ByteString> decodeBytes() {
    return arg -> arg.right().get();
  }

  /**
   * Decode no arguments.
   */
  public MultiArgumentDecoder<NotUsed> decodeNoArgs() {
    return args -> NotUsed.getInstance();
  }

  /**
   * Encode a single argument as a JSON.
   */
  public <T> SingleArgumentEncoder<T> encodeJson() {
    return arg -> {
      JsonNode jsonNode = objectMapper.valueToTree(arg);
      // Note - IntelliJ is not very good at working out what the Java signatures produced by Scala objects
      // are, especially when they are nested, so this will be red in IntelliJ, but does compile.
      return Left.apply(Writes$.MODULE$.JsonNodeWrites().writes(jsonNode));
    };
  }

  /**
   * Encode a single argument as a binary message.
   */
  public SingleArgumentEncoder<ByteString> encodeBytes() {
    return Right::apply;
  }

  /**
   * Encode no arguments.
   */
  public MultiArgumentEncoder<NotUsed> encodeNoArgs() {
    return notUsed -> List$.MODULE$.empty();
  }

  /**
   * An event decoder.
   */
  public interface EventDecoder<T> {

    /**
     * Decode the given {@link SocketIOEvent} into a <code>T</code>.
     *
     * @param event The event to decode.
     * @return The decoded event.
     */
    T decodeEvent(SocketIOEvent event);

    /**
     * Map this event decoder to a decoder of another type.
     *
     * This produces a new {@link EventDecoder} that uses the passed in function to convert the result of this event
     * decoder into the result of the new event decoder.
     *
     * @param f The map function.
     * @return The new event decoder.
     */
    default <U> EventDecoder<U> map(Function<? super T, ? extends U> f) {
      return event -> f.apply(decodeEvent(event));
    }
  }

  /**
   * An event decoder that decodes multiple arguments.
   *
   * Primarily a convenience interface to provide an implementation of <code>decodeEvent</code> that extracts the
   * arguments and passes them to <code>decodeArgs</code>.
   */
  public interface MultiArgumentDecoder<T> extends EventDecoder<T> {

    /**
     * Decode the given arguments.
     *
     * @param args The arguments to decode.
     * @return The decoded event.
     */
    T decodeArgs(Seq<Either<JsValue, ByteString>> args);

    @Override
    default T decodeEvent(SocketIOEvent event) {
      return decodeArgs(event.arguments());
    }
  }

  /**
   * An event decoder that just decodes a single argument.
   *
   * This decoder implements both {@link MultiArgumentDecoder} and {@link EventDecoder}. If passed a list of arguments
   * via the <code>decodeArgs</code> method or an event by the <code>decodeEvent</code> method, it will decode the
   * first argument, and fail if there is no argument.
   *
   * It can be used as a whole event decoder for events that are expected to only have one argument, or it can be
   * combined with other decoders to allow decoding multiple arguments, via the <code>and</code> method.
   */
  public interface SingleArgumentDecoder<T> extends MultiArgumentDecoder<T> {

    /**
     * Decode the given argument.
     *
     * @param arg The argument, either a JSON tree or a binary {@link ByteString}
     * @return The decoded argument.
     */
    T decodeArg(Either<JsValue, ByteString> arg);

    /**
     * Combine this decoder with another decoder to create a decoder that decodes two arguments.
     *
     * @param decoder The decoder for the second argument.
     * @return A decoder that decodes two arguments into a {@link Pair} of arguments.
     */
    default <T2> TwoArgumentDecoder<T, T2> and(SingleArgumentDecoder<T2> decoder) {
      return args -> {
        if (args.size() < 2) {
          throw new EventCodecException("Needed two arguments to decode, but only found " + args.size());
        } else {
          return Pair.create(decodeArg(args.apply(0)), decoder.decodeArg(args.apply(1)));
        }
      };
    }

    /**
     * Combine this decoder with an argument encoder to create a decoder that decodes events that may have acks.
     *
     * The passed in encoder will be used if the ack is invoked to encode its arguments before sending them to the
     * client.
     *
     * @param encoder The encoder to encode the ack arguments with.
     * @return A decoder that decodes a single argument and an ack function into a {@link Pair} of the decoded argument
     *         and ack function.
     */
    default <A> EventDecoder<Pair<T, Optional<Consumer<A>>>> withMaybeAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        return Pair.create(decodeEvent(event), OptionConverters.toJava(event.ack()).map(ack -> {
          return a -> ack.apply(encoder.encodeArgs(a));
        }));
      };
    }

    /**
     * Combine this decoder with an argument encoder to create a decoder that decodes events that must have acks.
     *
     * The passed in encoder will be used if the ack is invoked to encode its arguments before sending them to the
     * client.
     *
     * This decoder will fail if the event it decodes does not have an ack attached to it.
     *
     * @param encoder The encoder to encode the ack arguments with.
     * @return A decoder that decodes a single argument and an ack function into a {@link Pair} of the decoded argument
     *         and ack function.
     */
    default <A> EventDecoder<Pair<T, Consumer<A>>> withAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        SocketIOEventAck ack = OptionConverters.toJava(event.ack()).orElseGet(() -> {
          throw new EventCodecException("Expected ack");
        });
        return Pair.create(decodeEvent(event), a -> ack.apply(encoder.encodeArgs(a)));
      };
    }

    @Override
    default T decodeArgs(Seq<Either<JsValue, ByteString>> args) {
      if (args.size() < 1) {
        throw new EventCodecException("Tried to decode 1 argument, but there were no arguments to decode");
      } else {
        return decodeArg(args.head());
      }
    }
  }

  /**
   * An event decoder that decodes two arguments.
   *
   * This decoder implements both {@link MultiArgumentDecoder} and {@link EventDecoder}. If passed a list of arguments
   * via the <code>decodeArgs</code> method or an event by the <code>decodeEvent</code> method, it will decode the
   * first and second arguments, and fail if there are less than two arguments.
   *
   * It can be used as a whole event decoder for events that are expected to only have two arguments, or it can be
   * combined with other decoders to allow decoding more arguments, via the <code>and</code> method.
   */
  public interface TwoArgumentDecoder<T1, T2> extends MultiArgumentDecoder<Pair<T1, T2>> {

    /**
     * Combine this decoder with another decoder to create a decoder that decodes three arguments.
     *
     * @param decoder The decoder for the third argument.
     * @return A decoder that decodes three arguments into a {@link F.Tuple3} of arguments.
     */
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

    /**
     * Combine this decoder with an argument encoder to create a decoder that decodes events that may have acks.
     *
     * The passed in encoder will be used if the ack is invoked to encode its arguments before sending them to the
     * client.
     *
     * @param encoder The encoder to encode the ack arguments with.
     * @return A decoder that decodes two arguments and an ack function into a {@link F.Tuple3} of the decoded
     *         arguments and ack function.
     */
    default <A> EventDecoder<F.Tuple3<T1, T2, Optional<Consumer<A>>>> withMaybeAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        Pair<T1, T2> pair = decodeEvent(event);
        return new F.Tuple3<>(pair.first(), pair.second(), OptionConverters.toJava(event.ack()).map(ack -> {
          return a -> ack.apply(encoder.encodeArgs(a));
        }));
      };
    }

    /**
     * Combine this decoder with an argument encoder to create a decoder that decodes events that must have acks.
     *
     * The passed in encoder will be used if the ack is invoked to encode its arguments before sending them to the
     * client.
     *
     * This decoder will fail if the event it decodes does not have an ack attached to it.
     *
     * @param encoder The encoder to encode the ack arguments with.
     * @return A decoder that decodes two arguments and an ack function into a {@link F.Tuple3} of the decoded
     *         arguments and ack function.
     */
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

  /**
   * An event decoder that decodes three arguments.
   *
   * This decoder implements both {@link MultiArgumentDecoder} and {@link EventDecoder}. If passed a list of arguments
   * via the <code>decodeArgs</code> method or an event by the <code>decodeEvent</code> method, it will decode the
   * first, second and third arguments, and fail if there are less than three arguments.
   *
   * It can be used as a whole event decoder for events that are expected to only have three arguments, or it can be
   * combined with other decoders to allow decoding more arguments, via the <code>and</code> method.
   */
  public interface ThreeArgumentDecoder<T1, T2, T3> extends MultiArgumentDecoder<F.Tuple3<T1, T2, T3>> {

    /**
     * Combine this decoder with another decoder to create a decoder that decodes four arguments.
     *
     * @param decoder The decoder for the fourth argument.
     * @return A decoder that decodes four arguments into a {@link F.Tuple4} of arguments.
     */
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

    /**
     * Combine this decoder with an argument encoder to create a decoder that decodes events that may have acks.
     *
     * The passed in encoder will be used if the ack is invoked to encode its arguments before sending them to the
     * client.
     *
     * @param encoder The encoder to encode the ack arguments with.
     * @return A decoder that decodes three arguments and an ack function into a {@link F.Tuple4} of the decoded
     *         arguments and ack function.
     */
    default <A> EventDecoder<F.Tuple4<T1, T2, T3, Optional<Consumer<A>>>> withMaybeAckEncoder(MultiArgumentEncoder<A> encoder) {
      return event -> {
        F.Tuple3<T1, T2, T3> tuple = decodeEvent(event);
        return new F.Tuple4<>(tuple._1, tuple._2, tuple._3, OptionConverters.toJava(event.ack()).map(ack -> {
          return a -> ack.apply(encoder.encodeArgs(a));
        }));
      };
    }

    /**
     * Combine this decoder with an argument encoder to create a decoder that decodes events that must have acks.
     *
     * The passed in encoder will be used if the ack is invoked to encode its arguments before sending them to the
     * client.
     *
     * This decoder will fail if the event it decodes does not have an ack attached to it.
     *
     * @param encoder The encoder to encode the ack arguments with.
     * @return A decoder that decodes three arguments and an ack function into a {@link F.Tuple4} of the decoded
     *         arguments and ack function.
     */
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

  /**
   * An event encoder.
   */
  public interface EventEncoder<T> {

    /**
     * Encode the given <code>T</code> into a {@link SocketIOEvent}.
     *
     * @param t The event to encode.
     * @return The encoded {@link SocketIOEvent}.
     */
    SocketIOEvent encodeEvent(T t);

    /**
     * Contravariant map this event encoder to an encoder of another type.
     *
     * This produces a new {@link EventEncoder} that uses the passed in function to convert the argument passed into it
     * into the type taken by this event encoder, which is then passed to this encoder to encode the event.
     *
     * @param f The contramap function.
     * @return The new event encoder.
     */
    default <U> EventEncoder<U> contramap(Function<? super U, ? extends T> f) {
      return u -> encodeEvent(f.apply(u));
    }

    /**
     * Add a name to this event encoder.
     *
     * This name will be added to the events encoded by this encoder.
     *
     * @param name The name to add.
     * @return An event encoder that encodes events with the given name.
     */
    default NamedEventEncoder<T> withName(String name) {
      return t -> {
        SocketIOEvent event = encodeEvent(t);
        return new SocketIOEvent(name, event.arguments(), event.ack());
      };
    }
  }

  /**
   * A named event encoder.
   *
   * This interface is a marker interface to indicate that the events it encodes will have names on them.
   */
  public interface NamedEventEncoder<T> extends EventEncoder<T> {}

  /**
   * An event encoder that encodes multiple arguments.
   *
   * Primarily a convenience interface to provide an implementation of <code>encodeEvent</code> that takes just the
   * event arguments and produces an unnamed event.
   */
  public interface MultiArgumentEncoder<T> extends EventEncoder<T> {

    /**
     * Encode the given event.
     *
     * @param t The event to encode.
     * @return The encoded arguments.
     */
    Seq<Either<JsValue, ByteString>> encodeArgs(T t);

    @Override
    default SocketIOEvent encodeEvent(T t) {
      return SocketIOEvent.unnamed(encodeArgs(t), Option.apply(null));
    }
  }

  /**
   * An event encoder that just encodes a single argument.
   *
   * This encoder implements both {@link MultiArgumentEncoder} and {@link EventEncoder}. If passed an event via the
   * <code>encodeArg</code> or the <code>encodeEvent</code> method, it will encode the passed in event as a singleton
   * argument.
   *
   * It can be used as a whole event encoder for events that are expected to only have one argument, or it can be
   * combined with other encoders to allow encoding multiple arguments, via the <code>and</code> method.
   */
  public interface SingleArgumentEncoder<T> extends MultiArgumentEncoder<T> {
    Either<JsValue, ByteString> encodeArg(T t);

    /**
     * Combine this encoder with another encoder to create an encoder that encodes two arguments.
     *
     * @param encoder The encoder for the second argument.
     * @return An encoder that encodes two arguments from a {@link Pair} of arguments.
     */
    default <T2> TwoArgumentEncoder<T, T2> and(SingleArgumentEncoder<T2> encoder) {
      return args -> {
        return JavaConverters.asScalaBufferConverter(
            Arrays.asList(encodeArg(args.first()), encoder.encodeArg(args.second()))
        ).asScala();
      };
    }

    /**
     * Combine this encoder with an argument decoder to create an encoder that encodes events that have acks.
     *
     * The passed in decoder will be used when the client invokes the ack to decode the arguments it passes to the
     * events ack function.
     *
     * @param decoder The decoder to decode the ack arguments with.
     * @return An encoder that encodes a {@link Pair} of a single argument and an ack function into an event.
     */
    default <A> EventEncoder<Pair<T, Consumer<A>>> withAckDecoder(MultiArgumentDecoder<A> decoder) {
      return pair -> {
        return SocketIOEvent.unnamed(encodeArgs(pair.first()), Some.<SocketIOEventAck>apply(args ->
          pair.second().accept(decoder.decodeArgs(args))
        ));
      };
    }

    @Override
    default Seq<Either<JsValue, ByteString>> encodeArgs(T t) {
      return JavaConverters.asScalaBufferConverter(Collections.singletonList(encodeArg(t))).asScala();
    }
  }

  /**
   * An event encoder that just encodes two arguments.
   *
   * This encoder implements both {@link MultiArgumentEncoder} and {@link EventEncoder}. If passed an event via the
   * <code>encodeArg</code> or the <code>encodeEvent</code> method, it will encode the passed in event as two
   * arguments.
   *
   * It can be used as a whole event encoder for events that are expected to only have two arguments, or it can be
   * combined with other encoders to allow encoding more arguments, via the <code>and</code> method.
   */
  public interface TwoArgumentEncoder<T1, T2> extends MultiArgumentEncoder<Pair<T1, T2>> {

    /**
     * Combine this encoder with another encoder to create an encoder that encodes three arguments.
     *
     * @param encoder The encoder for the third argument.
     * @return An encoder that encodes three arguments from a {@link F.Tuple3} of arguments.
     */
    default <T3> ThreeArgumentEncoder<T1, T2, T3> and(SingleArgumentEncoder<T3> encoder) {
      return args -> {
        Seq<Either<JsValue, ByteString>> init = encodeArgs(Pair.create(args._1, args._2));
        List<Either<JsValue, ByteString>> result =
            new ArrayList<>(JavaConverters.asJavaCollectionConverter(init).asJavaCollection());
        result.add(encoder.encodeArg(args._3));
        return JavaConverters.asScalaBufferConverter(result).asScala();
      };
    }

    /**
     * Combine this encoder with an argument decoder to create an encoder that encodes events that have acks.
     *
     * The passed in decoder will be used when the client invokes the ack to decode the arguments it passes to the
     * events ack function.
     *
     * @param decoder The decoder to decode the ack arguments with.
     * @return An encoder that encodes a {@link F.Tuple3} of two arguments and an ack function into an event.
     */
    default <A> EventEncoder<F.Tuple3<T1, T2, Consumer<A>>> withAckDecoder(MultiArgumentDecoder<A> decoder) {
      return tuple -> {
        return SocketIOEvent.unnamed(encodeArgs(Pair.create(tuple._1, tuple._2)), Some.<SocketIOEventAck>apply(args ->
          tuple._3.accept(decoder.decodeArgs(args))
        ));
      };
    }
  }

  /**
   * An event encoder that just encodes three arguments.
   *
   * This encoder implements both {@link MultiArgumentEncoder} and {@link EventEncoder}. If passed an event via the
   * <code>encodeArg</code> or the <code>encodeEvent</code> method, it will encode the passed in event as three
   * arguments.
   *
   * It can be used as a whole event encoder for events that are expected to only have three arguments, or it can be
   * combined with other encoders to allow encoding more arguments, via the <code>and</code> method.
   */
  public interface ThreeArgumentEncoder<T1, T2, T3> extends MultiArgumentEncoder<F.Tuple3<T1, T2, T3>> {

    /**
     * Combine this encoder with another encoder to create an encoder that encodes four arguments.
     *
     * @param encoder The encoder for the fourth argument.
     * @return An encoder that encodes four arguments from a {@link F.Tuple4} of arguments.
     */
    default <T4> MultiArgumentEncoder<F.Tuple4<T1, T2, T3, T4>> and(SingleArgumentEncoder<T4> encoder) {
      return args -> {
        Seq<Either<JsValue, ByteString>> init = encodeArgs(new F.Tuple3<>(args._1, args._2, args._3));
        List<Either<JsValue, ByteString>> result =
            new ArrayList<>(JavaConverters.asJavaCollectionConverter(init).asJavaCollection());
        result.add(encoder.encodeArg(args._4));
        return JavaConverters.asScalaBufferConverter(result).asScala();
      };
    }

    /**
     * Combine this encoder with an argument decoder to create an encoder that encodes events that have acks.
     *
     * The passed in decoder will be used when the client invokes the ack to decode the arguments it passes to the
     * events ack function.
     *
     * @param decoder The decoder to decode the ack arguments with.
     * @return An encoder that encodes a {@link F.Tuple4} of three arguments and an ack function into an event.
     */
    default <A> EventEncoder<F.Tuple4<T1, T2, T3, Consumer<A>>> withAckDecoder(MultiArgumentDecoder<A> decoder) {
      return tuple -> {
        return SocketIOEvent.unnamed(encodeArgs(new F.Tuple3<>(tuple._1, tuple._2, tuple._3)), Some.<SocketIOEventAck>apply(args ->
          tuple._4.accept(decoder.decodeArgs(args))
        ));
      };
    }
  }

  /**
   * Exception thrown when the socket.io codec experiences a problem, such as trying to decode more arguments than are
   * supplied.
   */
  public static class EventCodecException extends RuntimeException {

    public static final long serialVersionUID = 1;

    public EventCodecException(String message) {
      super(message);
    }
    public EventCodecException(String message, Throwable cause) {
      super(message, cause);
    }
  }

}
