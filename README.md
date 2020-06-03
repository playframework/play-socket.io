# Play socket.io support

[![Build Status](https://travis-ci.org/playframework/play-socket.io.png?branch=master)](https://travis-ci.org/playframework/play-socket.io) [![Latest Release](https://img.shields.io/maven-central/v/com.lightbend.play/play-socket-io_2.12.svg)](https://mvnrepository.com/artifact/com.lightbend.play/play-socket-io_2.12)

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
