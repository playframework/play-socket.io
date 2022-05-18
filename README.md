# Play socket.io support

[![Twitter Follow](https://img.shields.io/twitter/follow/playframework?label=follow&style=flat&logo=twitter&color=brightgreen)](https://twitter.com/playframework)
[![Discord](https://img.shields.io/discord/931647755942776882?logo=discord&logoColor=white)](https://discord.gg/g5s2vtZ4Fa)
[![GitHub Discussions](https://img.shields.io/github/discussions/playframework/playframework?&logo=github&color=brightgreen)](https://github.com/playframework/playframework/discussions)
[![StackOverflow](https://img.shields.io/static/v1?label=stackoverflow&logo=stackoverflow&logoColor=fe7a16&color=brightgreen&message=playframework)](https://stackoverflow.com/tags/playframework)
[![YouTube](https://img.shields.io/youtube/channel/views/UCRp6QDm5SDjbIuisUpxV9cg?label=watch&logo=youtube&style=flat&color=brightgreen&logoColor=ff0000)](https://www.youtube.com/channel/UCRp6QDm5SDjbIuisUpxV9cg)
[![Twitch Status](https://img.shields.io/twitch/status/playframework?logo=twitch&logoColor=white&color=brightgreen&label=live%20stream)](https://www.twitch.tv/playframework)
[![OpenCollective](https://img.shields.io/opencollective/all/playframework?label=financial%20contributors&logo=open-collective)](https://opencollective.com/playframework)

[![Build Status](https://github.com/playframework/play-socket.io/actions/workflows/build-test.yml/badge.svg)](https://github.com/playframework/play-socket.io/actions/workflows/build-test.yml)
[![Maven](https://img.shields.io/maven-central/v/com.typesafe.play/play-socket-io_2.13.svg?logo=apache-maven)](https://mvnrepository.com/artifact/com.typesafe.play/play-socket-io_2.13)
[![Repository size](https://img.shields.io/github/repo-size/playframework/play-socket.io.svg?logo=git)](https://github.com/playframework/play-socket.io)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)
[![Mergify Status](https://img.shields.io/endpoint.svg?url=https://api.mergify.com/v1/badges/playframework/play-socket.io&style=flat)](https://mergify.com)

This is the Play backend socket.io support.

## Features

* Supports all socket.io features, including polling and WebSocket transports, multiple namespaces, acks, JSON and binary messages.
* Uses Akka streams to handle namespaces.
* Supports end to end back pressure, pushing back on the TCP connection for clients that send messages faster than the server can handle them.
* In-built multi node support via Akka clustering, no need to use sticky load balancing.
* Straight forward codec DSL for translating socket.io callback messages to high level streamed message types.

## Documentation

See the [Java](./docs/JavaSocketIO.md) and [Scala](./docs/ScalaSocketIO.md) documentation.

## Sample apps

Sample apps can be found [here](./samples).

## License and support

This software is copyright 2017 Lightbend, and is licensed under the [Apache 2 license](LICENSE). The Lightbend subscription does not cover support for this software.

As this software is still in beta status, no guarantees are made with regards to binary or source compatibility between versions.

## Developing

### Testing

Integration tests are written in JavaScript using mocha and chai, so that the JavaScript socket.io client can be used, to ensure compatibility with the reference implementation. They can be run by running `test` in sbt.

There are multiple different backends that the tests are run against, including one implemented in Java, one in Scala, and one in a multi node setup. To debug them, you can start these tests by running `runJavaServer`, `runScalaServer` and `runMultiNodeServer` from `sbt`, then visit `http://localhost:9000`. This will bring you to an index page will includes and will run the mocha test suite against the backend you started. From there, you can set breakpoints in the JavaScript, and inspect the communication using your browsers developer tools.

The test runner runs the tests against each of these backends, using phantomjs as the browser, and it extracts the test results out and prints them nicely to the console as the tests are running.

## Support

The Play-backed socket.io support library is *[Community Driven](https://developer.lightbend.com/docs/introduction/getting-help/support-terminology.html)*.
