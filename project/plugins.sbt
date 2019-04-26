addSbtPlugin("com.typesafe.play" % "interplay" % sys.props.get("interplay.version").getOrElse("1.3.18"))

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.13")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.0")
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "1.8.0")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.6.0")

// Protobuf
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.11" exclude ("com.trueaccord.scalapb", "protoc-bridge_2.10"))
libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin-shaded" % "0.6.7"
