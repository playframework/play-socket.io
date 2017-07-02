package modules

import chat.ChatEngine
import play.api.{ApplicationLoader, BuiltInComponents, BuiltInComponentsFromContext, LoggerConfigurator}
import com.softwaremill.macwire._
import controllers.{AssetsComponents, MyRouter}
import play.api.inject.DefaultApplicationLifecycle
import play.api.mvc.ControllerComponents
import play.api.routing.Router
import socketio.{EngineIOConfig, EngineIOFactory}

class MyApplicationLoader extends ApplicationLoader {
  override def load(context: ApplicationLoader.Context) =
    new BuiltInComponentsFromContext(context) with MyApplication {
      LoggerConfigurator.apply(context.environment.classLoader)
        .foreach(_.configure(context.environment))
    }.application
}

trait MyApplication extends BuiltInComponents
  with AssetsComponents {

  override def applicationLifecycle: DefaultApplicationLifecycle

  def controllerComponents: ControllerComponents

  lazy val engineIOConfig = EngineIOConfig()
  lazy val engineIOFactory = wire[EngineIOFactory]
  lazy val chatEngine = wire[ChatEngine]


  lazy val mainRouter: Router = {
    val prefix = "/"
    wire[_root_.router.Routes]
  }

  override lazy val router = new MyRouter(mainRouter)
  override lazy val httpFilters = Nil
}