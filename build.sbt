import Dependencies.AkkaVersion
import Dependencies.Scala212
import Dependencies.Scala213
import play.core.PlayVersion.{ current => playVersion }

lazy val runChromeWebDriver = taskKey[Unit]("Run the chromewebdriver tests")

val macwire = "com.softwaremill.macwire" %% "macros" % "2.5.7"
val lombok  = "org.projectlombok"         % "lombok" % "1.18.8" % Provided
val akkaCluster = Seq(
  "com.typesafe.akka" %% "akka-cluster"       % AkkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % AkkaVersion
)

// Customise sbt-dynver's behaviour to make it work with tags which aren't v-prefixed
(ThisBuild / dynverVTagPrefix) := false

// TODO: enable after first release
val previousVersion: Option[String] = None

lazy val root = (project in file("."))
  .settings(
    organization := "com.typesafe.play",
    name := "play-socket-io",
    mimaPreviousArtifacts := previousVersion.map(organization.value %% moduleName.value % _).toSet,
    scalaVersion := Scala213,
    crossScalaVersions := Seq(Scala213, Scala212),
    scalacOptions ++= Seq("-feature", "-release", "11"),
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
      macwire              % Test,
      // Test dependencies for running chrome driver
      "io.github.bonigarcia"    % "webdrivermanager"       % "5.3.2" % Test,
      "org.seleniumhq.selenium" % "selenium-chrome-driver" % "4.18.1" % Test,
      // Test framework dependencies
      "org.scalatest" %% "scalatest"       % "3.1.2" % Test,
      "com.novocode"   % "junit-interface" % "0.11"  % Test
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
    headerLicense := Some(
      HeaderLicense.Custom(
        "Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>"
      )
    ),
    headerEmptyLine := false
  )

lazy val scalaChat = (project in file("samples/scala/chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-chat-example",
    organization := "com.typesafe.play",
    scalaVersion := Scala213,
    libraryDependencies += macwire % Provided
  )

lazy val scalaMultiRoomChat = (project in file("samples/scala/multi-room-chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-multi-room-chat-example",
    organization := "com.typesafe.play",
    scalaVersion := Scala213,
    libraryDependencies += macwire % Provided
  )

lazy val scalaClusteredChat = (project in file("samples/scala/clustered-chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-clustered-chat-example",
    organization := "com.typesafe.play",
    scalaVersion := Scala213,
    libraryDependencies ++= Seq(macwire % Provided) ++ akkaCluster
  )

lazy val javaChat = (project in file("samples/java/chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-chat-example",
    organization := "com.typesafe.play",
    scalaVersion := Scala213,
    libraryDependencies += guice
  )

lazy val javaMultiRoomChat = (project in file("samples/java/multi-room-chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-multi-room-chat-example",
    organization := "com.typesafe.play",
    scalaVersion := Scala213,
    libraryDependencies ++= Seq(guice, lombok)
  )

lazy val javaClusteredChat = (project in file("samples/java/clustered-chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-clustered-chat-example",
    organization := "com.typesafe.play",
    scalaVersion := Scala213,
    libraryDependencies ++= Seq(guice, lombok) ++ akkaCluster
  )

addCommandAlias(
  "validateCode",
  (
    List(
      "headerCheckAll",
      "scalafmtSbtCheck",
      "scalafmtCheckAll",
    ) ++
      List(scalaChat, scalaMultiRoomChat, scalaClusteredChat, javaChat, javaMultiRoomChat, javaClusteredChat)
        .flatMap(p => List("scalafmtCheckAll").map(cmd => s"${p.id}/$cmd"))
  ).mkString(";")
)
