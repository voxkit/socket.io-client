package io.voxkit.engineio.client.transports

import co.touchlab.kermit.Logger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import io.voxkit.engineio.client.EngineIOOptions
import io.voxkit.engineio.parser.Packet
import io.voxkit.engineio.parser.Parser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal suspend fun HttpClient.webSocketTransport(options: EngineIOOptions, sid: String? = null): Transport {
    val incomingPackets = Channel<Packet>()

    val webSocketSession = runCatching {
        webSocketSession { options.buildRequest(TransportType.WEBSOCKET, sid, builder = this) }
    }
        .onFailure { incomingPackets.cancel() }
        .getOrThrow()

    val logger = Logger(options.loggerConfig, "WebSocketTransport @ ${webSocketSession.hashCode()}")
    logger.v { "WebSocket transport stared." }

    webSocketSession.launch {
        try {
            while (true) {
                when (val frame = webSocketSession.incoming.receive()) {
                    is Frame.Binary -> {
                        val bytes = frame.readBytes()
                        val packet = Parser.decodePacket(bytes)
                        incomingPackets.send(packet)
                    }

                    is Frame.Text -> {
                        val text = frame.readText()
                        val packet = Parser.decodePacket(text)
                        incomingPackets.send(packet)
                    }

                    else -> {
                        // Ignore other frame types
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            logger.d { "WebSocket transport closed: ${webSocketSession.closeReason.await()}" }
            incomingPackets.send(Packet.Close)
            incomingPackets.cancel()
        }
    }

    return object : Transport, CoroutineScope by webSocketSession {
        override val type: TransportType = TransportType.WEBSOCKET
        override val incoming: ReceiveChannel<Packet> = incomingPackets
        override val call: StateFlow<HttpClientCall> = MutableStateFlow(webSocketSession.call).asStateFlow()

        override suspend fun send(packet: Packet) {
            runCatching {
                when (val encodedPacket = Parser.encodePacket(packet)) {
                    is ByteArray -> webSocketSession.send(encodedPacket)
                    is String -> webSocketSession.send(encodedPacket)
                    else -> logger.e { "Illegal packet type: $encodedPacket" }
                }
            }.onFailure { e ->
                incomingPackets.send(Packet.Error("Failed to send packet: ${e.message}"))
            }
        }

        override fun close() {
            incomingPackets.cancel()
            webSocketSession.launch { webSocketSession.close() }
            logger.d { "Transport $type closed" }
        }
    }
}
