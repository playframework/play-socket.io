package play.engineio

import java.net.URI

import play.api.libs.typedmap.TypedMap
import play.api.mvc.{Headers, RequestHeader}
import play.api.mvc.request.{RemoteConnection, RequestTarget}
import play.core.parsers.FormUrlEncodedParser

private[engineio] class DeserializedRequestHeader(
  val method: String,
  rawUri: String,
  val version: String,
  headerSeq: Seq[(String, String)]
) extends RequestHeader {
  override lazy val connection = RemoteConnection("0.0.0.0", false, None)
  override def attrs = TypedMap.empty
  override lazy val headers = Headers(headerSeq: _*)
  override lazy val target = {
    new RequestTarget {
      override lazy val uri = URI.create(rawUri)
      override def uriString = rawUri
      override def path = if (uri.getPath == null) "/" else uri.getPath
      override lazy val queryMap = if (uri.getRawQuery == null) {
        Map.empty
      } else {
        FormUrlEncodedParser.parse(uri.getRawQuery)
      }
    }
  }
}
