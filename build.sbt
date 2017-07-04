version in ThisBuild := "1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    organization := "com.lightbend.play",
    name := "play-socket.io",

    scalaVersion := "2.12.2",

    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play" % "2.6.0",
      "com.typesafe.akka" %% "akka-remote" % "2.5.3"
    ),

    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    )
  )

lazy val chat = (project in file("samples/chat"))
  .enablePlugins(PlayScala)
  .dependsOn(root)
  .settings(
    name := "play-socket.io-chat-example",
    organization := "com.lightbend.play",
    scalaVersion := "2.12.2",

    libraryDependencies += "com.softwaremill.macwire" %% "macros" % "2.3.0" % "provided"
  )
