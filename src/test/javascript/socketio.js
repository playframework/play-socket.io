
var expect = chai.expect;

var modes = [
  {
    name: "Default",
    opts: {},
    expectUpgrade: true
  },
  {
    name: "WebSocket",
    opts: {
      transports: ["websocket"]
    }
  },
  {
    name: "Polling",
    opts: {
      transports: ["polling"]
    }
  },
  {
    name: "Polling Base64",
    opts: {
      transports: ["polling"],
      forceBase64: true
    }
  }
];

if (getQueryParameter("jsonp") === "true") {
  modes.push({
    name: "Polling JSONP",
    opts: {
      transports: ["polling"],
      forceJSONP: true
    }
  });
}


modes.forEach(function (mode) {

  describe(mode.name + " socket.io support", function() {
    var socket;
    var manager;

    this.timeout(5000);

    function connect(uri, opts) {
      socket = manager.socket(uri, opts);
      return socket;
    }

    beforeEach(function(done) {
      socket = io(Object.assign({
        forceNew: true
      }, mode.opts));
      manager = socket.io;
      // If we're using a protocol that upgrades, wait to upgrade.
      if (mode.expectUpgrade) {
        manager.engine.on("upgrade", function() {
          done();
        });
      } else {
        done();
      }
    });

    afterEach(function() {
      if (manager) {
        manager.close();
      }
    });

    it("should allow sending and receiving messages on the root namespace", function(done) {
      socket.on("m", function(arg) {
        expect(arg).to.equal("hello");
        done();
      });
      socket.emit("m", "hello");
    });

    it("should allow sending acks in both directions", function(done) {
      socket.emit("m", "hello", function(arg) {
        expect(arg).to.equal("This is an acked ack");
        done();
      });
      socket.on("m", function(arg, ack) {
        ack("This is an acked ack");
      });
    });

    it("should support binary messages", function(done) {
      var blob = new Blob(["im binary"], {type: "text/plain"});
      socket.emit("m", blob);
      socket.on("m", function(arg) {
        var text = new TextDecoder("utf-8").decode(arg);
        expect(text).to.equal("im binary");
        done();
      });
    });

    it("should support binary acks", function(done) {
      socket.emit("m", "hello", function(arg) {
        var text = new TextDecoder("utf-8").decode(arg);
        expect(text).to.equal("this is a binary acked ack");
        done();
      });
      socket.on("m", function(arg, ack) {
        var blob = new Blob(["this is a binary acked ack"], {type: "text/plain"});
        ack(blob);
      });
    });

    it("should allow connecting to a namespace", function(done) {
      var socket = connect("/test");
      socket.on("connect", function() {
        done();
      });
    });

    it("should allow sending and receiving messages on a namespace", function(done) {
      var socket = connect("/test");
      socket.emit("m", "hello");
      socket.on("m", function(arg) {
        expect(arg).to.equal("hello");
        done();
      });
    });

    it("should allow sending acks in both directions on a namespace", function(done) {
      var socket = connect("/test");
      socket.emit("m", "hello", function(arg) {
        expect(arg).to.equal("This is an acked ack");
        done();
      });
      socket.on("m", function(arg, ack) {
        ack("This is an acked ack");
      });
    });

    it("should allow disconnecting from a namespace", function(done) {
      var testDisconnectListener = connect("/test-disconnect-listener");
      var socket = connect("/test");
      testDisconnectListener.on("test disconnect", function(sid) {
        if (sid === manager.engine.id) {
          done();
        }
      });
      socket.on("connect", function() {
        socket.disconnect();
      });
    });

    it("should get back an error if the namespace doesn't exist", function(done) {
      var socket = connect("/i-dont-exist");
      socket.on("error", function(error) {
        expect(error).to.equal("Namespace not found: /i-dont-exist");
        done();
      });
    });

    it("should notify the client when a namespace disconnects on the server", function(done) {
      var socket = connect("/test");
      socket.on("disconnect", function() {
        done();
      });
      socket.emit("disconnect me");
    });

    it("should notify the client when a namespace emits an error", function(done) {
      var socket = connect("/failable");
      socket.on("error", function(arg) {
        expect(arg).to.equal("you failed");
        done();
      });
      socket.emit("fail me");
    });

    it("should disconnect the namespace when a namespace emits an error", function(done) {
      var socket = connect("/failable");
      socket.on("disconnect", function() {
        done();
      });
      socket.emit("fail me");
    });

  })
});


