package io.voxkit.engineio.client

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*

internal actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets)
}
