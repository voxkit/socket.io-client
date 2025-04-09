package socketio

import io.voxkit.engineio.client.ioHttpClient
import io.voxkit.socketio.client.IO
import io.voxkit.socketio.client.Socket
import io.voxkit.socketio.client.send
import io.voxkit.socketio.client.sendWithAck
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * This is a simple example of how to use the Socket.IO client in Kotlin.
 * It connects to a Socket.IO server, listens for events, and sends messages.
 *
 * To run this example, you need to have a Socket.IO server running on localhost at port 3001.
 * You can use the following command to start a simple Socket.IO server:
 * ```
 * node test_server/server-socket.io.js
 * ```
 */
fun main() = runBlocking {
    // Create a Socket.IO client
    val httpClient = ioHttpClient()
    val io = IO(httpClient)

    // Connect to localhost at port 3000
    val socket = io.socket("http://localhost:3000")

    // Listen for events
    val job = launch {
        println("Listening for events...")
        socket.events.collect { event ->
            when (event) {
                is Socket.Event.Connect -> println("Connected!")
                is Socket.Event.Custom -> println("Received: ${event.event} with data: ${event.payload}")
                is Socket.Event.Disconnect -> println("Disconnected: ${event.reason}")
                else -> {}
            }
        }
    }

    socket.connect()

    // Send a message
    socket.send("echo", "Hello, Socket.IO!")

    // Using acknowledgments
    val response = socket.sendWithAck("getAckDate", mapOf("test" to true))
    println("Received ack: $response")

    // Close connection
    socket.disconnect()
    job.cancel()
    io.close()
    httpClient.close()
}
