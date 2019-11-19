package modules

import chat.ChatEngine
import play.api.{ApplicationLoader, BuiltInComponents, BuiltInComponentsFromContext, LoggerConfigurator}
import com.softwaremill.macwire._
import controllers.AssetsComponents
import play.engineio.{EngineIOComponents, EngineIOController}
import play.socketio.scaladsl.SocketIOComponents

class MyApplicationLoader extends ApplicationLoader {
  override def load(context: ApplicationLoader.Context) =
    new BuiltInComponentsFromContext(context) with MyApplication {
      LoggerConfigurator.apply(context.environment.classLoader)
        .foreach(_.configure(context.environment))
    }.application
}

trait MyApplication extends BuiltInComponents
  with AssetsComponents
  with SocketIOComponents {

  lazy val chatEngine = wire[ChatEngine]
  lazy val engineIOController: EngineIOController = chatEngine.controller

  override lazy val router = {
    val prefix = "/"
    wire[_root_.router.Routes]
  }
  override lazy val httpFilters = Nil
}