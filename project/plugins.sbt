addSbtPlugin("com.typesafe"      % "sbt-mima-plugin" % "1.1.3")
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.10.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"    % "2.5.0")

addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.8")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.0")

// Protobuf
addSbtPlugin("com.thesamet"                    % "sbt-protoc"     % "1.0.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.15"
