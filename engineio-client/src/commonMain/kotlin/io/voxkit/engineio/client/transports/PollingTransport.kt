package io.voxkit.engineio.client.transports

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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
import kotlinx.coroutines.flow.first

internal fun CoroutineScope.pollingTransport(httpClient: HttpClient, options: EngineIOOptions): Transport =
    PollingTransport(scope = this, httpClient = httpClient, options = options)

internal class PollingTransport(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val options: EngineIOOptions,
) : Transport {

    override val type: TransportType = TransportType.POLLING

    private val _call = MutableStateFlow<HttpClientCall?>(null)
    override val call: StateFlow<HttpClientCall?> = _call.asStateFlow()

    private val logger = options.loggerFactory.createLogger("engine.io polling")
    private val job = SupervisorJob()
    private val state = MutableStateFlow(State.RUNNING)
    private var sid: String? = null

    override val incoming: ReceiveChannel<Packet> by lazy { poll() }

    init {
        logger.i { "Polling transport started." }
    }

    fun pause() {
        logger.d { "Pause polling transport." }
        state.value = State.PAUSED
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun poll() = scope.produce(job + CoroutineName("incoming [polling]")) {
        while (state.value != State.CLOSED) {
            state.first { it == State.RUNNING || it == State.CLOSED }

            if (state.value == State.CLOSED) {
                logger.d { "Transport closed. Stop polling." }
                break
            }

            logger.d { "Fetch new data..." }
            val response = httpClient.get { options.buildRequest(TransportType.POLLING, sid, builder = this) }
            _call.value = response.call

            if (response.status.isSuccess()) {
                val payload = response.bodyAsText()
                val packets = Parser.decodePayload(payload)

                logger.d { "Fetch new data complete ${response.status}: $payload" }

                if (sid == null && packets.firstOrNull() is Packet.Open) {
                    sid = (packets.first() as Packet.Open).sid
                }

                logger.d { "Send fetched packets to incoming channel." }

                packets.forEach { send(it) }
            } else {
                state.value = State.CLOSED
                throw TransportException(response)
            }
        }
    }

    override suspend fun send(packet: Packet) {
        state.first { it != State.PAUSED }
        check(state.value == State.RUNNING) { "Transport is closed" }

        val payload = Parser.encodePayload(listOf(packet))

        val response = httpClient.post {
            options.buildRequest(TransportType.POLLING, sid, builder = this)
            contentType(ContentType.Text.Plain)
            setBody(payload)
        }

        if (!response.status.isSuccess()) {
            throw TransportException(response)
        }
    }

    override fun close() {
        logger.d { "Close polling transport." }
        if (state.value == State.CLOSED) return
        state.value = State.CLOSED
        job.cancel()
    }

    enum class State { RUNNING, PAUSED, CLOSED }
}
