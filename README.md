# Engine.IO Client for Kotlin Multiplatform

A Kotlin Multiplatform implementation of the [Engine.IO](https://socket.io/docs/v4/engine-io-protocol/) client protocol on top of [Ktor](https://ktor.io/).

## Features

- Full Engine.IO protocol implementation
- Multiplatform support (JVM, Android, iOS)
- WebSocket and XHR/Polling transport support
- Type-safe API with Kotlin coroutines support

## Installation

### Gradle

```kotlin
dependencies {
    implementation("io.voxkit:engineio-client:$engioneIoVersion")
}
```

## Usage

### Basic Connection

```kotlin
fun main() = runBlocking {
    val httpClient = engineIOHttpClient()

    // Connect to localhost at port 3000
    val session = httpClient.engineIOSession {
        port = 3000
    }

    val echo = async { session.incoming.receive() }
    session.send("Hello, Engine.IO!")
    println("Received: ${echo.await()}")

    session.close()
    httpClient.close()
}
```

### Custom HTTP Client

You can provide a custom HTTP client:

```kotlin
// Use your custom HTTP client configuration
val customHttpClient = HttpClient {
    install(Websockets)
    // Your custom configuration
}

val session = customHttpClient.engineIOSession { 
    // Your session configuration
}
```

## License

[Apache 2.0 License](LICENSE)
