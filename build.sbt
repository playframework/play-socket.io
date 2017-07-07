version in ThisBuild := "1.0-SNAPSHOT"

import play.core.PlayVersion.{current => playVersion}

lazy val runPhantomjs = taskKey[Unit]("Run the phantomjs tests")

lazy val root = (project in file("."))
  .settings(
    organization := "com.lightbend.play",
    name := "play-socket.io",

    scalaVersion := "2.12.2",

    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play" % playVersion,
      "com.typesafe.akka" %% "akka-remote" % "2.5.3",
      "com.typesafe.play" %% "play-akka-http-server" % playVersion % Test,
      "com.softwaremill.macwire" %% "macros" % "2.3.0" % Test,
      "com.typesafe.play" %% "play-logback" % playVersion % Test,
      "ch.racic.selenium" % "selenium-driver-helper-phantomjs" % "2.1.1" % Test,
      "com.github.detro" % "ghostdriver" % "2.1.0" % Test
    ),

    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    ),

    fork in Test := true,
    connectInput in (Test, run) := true,
    mainClass in Test := Some("play.socketio.TestSocketIOServer"),

    runPhantomjs := {
      (runMain in Test).toTask(" play.socketio.RunSocketIOTests").value
    },

    test in Test := {
      (test in Test).value
      runPhantomjs.value
    },

    resolvers += "jitpack" at "https://jitpack.io"
  )

lazy val chat = (project in file("samples/chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.3.0" % Provided
  )

lazy val multiRoomChat = (project in file("samples/multi-room-chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-multi-room-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.3.0" % Provided
  )
