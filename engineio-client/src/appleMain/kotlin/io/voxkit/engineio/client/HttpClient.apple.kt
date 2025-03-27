package io.voxkit.engineio.client

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.websocket.*

internal actual fun platformHttpClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets)
}
