package chat

import akka.stream._
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}

import scala.collection.concurrent.TrieMap

sealed trait ChatEvent {
  def room: String
}
case class ChatMessage(user: String, room: String, message: String) extends ChatEvent
case class JoinRoom(user: String, room: String) extends ChatEvent
case class LeaveRoom(user: String, room: String) extends ChatEvent

class ChatSystem(implicit mat: Materializer) {

  // All that chat rooms. Let's assume something else takes care of cleaning these up when no ones in
  // them anymore
  private val chatRooms = TrieMap.empty[String, Flow[ChatEvent, ChatEvent, _]]

  private def getChatRoom(room: String) = {
    chatRooms.getOrElseUpdate(room, {
      // Easy enough to use merge hub / broadcast sink to create a dynamically joinable chat room
      val (sink, source) = MergeHub.source[ChatEvent].toMat(BroadcastHub.sink[ChatEvent])(Keep.both).run
      Flow.fromSinkAndSourceCoupled(sink, source)
    })
  }

  def flow: Flow[ChatEvent, ChatEvent, _] = {

    val joinRoomHandler = Flow.fromGraph(new GraphStageWithMaterializedValue[
      FlowShape[ChatEvent, ChatEvent], (Source[ChatEvent, _], Sink[ChatEvent, _]) => Unit] {

      val in = Inlet[ChatEvent]("in")
      val out = Outlet[ChatEvent]("out")
      override def shape = FlowShape(in, out)
      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
        val logic = new GraphStageLogic(shape) {
          // broadcast source and sink for demux/muxing multiple chat rooms in this one flow
          var broadcastSource: Source[ChatEvent, _] = _
          var mergeSink: Sink[ChatEvent, _] = _

          def provideSourceAndSink(source: Source[ChatEvent, _], sink: Sink[ChatEvent, _]) = {
            broadcastSource = source
            mergeSink = sink
          }

          setHandler(in, new InHandler {
            override def onPush() = grab(in) match {
              case join @ JoinRoom(_, room) =>
                val roomFlow = getChatRoom(room)
                // Add the room to our flow
                broadcastSource
                  // Ensure only messages for this room get there
                  .filter(_.room == room)
                  // Leave the room when the leave room message comes
                  .takeWhile(_.isInstanceOf[LeaveRoom], inclusive = true)
                  .via(roomFlow)
                  .runWith(mergeSink)

                push(out, join)

              case other =>
                push(out, other)
            }
          })

          setHandler(out, new OutHandler {
            override def onPull() = pull(in)
          })
        }

        (logic, logic.provideSourceAndSink)
      }
    })

    val muxedFlow = Flow.fromSinkAndSourceCoupledMat(BroadcastHub.sink[ChatEvent], MergeHub.source[ChatEvent])(Keep.both)
    joinRoomHandler.viaMat(muxedFlow) { (provideSourceAndSink, sourceAndSink) =>
      provideSourceAndSink.tupled(sourceAndSink)
    }

  }

}
