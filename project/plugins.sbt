addSbtPlugin("com.typesafe.play" % "interplay" % sys.props.get("interplay.version").getOrElse("2.1.2"))

addSbtPlugin("com.typesafe"      % "sbt-mima-plugin" % "0.6.0")
addSbtPlugin("org.scoverage"     % "sbt-scoverage"   % "1.6.1")
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.5.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"    % "2.0.7")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.7.3")

// Protobuf
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.27")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin-shaded" % "0.9.8"
