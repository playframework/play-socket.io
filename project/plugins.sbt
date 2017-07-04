// The Play plugin
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.6.0")

// Protobuf
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.11" exclude ("com.trueaccord.scalapb", "protoc-bridge_2.10"))
libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin-shaded" % "0.6.0"
