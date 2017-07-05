# Play socket.io support

## Developing

### Testing

The tests are written in JavaScript using mocha and chai, so that the JavaScript socket.io client can be used, to ensure compatibility with the reference implementation. They can be run by running `test` in sbt.

If you want to debug the tests, the easiest way is to run `test:run`, this will start an HTTP server on port 9000, if you then visit `http://localhost:9000/`, you will get the browser version of the mocha test runner, where you can set breakpoints in the JavaScript code if needed, and be able to inspect what gets sent over the wire.

The test runner executes almost exactly the same thing, using phantom.js.
