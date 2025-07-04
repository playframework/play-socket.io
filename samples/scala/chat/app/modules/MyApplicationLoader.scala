package modules

import chat.ChatEngine
import com.softwaremill.macwire._
import controllers.AssetsComponents
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.api.Application
import play.api.ApplicationLoader
import play.api.BuiltInComponents
import play.api.BuiltInComponentsFromContext
import play.api.LoggerConfigurator
import play.engineio.EngineIOController
import play.socketio.scaladsl.SocketIOComponents

class MyApplicationLoader extends ApplicationLoader {
  override def load(context: ApplicationLoader.Context): Application =
    new BuiltInComponentsFromContext(context) with MyApplication {
      LoggerConfigurator
        .apply(this.context.environment.classLoader)
        .foreach(_.configure(this.context.environment))
    }.application
}

trait MyApplication extends BuiltInComponents with AssetsComponents with SocketIOComponents {

  lazy val chatEngine: ChatEngine                 = wire[ChatEngine]
  lazy val engineIOController: EngineIOController = chatEngine.controller

  override lazy val router: Router = {
    val prefix = "/"
    wire[_root_.router.Routes]
  }
  override lazy val httpFilters: Seq[EssentialFilter] = Nil
}
