function getQueryParameter(name) {
  name = name.replace(/[\[]/, '\\[').replace(/[\]]/, '\\]');
  var regex = new RegExp('[\\?&]' + name + '=([^&#]*)');
  var results = regex.exec(location.search);
  return results === null ? '' : decodeURIComponent(results[1].replace(/\+/g, ' '));
}

// Phantomjs doesn't support Object.assign, so we add it here
if (typeof Object.assign !== 'function') {
  (function () {
    Object.assign = function (target) {
      'use strict';
      if (target === undefined || target === null) {
        throw new TypeError('Cannot convert undefined or null to object');
      }

      var output = Object(target);
      for (var index = 1; index < arguments.length; index++) {
        var source = arguments[index];
        if (source !== undefined && source !== null) {
          for (var nextKey in source) {
            if (source.hasOwnProperty(nextKey)) {
              output[nextKey] = source[nextKey];
            }
          }
        }
      }
      return output;
    };
  })();
}

// This is how we read stuff out of phantomjs

var mochaEvents = [];
var consumeMochaEvents = function() {
  var toReturn = mochaEvents;
  mochaEvents = [];
  return toReturn;
};

var recordMochaEvent = function(event) {
  mochaEvents.push(event);
};

var runMocha = function() {
  var mochaRunner = mocha.run();

  ["suite", "pass"].forEach(function (eventName) {
    mochaRunner.on(eventName, function (event) {
      var props = [];
      for (var prop in event) {
        props.push(prop);
      }

      recordMochaEvent({
        name: eventName,
        title: event.title,
        duration: event.duration
      });
    });
  });

  mochaRunner.on("fail", function (event, err) {
    recordMochaEvent({
      name: "fail",
      title: event.title,
      duration: event.duration,
      error: err.message
    })
  });

  mochaRunner.on("end", function (event) {
    recordMochaEvent({
      name: "end"
    })
  });
};
