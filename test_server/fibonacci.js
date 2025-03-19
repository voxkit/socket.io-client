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

    let f1 = 0;
    let f2 = 1;

    socket.on("message", function (message) {
      if (message === "next") {
        socket.send("" + f1);
        const next = f1 + f2;
        f1 = f2;
        f2 = next;
      }
    });

    socket.on("error", function (err) {
      throw err;
    });
  })
  .on("error", function (err) {
    console.error(err);
  });
