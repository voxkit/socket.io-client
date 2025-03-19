package io.voxkit.engineio.client

import co.touchlab.kermit.Logger
import io.ktor.client.*
import io.ktor.client.call.*
import io.voxkit.engineio.client.transports.PollingTransport
import io.voxkit.engineio.client.transports.Transport
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.client.transports.webSocketTransport
import io.voxkit.engineio.parser.Packet
import io.voxkit.engineio.parser.data
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class EngineIOSessionImpl(
    private val logger: Logger,
    initialTransport: Transport,
    private val options: EngineIOOptions,
    private val handshakePacket: Packet,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("EngineIoSession")),
) : EngineIOSession, CoroutineScope by scope {

    override val id: String? = (handshakePacket as? Packet.Open)?.sid
    private val _incoming: Channel<Packet>
    override val incoming: ReceiveChannel<Packet> get() = _incoming

    private val upgrades = (handshakePacket as? Packet.Open)?.upgrades ?: emptyList()
    private val state = MutableStateFlow(State.OPENING)
    private var transport = MutableStateFlow(initialTransport)
    private var pingTimeoutJob: Job? = null

    override val transportType: StateFlow<TransportType> = transport
        .map { it.type }
        .stateIn(scope, started = SharingStarted.Eagerly, initialValue = initialTransport.type)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val call: StateFlow<HttpClientCall?> = transport
        .flatMapLatest { it.call }
        .stateIn(scope, started = SharingStarted.Eagerly, initialValue = initialTransport.call.value)

    init {
        if (handshakePacket is Packet.Open) {
            _incoming = Channel()
            startSession(initialTransport)
        } else {
            throw EngineIOInvalidHandshakeException(handshakePacket)
        }

        scope.launch {
            runCatching { awaitCancellation() }.onFailure { _incoming.cancel() }
        }
    }

    private fun startSession(transport: Transport) {
        logger.d { "start Engin.IO socket session, handshake data: $handshakePacket" }
        receivePackets(transport)
        onHeartbeat()

        if (
            options.transports.contains(TransportType.WEBSOCKET) &&
            upgrades.contains("websocket") &&
            transport is PollingTransport
        ) {
            scope.launch { upgrade(transport) }
        } else {
            state.value = State.OPEN
        }
    }

    /**
     * @see https://socket.io/docs/v4/engine-io-protocol/#upgrade
     *
     * CLIENT                                                 SERVER
     *
     *   │                                                      │
     *   │   GET /engine.io/?EIO=4&transport=websocket&sid=...  │
     *   │ ───────────────────────────────────────────────────► │
     *   │  ◄─────────────────────────────────────────────────┘ │
     *   │            HTTP 101 (WebSocket handshake)            │
     *   │                                                      │
     *   │            -----  WebSocket frames -----             │
     *   │  ─────────────────────────────────────────────────►  │
     *   │                         2probe                       │ (ping packet)
     *   │  ◄─────────────────────────────────────────────────  │
     *   │                         3probe                       │ (pong packet)
     *   │  ─────────────────────────────────────────────────►  │
     *   │                         5                            │ (upgrade packet)
     *   │                                                      │
     */
    private suspend fun upgrade(pollingTransport: PollingTransport) {
        logger.d { "Upgrading transport to WebSocket" }
        state.value = State.UPGRADING

        // Pause the HTTP long-polling transport
        pollingTransport.pause()

        // Open a WebSocket connection with the same session ID
        val webSocketTransport = httpClient.webSocketTransport(options, sid = id)

        // Send a ping packet with the string "probe" in the payload
        logger.v { "client ==> server: probe" }
        webSocketTransport.send(Packet.Ping("probe"))

        // Wait for a pong packet with the string "probe" in the payload
        for (packet in webSocketTransport.incoming) {
            if (packet is Packet.Pong && packet.data() == "probe") break
        }
        logger.v { "client <== server: probe" }

        // Switch to the WebSocket transport
        transport.value = webSocketTransport
        receivePackets(webSocketTransport)

        // Send an upgrade packet
        logger.v { "client ==> server: upgrade" }
        webSocketTransport.send(Packet.Upgrade)
        state.value = State.OPEN

        // Close the HTTP long-polling transport
        pollingTransport.close()

        logger.d { "Transport upgraded to WebSocket" }
    }

    private fun receivePackets(transport: Transport) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            logger.d { "Start receiving packets from transport: ${transport.type}" }
            while (true) {
                runCatching { transport.incoming.receive() }
                    .onSuccess { onPacket(it) }
                    .onFailure { e ->
                        if (this@EngineIOSessionImpl.transport == transport) {
                            onError(EngineIoException("Transport error [${transport.type}]", cause = e))
                        }
                    }
            }
        }
    }

    private suspend fun onPacket(packet: Packet) {
        logger.v { "client <== server: $packet" }
        onHeartbeat()

        when (packet) {
            is Packet.Ping -> runCatching { sendPacket(Packet.Pong()) }
            is Packet.Error -> onError(EngineIoException("Server error", code = packet.data))
            is Packet.Message, is Packet.Binary -> _incoming.send(packet)
            is Packet.Close -> onClose("close by server")
            else -> logger.w { "Unexpected packet type: $packet" }
        }
    }

    override suspend fun send(message: String) {
        sendPacket(Packet.Message(message))
    }

    override suspend fun send(data: ByteArray) {
        sendPacket(Packet.Binary(data))
    }

    private suspend fun sendPacket(packet: Packet) {
        logger.v { "client ==> server: $packet" }

        when (state.value) {
            State.OPENING, State.UPGRADING -> {
                logger.v { "Socket is not ready, waiting for OPEN state" }
                state.first { it == State.OPEN }
                logger.v { "Socket is in OPEN state" }
            }

            State.OPEN -> Unit // Socket is already open
            State.CLOSING, State.CLOSED -> throw EngineIOSocketClosedException()
        }
        runCatching { transport.value.send(packet) }
            .onFailure { onError(EngineIoException("Transport error", cause = it)) }
            .getOrThrow()
    }

    override suspend fun close() {
        onClose("close by client")
    }

    private fun onHeartbeat() {
        pingTimeoutJob?.cancel()
        pingTimeoutJob = scope.launch {
            check(handshakePacket is Packet.Open) { "Handshake packet is not OPEN" }
            val timeout = handshakePacket.pingInterval + handshakePacket.pingTimeout
            delay(timeout)
            onClose("ping timeout")
        }
    }

    private suspend fun onError(exception: Exception) {
        onClose("transport error", exception)
    }

    private suspend fun onClose(reason: String, cause: Exception? = null) {
        if (state.value in listOf(State.CLOSING, State.CLOSED)) return
        state.value = State.CLOSING

        cause?.let { logger.w(it) { "Close Engine.IO socket session: $reason" } }
            ?: run { logger.d { "Close Engine.IO socket session: $reason" } }

        _incoming.cancel()
        scope.cancel(reason, cause)
        state.value = State.CLOSED
        transport.value.close()
    }

    enum class State {
        OPENING, UPGRADING, OPEN, CLOSING, CLOSED
    }
}
