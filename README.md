# Socket.IO Client for Kotlin Multiplatform

A Kotlin Multiplatform implementation of the [Socket.IO](https://socket.io/) client protocol based on [Ktor](https://ktor.io/).

## Features

- Complete Socket.IO client protocol implementation
- Multiplatform support (JVM, Android, iOS)
- WebSocket and XHR/Polling transport support
- Type-safe API with Kotlin coroutines and Flow
- Binary data support
- Namespaces support
- Acknowledgments

## Usage

```kotlin
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
```

### Event Handling

```kotlin
// Using Flow to listen for specific events
socket.on<Socket.Event.Connect>().collect {
    println("Connected!")
}

// Listen for custom events
socket.on("echoBack").collect { event ->
    println("Echo response: ${event.payload}")
}

// Wait for a single occurrence of an event
val connectEvent = socket.once<Socket.Event.Connect>()
```

### Connection Options

```kotlin
val io = IO(httpClient) {
    loggingLevel = LoggingLevel.DEBUG
    forceNew = true
}

val socket = io.socket("http://localhost:3000/namespace") {
    reconnection = true
    reconnectionAttempts = 5
    reconnectionDelay = 1000.milliseconds
    timeout = 5000.milliseconds
    auth = mapOf("token" to "auth-token")
    autoConnect = true
}
```

## Installation

Add the dependency to your build.gradle.kts:

```kotlin
dependencies {
    implementation("io.voxkit:socketio-client:VERSION")
}
```

## License

Apache 2.0 License
