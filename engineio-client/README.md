# Engine.IO Client for Kotlin Multiplatform

A Kotlin Multiplatform implementation of the [Engine.IO](https://socket.io/docs/v4/engine-io-protocol/) client protocol
based on top of [Ktor](https://ktor.io/).

## Features

- Full Engine.IO protocol implementation
- Multiplatform support (JVM, Android, iOS, TODO: add other platforms)
- WebSocket and XHR/Polling transport support
- Type-safe API with Kotlin coroutines support

## Usage

### Basic Connection

```kotlin
fun main() = runBlocking {
    val httpClient = ioHttpClient()

    // Connect to localhost at port 3000
    val engine = engineIO("http://localhost:3000", httpClient)

    val echo = async { engine.incoming.receive() }
    engine.send("Hello, Engine.IO!")
    println("Received: ${echo.await().data}")

    engine.close()
    httpClient.close()
}
```

### HTTP Client Configuration

You can configure HTTP client:

```kotlin
val httpClient = ioHttpClient {
    // Configure the HTTP client if needed.
    // See https://ktor.io/docs/client-create-and-configure.html#configure-client
}

val engine = engineIO("http://localhost:3000", httpClient)
```

Or even provide your own custom HTTP client:

```kotlin
// Use your custom HTTP client configuration
val customHttpClient = HttpClient {
    install(Websockets) // Webscoket plugin is required
    // Your custom configuration
}

val engine = engineIO("http://localhost:3000", httpClient)
```

### Connection Options
```kotlin
val engine = engineIO("http://localhost:3000", httpClient) {
    path = "/custom/path"
    transports = listOf(TransportType.POLLING, TransportType.WEBSOCKET)
    timestampRequests = true
    timestampParam = "t"
    extraHeaders = headersOf("Authorization", "Bearer token")
}
```

For all possible options, see the `EngineOptionsBuilder`.
