/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.api.mvc

import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import play.api.http.ParserConfiguration

/**
 * Created to use in the meantime before https://github.com/playframework/playframework/pull/7551 is merged and
 * released.
 *
 * This is in the play.api.mvc in order to take advantage of the built in Play utilities for buffering bodies.
 */
class ByteStringBodyParser(parsers: PlayBodyParsers) {

  private object myParsers extends PlayBodyParsers {
    private[play] implicit override def materializer: Materializer = parsers.materializer
    override def config: ParserConfiguration                       = parsers.config
    private[play] override def errorHandler                        = parsers.errorHandler
    private[play] override def temporaryFileCreator                = parsers.temporaryFileCreator

    // Overridden to make public
    override def tolerantBodyParser[A](name: String, maxLength: Long, errorMessage: String)(
        parser: (RequestHeader, ByteString) => A
    ): BodyParser[A] =
      super.tolerantBodyParser(name, maxLength, errorMessage)(parser)
  }

  def byteString: BodyParser[ByteString] =
    myParsers.tolerantBodyParser("byteString", myParsers.config.maxMemoryBuffer, "Error decoding byte string body")(
      (_, bytes) => bytes
    )
}
