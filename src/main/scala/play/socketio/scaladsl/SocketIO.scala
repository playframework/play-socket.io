/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.socketio.scaladsl

import javax.inject.Inject

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import play.api.libs.json.{ JsString, JsValue }
import play.api.mvc.RequestHeader
import play.api.{ Configuration, Environment, Logger }
import play.engineio._
import SocketIOEventCodec.{ SocketIOEventsDecoder, SocketIOEventsEncoder }
import play.api.inject.Module
import play.socketio._

import scala.concurrent.{ ExecutionContext, Future }

/**
 * The engine.io system. Allows you to create engine.io controllers for handling engine.io connections.
 */
final class SocketIO @Inject() (config: SocketIOConfig, engineIO: EngineIO)(implicit ec: ExecutionContext, mat: Materializer) {

  private val log = Logger(classOf[SocketIO])

  /**
   * Create a builder.
   *
   * The builder will by default:
   *   - Accept all sessions
   *   - Send the message of each exception the client as a JSON string
   *   - Use a flow that ignores all incoming messages and produces no outgoing messages for the default namespace
   *   - Provide no other namespaces
   */
  def builder: SocketIOBuilder[Any] = {
    new SocketIOBuilder[Any](
      (_, _) => Future.successful(NotUsed),
      {
        case e if e.getMessage != null => JsString(e.getMessage)
        case e                         => JsString(e.getClass.getName)
      },
      _ => Flow.fromSinkAndSource(Sink.ignore, Source.maybe),
      PartialFunction.empty
    )
  }

