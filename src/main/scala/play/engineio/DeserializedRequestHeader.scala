/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package play.engineio

import java.net.URI

import play.api.libs.typedmap.TypedMap
import play.api.mvc.Headers
import play.api.mvc.RequestHeader
import play.api.mvc.request.RemoteConnection
import play.api.mvc.request.RequestTarget
import play.core.parsers.FormUrlEncodedParser

/**
 * Implementation of Play's RequestHeader that is built from a set of deserialized values.
 *
 * This is provided so that the RequestHeader can be deserialized from a form sent using Akka remoting.
 *
 * Does not provide the full request functionality, but does provide all the raw information from the wire.
 */
private[engineio] class DeserializedRequestHeader(
    val method: String,
    rawUri: String,
    val version: String,
    headerSeq: Seq[(String, String)]
) extends RequestHeader {
  override lazy val connection = RemoteConnection("0.0.0.0", false, None)
  override def attrs           = TypedMap.empty
  override lazy val headers    = Headers(headerSeq: _*)
  override lazy val target = {
    new RequestTarget {
      override lazy val uri  = URI.create(rawUri)
      override def uriString = rawUri
      override def path      = if (uri.getPath == null) "/" else uri.getPath
      override lazy val queryMap: Map[String, Seq[String]] = if (uri.getRawQuery == null) {
        Map.empty
      } else {
        FormUrlEncodedParser.parse(uri.getRawQuery)
      }
    }
  }
}
