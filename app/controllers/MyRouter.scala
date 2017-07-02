package controllers

import play.api.mvc.{Handler, RequestHeader}
import play.api.routing.{Router, SimpleRouter}

class MyRouter(delegate: Router) extends Router {
  override def routes = Function.unlift[RequestHeader, Handler] { request =>
    import request._
    /*println(s"Got request:")
    println(s"$method $uri $version")
    headers.headers.foreach {
      case (k, v) => println(s"$k: $v")
    }
    println()*/
    None
  } orElse delegate.routes

  override def documentation = delegate.documentation

  override def withPrefix(prefix: String) = new MyRouter(delegate.withPrefix(prefix))
}