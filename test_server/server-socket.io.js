const { Server } = require("socket.io");

const io = new Server({
  /* options */
});

io.on("connection", (socket) => {
  console.log("a user connected");
});

io.listen(3000);
