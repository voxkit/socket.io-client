package io.voxkit.engineio.client.transports

import co.touchlab.kermit.Logger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.voxkit.engineio.client.EngineIOOptions
import io.voxkit.engineio.parser.Packet
import io.voxkit.engineio.parser.Parser
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

internal fun HttpClient.pollingTransport(options: EngineIOOptions): Transport {
    return PollingTransport(options, httpClient = this)
}

internal class PollingTransport(
    private val options: EngineIOOptions,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("PollingTransport")),
) : Transport, CoroutineScope by scope {
    override val type: TransportType = TransportType.POLLING

    private val _incoming = Channel<Packet>()
    override val incoming: ReceiveChannel<Packet> = _incoming

    private val _call = MutableStateFlow<HttpClientCall?>(null)
    override val call: StateFlow<HttpClientCall?> = _call.asStateFlow()

    private val logger = Logger(options.loggerConfig, "PollingTransport")
    private val state = MutableStateFlow(State.RUNNING)
    private var sid: String? = null

    init {
        scope.launch { poll() }
    }

    fun pause() {
        state.value = State.PAUSED
    }

    private suspend fun poll() {
        try {
            while (true) {
                state.first { it == State.RUNNING }
                runCatching {
                    val response = httpClient.get { options.buildRequest(TransportType.POLLING, sid, builder = this) }
                    _call.value = response.call

                    if (response.status.isSuccess()) {
                        val payload = response.bodyAsText()
                        val packets = Parser.decodePayload(payload)

                        if (sid == null && packets.firstOrNull() is Packet.Open) {
                            sid = (packets.first() as Packet.Open).sid
                        }
                        packets.forEach { _incoming.send(it) }
                    } else {
                        val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                        val errorMessage =
                            "Polling request failed: HTTP ${response.status.value} ${response.status.description}. " +
                                    "URL: ${response.request.url}, " +
                                    "Method: ${response.request.method.value}, " +
                                    "Response: ${errorBody.take(100)}${if (errorBody.length > 100) "..." else ""}"

                        logger.w { errorMessage }
                        _incoming.send(Packet.Error("Polling request failed"))
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    logger.w(e) { "Polling request failed" }
                    _incoming.send(Packet.Error("Polling request failed"))
                }
            }
        } finally {
            _incoming.cancel()
        }
    }

    override suspend fun send(packet: Packet) {
        if (scope.isActive.not()) return

        runCatching {
            val payload = Parser.encodePayload(listOf(packet))

            val response = httpClient.post {
                options.buildRequest(TransportType.POLLING, sid, builder = this)
                contentType(ContentType.Text.Plain)
                setBody(payload)
            }

            if (!response.status.isSuccess()) {
                val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                _incoming.send(
                    Packet.Error(
                        "Failed to send packet: HTTP ${response.status.value} ${response.status.description}. " +
                                "URL: ${response.request.url}, " +
                                "Method: POST, " +
                                "Content-Type: ${response.request.contentType()}, " +
                                "Response: ${errorBody.take(100)}${if (errorBody.length > 100) "..." else ""}"
                    )
                )
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e

            val errorType = when (e) {
                is ConnectTimeoutException -> "Network error"
                is SocketTimeoutException -> "Socket timeout"
                is HttpRequestTimeoutException -> "Request timeout"
                else -> "Unexpected error"
            }

            _incoming.send(Packet.Error("$errorType during send packet: ${e::class.simpleName} ${e.message}"))
        }
    }

    override fun close() {
        if (state.value == State.CLOSED) return
        state.value = State.CLOSED
        scope.cancel()
        logger.d { "Transport $type closed." }
    }

    enum class State { RUNNING, PAUSED, CLOSED }
}
