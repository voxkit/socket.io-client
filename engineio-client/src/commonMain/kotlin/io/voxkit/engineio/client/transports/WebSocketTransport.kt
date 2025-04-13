package io.voxkit.engineio.client.transports

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import io.voxkit.engineio.client.EngineIOOptions
import io.voxkit.engineio.parser.Packet
import io.voxkit.engineio.parser.Parser
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun CoroutineScope.webSocketTransport(
    httpClient: HttpClient,
    options: EngineIOOptions,
    sid: String? = null
): Transport {
    return WebSocketTransport(this, httpClient, options, sid)
}

private class WebSocketTransport(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val options: EngineIOOptions,
    private val sid: String?,
) : Transport {
    override val type: TransportType = TransportType.WEBSOCKET

    private val logger = options.loggerFactory.createLogger("engine.io websocket")
    private var webSocketSession = MutableStateFlow<Result<DefaultClientWebSocketSession>?>(null)
    private val job = SupervisorJob()

    init {
        logger.i { "WebSocket transport created." }
        scope.launch(job) {
            webSocketSession.value = runCatching {
                httpClient.webSocketSession {
                    options.buildRequest(TransportType.WEBSOCKET, sid, builder = this)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val incoming: ReceiveChannel<Packet> = scope.produce(job + CoroutineName("incoming [websocket]")) {
        val session = webSocketSession
            .filterNotNull()
            .first()
            .getOrThrow()

        while (true) {
            when (val frame = session.incoming.receive()) {
                is Frame.Binary -> {
                    val bytes = frame.readBytes()
                    val packet = Parser.decodePacket(bytes)
                    send(packet)
                }

                is Frame.Text -> {
                    val text = frame.readText()
                    val packet = Parser.decodePacket(text)
                    send(packet)
                }

                else -> Unit // ignore other frame types
            }
        }
    }

    private val _call = MutableStateFlow<HttpClientCall?>(null)
    override val call: StateFlow<HttpClientCall?> = _call.asStateFlow()

    override suspend fun send(packet: Packet) {
        val session = webSocketSession.filterNotNull().first().getOrThrow()
        when (val encodedPacket = Parser.encodePacket(packet)) {
            is ByteArray -> session.send(encodedPacket)
            is String -> session.send(encodedPacket)
            else -> logger.e { "Illegal packet type: $encodedPacket" }
        }
    }

    override fun close() {
        logger.i { "Close WebSocket transport." }
        scope.launch {
            val session = webSocketSession.filterNotNull().first().getOrNull()
            session?.close()
            job.cancel()
            logger.d { "WebSocket transport closed" }
        }
    }
}
