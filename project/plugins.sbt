addSbtPlugin("com.typesafe"      % "sbt-mima-plugin" % "1.1.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.7.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"    % "2.4.6")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.15")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")

// Protobuf
addSbtPlugin("com.thesamet"                    % "sbt-protoc"     % "1.0.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.10"
