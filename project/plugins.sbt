addSbtPlugin("com.typesafe"      % "sbt-mima-plugin" % "0.6.0")
addSbtPlugin("org.scoverage"     % "sbt-scoverage"   % "1.6.1")
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.5.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"    % "2.4.2")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.7.3")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")

// Protobuf
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.10"
