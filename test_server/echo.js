var fs = require("fs");
var engine = require("engine.io");

const http = require("http").createServer();

const server = engine.attach(http, {
  pingInterval: 500,
});

var port = process.env.PORT || 3000;
http.listen(port, function () {
  console.log("Engine.IO server listening on port", port);
});

server
  .on("connection", function (socket) {
    console.log("Connection established");
    socket.on("message", function (message) {
      socket.send(message);
    });

    socket.on("error", function (err) {
      throw err;
    });
  })
  .on("error", function (err) {
    console.error(err);
  });
