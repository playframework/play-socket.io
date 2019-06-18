addSbtPlugin("com.typesafe.play" % "interplay" % sys.props.get("interplay.version").getOrElse("2.0.8"))

addSbtPlugin("com.typesafe"      % "sbt-mima-plugin" % "0.3.0")
addSbtPlugin("org.scoverage"     % "sbt-scoverage"   % "1.5.1")
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.2.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"    % "2.0.0")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.7.2")

// Protobuf
addSbtPlugin(("com.thesamet" % "sbt-protoc" % "0.99.21").exclude("com.trueaccord.scalapb", "protoc-bridge_2.10"))
libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin-shaded" % "0.6.7"
