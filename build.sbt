import play.core.PlayVersion.{ current => playVersion }
import interplay.ScalaVersions._

val AkkaVersion = "2.5.28"

lazy val runChromeWebDriver = taskKey[Unit]("Run the chromewebdriver tests")

val macwire = "com.softwaremill.macwire" %% "macros" % "2.3.4"
val lombok  = "org.projectlombok"        % "lombok"  % "1.18.8"
val akkaCluster = Seq(
  "com.typesafe.akka" %% "akka-cluster"       % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % AkkaVersion
)

(ThisBuild / playBuildRepoName) := "play-socket.io"
(ThisBuild / sonatypeProfileName) := "com.lightbend"

lazy val root = (project in file("."))
  .enablePlugins(PlayReleaseBase, PlayLibrary)
  .settings(
    organization := "com.lightbend.play",
    name := "play-socket-io",
    scalaVersion := scala213,
    crossScalaVersions := Seq(scala213, scala212),
    scalacOptions ++= Seq("-feature", "-target:jvm-1.8"),
    (Compile / doc / scalacOptions) := Nil,
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
      "org.seleniumhq.selenium" % "selenium-chrome-driver" % "4.1.4",
      // Test framework dependencies
      "org.scalatest" %% "scalatest"      % "3.1.2" % Test,
      "com.novocode"  % "junit-interface" % "0.11"  % Test
    ),
    (Compile / PB.targets) := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value
    ),
    (Test / fork) := true,
    (Test / run / connectInput) := true,
    runChromeWebDriver := {
      (Test / runMain).toTask(" play.socketio.RunSocketIOTests").value
    },
    TaskKey[Unit]("runJavaServer") :=
      (Test / runMain).toTask(" play.socketio.javadsl.TestSocketIOJavaApplication").value,
    TaskKey[Unit]("runScalaServer") :=
      (Test / runMain).toTask(" play.socketio.scaladsl.TestSocketIOScalaApplication").value,
    TaskKey[Unit]("runMultiNodeServer") :=
      (Test / runMain).toTask(" play.socketio.scaladsl.TestMultiNodeSocketIOApplication").value,
    (Test / test) := {
      (Test / test).value
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
