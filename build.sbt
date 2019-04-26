import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import de.heikoseeberger.sbtheader.HeaderPattern
import play.core.PlayVersion.{current => playVersion}
val AkkaVersion = "2.5.3"

lazy val runPhantomjs = taskKey[Unit]("Run the phantomjs tests")

playBuildRepoName in ThisBuild := "play-socket.io"
sonatypeProfileName in ThisBuild := "com.lightbend"

lazy val root = (project in file("."))
  .enablePlugins(PlayReleaseBase, PlayLibrary, AutomateHeaderPlugin)
  .settings(
    organization := "com.lightbend.play",
    name := "play-socket-io",

    scalacOptions ++= Seq("-feature", "-Xfatal-warnings"),
    scalacOptions in (Compile, doc) := Nil,
    javacOptions ++= Seq("-Xlint"),

    libraryDependencies ++= Seq(
      // Production dependencies
      "com.typesafe.play" %% "play" % playVersion,
      "com.typesafe.akka" %% "akka-remote" % AkkaVersion,

      // Test dependencies for running a Play server
      "com.typesafe.play" %% "play-akka-http-server" % playVersion % Test,
      "com.typesafe.play" %% "play-logback" % playVersion % Test,

      // Test dependencies for Scala/Java dependency injection
      "com.typesafe.play" %% "play-guice" % playVersion % Test,
      "com.softwaremill.macwire" %% "macros" % "2.3.2" % Test,

      // Test dependencies for running phantomjs
      "ch.racic.selenium" % "selenium-driver-helper-phantomjs" % "2.1.1" % Test,
      "com.github.detro" % "ghostdriver" % "2.1.0" % Test,

      // Test framework dependencies
      "org.scalatest" %% "scalatest" % "3.0.7" % Test,
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
    TaskKey[Unit]("runMultiNodeServer") :=
      (runMain in Test).toTask(" play.socketio.scaladsl.TestMultiNodeSocketIOApplication").value,

    test in Test := {
      (test in Test).value
      runPhantomjs.value
    },

    resolvers += "jitpack" at "https://jitpack.io",

    headers := headers.value ++ Map(
      "scala" -> (
        HeaderPattern.cStyleBlockComment,
        """|/*
           | * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
           | */
           |""".stripMargin
      ),
      "java" -> (
        HeaderPattern.cStyleBlockComment,
        """|/*
           | * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
           | */
           |""".stripMargin
      )
    ),

    ScalariformKeys.preferences in Compile  := formattingPreferences,
    ScalariformKeys.preferences in Test     := formattingPreferences
  )

def formattingPreferences = {
  import scalariform.formatter.preferences._
  FormattingPreferences()
    .setPreference(RewriteArrowSymbols, false)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(SpacesAroundMultiImports, true)
}

lazy val scalaChat = (project in file("samples/scala/chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.3.2" % Provided
  )

lazy val scalaMultiRoomChat = (project in file("samples/scala/multi-room-chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-multi-room-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.3.2" % Provided
  )

lazy val scalaClusteredChat = (project in file("samples/scala/clustered-chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-scala-clustered-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies ++= Seq(
      "com.softwaremill.macwire" %% "macros" % "2.3.2" % Provided,
      "com.typesafe.akka" %% "akka-cluster" % AkkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools" % AkkaVersion
    )
  )

lazy val javaChat = (project in file("samples/java/chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies += guice
  )

lazy val javaMultiRoomChat = (project in file("samples/java/multi-room-chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-multi-room-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies ++= Seq(
      guice,
      "org.projectlombok" % "lombok" % "1.16.16"
    )
  )

lazy val javaClusteredChat = (project in file("samples/java/clustered-chat"))
  .enablePlugins(PlayJava)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-java-clustered-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies ++= Seq(
      guice,
      "org.projectlombok" % "lombok" % "1.16.16",
      "com.typesafe.akka" %% "akka-cluster" % AkkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools" % AkkaVersion
    )
  )