  /**
   * A builder for engine.io instances.
   */
  class SocketIOBuilder[SessionData] private[socketio] (
    connectCallback:            (RequestHeader, String) => Future[SessionData],
    errorHandler:               PartialFunction[Throwable, JsValue],
    defaultNamespaceCallback:   SocketIOSession[SessionData] => Flow[SocketIOEvent, SocketIOEvent, _],
    connectToNamespaceCallback: PartialFunction[(SocketIOSession[SessionData], String), Flow[SocketIOEvent, SocketIOEvent, _]]
  ) {

    /**
     * Set the onConnect callback.
     *
     * The callback takes the request header of the incoming connection and the id of the session, and should produce a
     * session object, which can be anything, for example, a user principal, or other authentication and/or
     * authorization details.
     *
     * If you wish to reject the connection, you can throw an exception, which will later be handled by the error
     * handler to turn it into a message to send to the client.
     */
    def onConnect[S <: SessionData](callback: (RequestHeader, String) => S): SocketIOBuilder[S] = {
      onConnectAsync((rh, sid) => Future.successful(callback(rh, sid)))
    }

    /**
     * Set the onConnect callback.
     *
     * The callback takes the request header of the incoming connection and the id of the ssion, and should produce a
     * session object, which can be anything, for example, a user principal, or other authentication and/or
     * authorization details.
     *
     * If you wish to reject the connection, you can throw an exception, which will later be handled by the error
     * handler to turn it into a message to send to the client.
     */
    def onConnectAsync[S <: SessionData](callback: (RequestHeader, String) => Future[S]): SocketIOBuilder[S] = {
      new SocketIOBuilder[S](
        callback,
        errorHandler,
        defaultNamespaceCallback,
        connectToNamespaceCallback
      )
    }

    /**
     * Set the error handler.
     *
     * If any errors are encountered, they will be serialized to JSON this function, and then passed to the client
     * using a socket.io error message.
     *
     * Any errors not handled by this partial function will fallback to the existing error handler in this builder,
     * which by default sends the exception message as a JSON string.
     */
    def withErrorHandler(handler: PartialFunction[Throwable, JsValue]): SocketIOBuilder[SessionData] = {
      new SocketIOBuilder(
        connectCallback,
        handler.orElse(errorHandler),
        defaultNamespaceCallback,
        connectToNamespaceCallback
      )
    }

    /**
     * Set the default namespace flow.
     *
     * @param decoder the decoder to use.
     * @param encoder the encoder to use.
     * @param flow the flow.
     */
    def defaultNamespace[In, Out](decoder: SocketIOEventsDecoder[In], encoder: SocketIOEventsEncoder[Out], flow: Flow[In, Out, _]): SocketIOBuilder[SessionData] = {
      defaultNamespace(decoder, encoder)(_ => flow)
    }

    /**
     * Set the default namespace flow.
     *
     * This variant allows you to customise the returned flow according to the session.
     *
     * @param decoder the decoder to use.
     * @param encoder the encoder to use.
     * @param callback a callback to create the flow given the session.
     */
    def defaultNamespace[In, Out](decoder: SocketIOEventsDecoder[In], encoder: SocketIOEventsEncoder[Out])(callback: SocketIOSession[SessionData] => Flow[In, Out, _]): SocketIOBuilder[SessionData] = {
      new SocketIOBuilder(
        connectCallback,
        errorHandler,
        session => createNamespace(decoder, encoder, callback(session)),
        connectToNamespaceCallback
      )
    }

    /**
     * Add a namespace.
     *
     * @param name The name of the namespace.
     * @param decoder The decoder to use to decode messages.
     * @param encoder The encoder to use to encode messages.
     * @param flow The flow to use for the namespace.
     */
    def addNamespace[In, Out](name: String, decoder: SocketIOEventsDecoder[In], encoder: SocketIOEventsEncoder[Out], flow: Flow[In, Out, _]): SocketIOBuilder[SessionData] = {
      addNamespace(decoder, encoder) {
        case (_, `name`) => flow
      }
    }

    /**
     * Add a namespace.
     *
     * This variant allows you to pass a callback that pattern matches on the namespace name, and uses the session
     * data to decide whether the user should be able to connect to this namespace or not.
     *
     * Any exceptions thrown here will result in an error being sent back to the client, serialized by the
     * errorHandler. Alternatively, you can simply not return a value from the partial function, which will result in
     * an error being sent to the client that the namespace does not exist.
     *
     * @param decoder The decoder to use to decode messages.
     * @param encoder The encoder to use to encode messages.
     * @param callback A callback to match the namespace and create a flow accordingly.
     */
    def addNamespace[In, Out](decoder: SocketIOEventsDecoder[In], encoder: SocketIOEventsEncoder[Out])(callback: PartialFunction[(SocketIOSession[SessionData], String), Flow[In, Out, _]]): SocketIOBuilder[SessionData] = {

      new SocketIOBuilder(
        connectCallback,
        errorHandler,
        defaultNamespaceCallback,
        connectToNamespaceCallback.orElse(callback.andThen { flow =>
          createNamespace(decoder, encoder, flow)
        })
      )
    }

    /**
     * Build the engine.io controller.
     */
    def createController(): EngineIOController = {
      val handler = SocketIOSessionFlow.createEngineIOSessionHandler(
        config,
        connectCallback,
        errorHandler,
        defaultNamespaceCallback,
        connectToNamespaceCallback
      )

      engineIO.createController(handler)
    }

    private def createNamespace[In, Out](decoder: SocketIOEventsDecoder[In], encoder: SocketIOEventsEncoder[Out], flow: Flow[In, Out, _]): Flow[SocketIOEvent, SocketIOEvent, _] = {
      Flow[SocketIOEvent] map decoder via flow map encoder
    }
  }
}

/**
 * Provides socket.io components
 *
 * Mix this trait into your application cake to get an instance of [[SocketIO]] to build your socket.io engine with.
 */
trait SocketIOComponents extends EngineIOComponents {
  lazy val socketIOConfig: SocketIOConfig = SocketIOConfig.fromConfiguration(configuration)
  lazy val socketIO: SocketIO = new SocketIO(socketIOConfig, engineIO)(executionContext, materializer)
}
