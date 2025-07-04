/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */
package play.engineio

import java.net.URI

import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.RemoteConnection
import play.api.mvc.request.RequestTarget
import play.api.mvc.Headers
import play.api.mvc.RequestHeader
import play.core.parsers.FormUrlEncodedParser

/**
 * Implementation of Play's RequestHeader that is built from a set of deserialized values.
 *
 * This is provided so that the RequestHeader can be deserialized from a form sent using Pekko remoting.
 *
 * Does not provide the full request functionality, but does provide all the raw information from the wire.
 */
private[engineio] class DeserializedRequestHeader(
    val method: String,
    rawUri: String,
    val version: String,
    headerSeq: Seq[(String, String)]
) extends RequestHeader {
  override lazy val connection: RemoteConnection = RemoteConnection("0.0.0.0", secure = false, None)
  override def attrs: TypedMap                   = TypedMap.empty
  override lazy val headers: Headers             = Headers(headerSeq: _*)
  override lazy val target: RequestTarget = {
    new RequestTarget {
      override lazy val uri: URI     = URI.create(rawUri)
      override def uriString: String = rawUri
      override def path: String      = if (uri.getPath == null) "/" else uri.getPath
      override lazy val queryMap: Map[String, Seq[String]] = if (uri.getRawQuery == null) {
        Map.empty
      } else {
        FormUrlEncodedParser.parse(uri.getRawQuery)
      }
    }
  }
}
