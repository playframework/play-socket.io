package play.socketio


import ch.racic.selenium.drivers.PhantomJSDriverHelper
import org.openqa.selenium.phantomjs.{PhantomJSDriver, PhantomJSDriverService}
import org.openqa.selenium.remote.DesiredCapabilities
import play.core.server.ServerConfig
import java.util

import play.utils.Colors

import scala.collection.JavaConverters._

object RunSocketIOTests extends App {

  val port = 9123

  val timeout = 60000
  val pollInterval = 200

  val capabilities = DesiredCapabilities.phantomjs()

  capabilities.setCapability(PhantomJSDriverService.PHANTOMJS_EXECUTABLE_PATH_PROPERTY,
    PhantomJSDriverHelper.executable64().getAbsolutePath)
  capabilities.setCapability(PhantomJSDriverService.PHANTOMJS_CLI_ARGS, Array("--webdriver-loglevel=WARN"))

  var passCount = 0
  var failCount = 0

  withCloseable(TestSocketIOServer.start(ServerConfig(
    port = Some(port)
  )))(_.stop()) { _ =>

    withCloseable(new PhantomJSDriver(capabilities))(_.quit()) { driver =>

      driver.navigate().to(s"http://localhost:$port/index.html?dontrun=true&jsonp=true")
      driver.executeScript("runMocha();")
      consume(driver, System.currentTimeMillis())

    }
  }

  if (failCount > 0) {
    System.exit(1)
  }

  @annotation.tailrec
  def consume(driver: PhantomJSDriver, start: Long): Unit = {
    var end = false
    driver.executeScript("return consumeMochaEvents();") match {
      case list: util.List[_] =>
        list.asScala.foreach {
          case map: util.Map[String, _] =>
            val obj = map.asScala
            obj.get("name") match {
              case Some("suite") =>
                println(obj.getOrElse("title", ""))
              case Some("pass") =>
                println(s" ${Colors.green("+")} ${obj.getOrElse("title", "")} (${obj.getOrElse("duration", 0)}ms)")
                passCount += 1
              case Some("fail") =>
                println(Colors.red(" - ") + obj.getOrElse("title", ""))
                println(s"[${Colors.red("error")} ${obj.getOrElse("error", "")}")
                failCount += 1
              case Some("end") =>
                val status = if (failCount > 0) {
                  Colors.red("error")
                } else {
                  Colors.green("success")
                }
                println(s"[$status] Test run finished in ${System.currentTimeMillis() - start}ms with $passCount passed and $failCount failed")
                end = true
              case other => sys.error("Unexpected event: " + other)
            }
          case unexpected => sys.error("Unexpected object in list: " + unexpected)
        }
      case unexpected => sys.error("Unexpected return value: " + unexpected)
    }
    if (start + timeout < System.currentTimeMillis()) {
      throw new RuntimeException("Tests have taken too long!")
    }
    if (!end) {
      Thread.sleep(pollInterval)
      consume(driver, start)
    }
  }


  def withCloseable[T](closeable: T)(close: T => Unit)(block: T => Unit) = try {
    block(closeable)
  } finally {
    close(closeable)
  }


}
