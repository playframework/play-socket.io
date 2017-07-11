version in ThisBuild := "1.0-SNAPSHOT"

import play.core.PlayVersion.{current => playVersion}

lazy val runPhantomjs = taskKey[Unit]("Run the phantomjs tests")

lazy val root = (project in file("."))
  .settings(
    organization := "com.lightbend.play",
    name := "play-socket.io",

    scalaVersion := "2.12.2",

    libraryDependencies ++= Seq(
      // Production dependencies
      "com.typesafe.play" %% "play" % playVersion,
      "com.typesafe.akka" %% "akka-remote" % "2.5.3",

      // Test dependencies for running a Play server
      "com.typesafe.play" %% "play-akka-http-server" % playVersion % Test,
      "com.typesafe.play" %% "play-logback" % playVersion % Test,

      // Test dependencies for Scala/Java dependency injection
      "com.typesafe.play" %% "play-guice" % playVersion % Test,
      "com.softwaremill.macwire" %% "macros" % "2.3.0" % Test,

      // Test dependencies for running phantomjs
      "ch.racic.selenium" % "selenium-driver-helper-phantomjs" % "2.1.1" % Test,
      "com.github.detro" % "ghostdriver" % "2.1.0" % Test,

      // Test framework dependencies
      "org.scalatest" %% "scalatest" % "3.0.1" % Test,
      "com.novocode" % "junit-interface" % "0.11" % Test
    ),

    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    ),

    fork in Test := true,
    connectInput in (Test, run) := true,

    runPhantomjs := {
      (runMain in Test).toTask(" play.socketio.RunSocketIOTests").value
    },

    TaskKey[Unit]("runJavaServer") :=
      (runMain in Test).toTask(" play.socketio.javadsl.TestSocketIOJavaApplication").value,
    TaskKey[Unit]("runScalaServer") :=
      (runMain in Test).toTask(" play.socketio.scaladsl.TestSocketIOScalaApplication").value,

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
