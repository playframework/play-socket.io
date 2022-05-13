import play.core.PlayVersion.{ current => playVersion }
import interplay.ScalaVersions._

val AkkaVersion = "2.5.23"

lazy val runChromeWebDriver = taskKey[Unit]("Run the chromewebdriver tests")

val macwire = "com.softwaremill.macwire" %% "macros" % "2.3.4"
val lombok  = "org.projectlombok"        % "lombok"  % "1.18.8"
val akkaCluster = Seq(
  "com.typesafe.akka" %% "akka-cluster"       % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % AkkaVersion
)

playBuildRepoName in ThisBuild := "play-socket.io"
sonatypeProfileName in ThisBuild := "com.lightbend"

lazy val root = (project in file("."))
  .enablePlugins(PlayReleaseBase, PlayLibrary)
  .settings(
    organization := "com.lightbend.play",
    name := "play-socket-io",
    scalaVersion := scala213,
    crossScalaVersions := Seq(scala213, scala212),
    scalacOptions ++= Seq("-feature", "-target:jvm-1.8"),
    scalacOptions in (Compile, doc) := Nil,
    javacOptions ++= Seq("-Xlint"),
    libraryDependencies ++= Seq(
      // Production dependencies
      "com.typesafe.play" %% "play"        % playVersion,
      "com.typesafe.akka" %% "akka-remote" % AkkaVersion,
      // Test dependencies for running a Play server
      "com.typesafe.play" %% "play-akka-http-server" % playVersion % Test,
      "com.typesafe.play" %% "play-logback"          % playVersion % Test,
      // Test dependencies for Scala/Java dependency injection
      "com.typesafe.play" %% "play-guice" % playVersion % Test,
      macwire             % Test,
      // Test dependencies for running chrome driver
      "org.seleniumhq.selenium" % "selenium-chrome-driver" % "3.141.59",
      // Test framework dependencies
      "org.scalatest" %% "scalatest"      % "3.1.2" % Test,
      "com.novocode"  % "junit-interface" % "0.11"  % Test
    ),
    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    ),
    fork in Test := true,
    connectInput in (Test, run) := true,
    runChromeWebDriver := {
      (runMain in Test).toTask(" play.socketio.RunSocketIOTests").value
    },
    TaskKey[Unit]("runJavaServer") :=
      (runMain in Test).toTask(" play.socketio.javadsl.TestSocketIOJavaApplication").value,
    TaskKey[Unit]("runScalaServer") :=
      (runMain in Test).toTask(" play.socketio.scaladsl.TestSocketIOScalaApplication").value,
    TaskKey[Unit]("runMultiNodeServer") :=
      (runMain in Test).toTask(" play.socketio.scaladsl.TestMultiNodeSocketIOApplication").value,
    test in Test := {
      (test in Test).value
      runChromeWebDriver.value
    },
    resolvers += "jitpack".at("https://jitpack.io"),
    headerLicense := Some(HeaderLicense.Custom("Copyright (C) Lightbend Inc. <https://www.lightbend.com>")),
    headerEmptyLine := false
  )

lazy val scalaChat = (project in file("samples/scala/chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := scala213,
    libraryDependencies += macwire % Provided
  )

lazy val scalaMultiRoomChat = (project in file("samples/scala/multi-room-chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-multi-room-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := scala213,
    libraryDependencies += macwire % Provided
  )

lazy val scalaClusteredChat = (project in file("samples/scala/clustered-chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-clustered-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := scala213,
    libraryDependencies ++= Seq(macwire % Provided) ++ akkaCluster
  )

lazy val javaChat = (project in file("samples/java/chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := scala213,
    libraryDependencies += guice
  )

lazy val javaMultiRoomChat = (project in file("samples/java/multi-room-chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-multi-room-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := scala213,
    libraryDependencies ++= Seq(guice, lombok)
  )

lazy val javaClusteredChat = (project in file("samples/java/clustered-chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-clustered-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := scala213,
    libraryDependencies ++= Seq(guice, lombok) ++ akkaCluster
  )
