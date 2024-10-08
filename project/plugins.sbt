addSbtPlugin("com.typesafe"      % "sbt-mima-plugin" % "1.1.3")
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.10.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"    % "2.5.0")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.7.3")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.6.0")

// Protobuf
addSbtPlugin("com.thesamet"                    % "sbt-protoc"     % "1.0.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.12"
