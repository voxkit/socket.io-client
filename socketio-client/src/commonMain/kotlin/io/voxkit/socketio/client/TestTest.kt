package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

public fun main(): Unit = runBlocking {
    val httpClient = HttpClient {
        install(WebSockets)
    }

    println("Starting WebSocket client...")
    val session = runCatching {
        httpClient.webSocketSession("wss://ws.postman-echo.com/raw")
    }
        .onFailure { println("Failed to start WebSocket client: ${it.message}") }
        .getOrNull() ?: run {
        httpClient.close()
        return@runBlocking
    }

    println("WebSocket client started.")

    println("Sending message...")
    session.send(Frame.Text("Hello, WebSocket!"))
    println("Message sent.")

    println("Receiving message...")
    println((session.incoming.receive() as Frame.Text).readText())
    println("Message received.")

    println("Closing WebSocket session...")
    session.close()
    println("WebSocket session closed.")

//    println("Try send message after close...")
//    runCatching { session.send(Frame.Text("Hello, WebSocket!")) }.onFailure { println("Send failed: $it") }

//    println("Try receive message after close...")
//    runCatching { println((session.incoming.receive() as Frame.Text).readText()) }.onFailure { println("Receive failed: $it") }

    delay(1000)
    println("Is session active? ${session.isActive}")
    session.launch {
        try {
            while (isActive) {
                println("Running...")
                delay(1000)
            }
        } finally {
            println("Coroutine cancelled.")
        }
    }

    delay(5000)

    println("Closing HTTP client...")
    httpClient.close()
    println("HTTP client closed.")

    delay(5000)

    println("Cancelling session...")
    session.cancel()
    println("Session cancelled.")
}
