package io.voxkit.engineio.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.voxkit.engineio.client.EngineIOSession.State
import io.voxkit.engineio.client.transports.PollingTransport
import io.voxkit.engineio.client.transports.Transport
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.client.transports.webSocketTransport
import io.voxkit.engineio.parser.Packet
import io.voxkit.engineio.parser.data
import io.voxkit.socketio.logging.VoxKitLoggerFactory
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
    initialTransport: Transport,
    private val options: EngineIOOptions,
    private val handshakePacket: Packet,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("engine.io")),
) : EngineIOSession, CoroutineScope by scope {

    override val id: String? = (handshakePacket as? Packet.Open)?.sid
    private val _incoming: Channel<Packet>
    override val incoming: ReceiveChannel<Packet> get() = _incoming

    private val _state = MutableStateFlow<State>(State.Opening)
    override val state: StateFlow<State> = _state

    private val logger = options.loggerFactory.createLogger("engine.io [${hashCode()}]")
    private val upgrades = (handshakePacket as? Packet.Open)?.upgrades ?: emptyList()
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
        logger.d { "Start Engin.IO socket session, handshake data: $handshakePacket" }
        receivePackets(transport)
        onHeartbeat()

        if (
            options.transports.contains(TransportType.WEBSOCKET) &&
            upgrades.contains("websocket") &&
            transport is PollingTransport
        ) {
            scope.launch { upgrade(transport) }
        } else {
            _state.value = State.Open
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
        _state.value = State.Upgrading

        // Pause the HTTP long-polling transport
        pollingTransport.pause()

        // Open a WebSocket connection with the same session ID
        val webSocketTransport = httpClient.webSocketTransport(options, sid = id)

        // Send a ping packet with the string "probe" in the payload
        val pingProbePacket = Packet.Ping("probe")
        logger.d { "client ==> server: $pingProbePacket" }
        webSocketTransport.send(pingProbePacket)

        // Wait for a pong packet with the string "probe" in the payload
        for (packet in webSocketTransport.incoming) {
            if (packet is Packet.Pong && packet.data() == "probe") {
                logger.d { "client <== server: $packet" }
                break
            }
        }

        // Switch to the WebSocket transport
        transport.value = webSocketTransport
        receivePackets(webSocketTransport)

        // Send an upgrade packet
        logger.d { "client ==> server: ${Packet.Upgrade}" }
        webSocketTransport.send(Packet.Upgrade)
        _state.value = State.Open

        // Close the HTTP long-polling transport
        pollingTransport.close()

        logger.d { "Transport upgraded to WebSocket" }
    }

    private fun receivePackets(transport: Transport) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            logger.d { "Start receiving packets from transport: ${transport.type}" }
            while (true) {
                runCatching { transport.incoming.receive() }
                    .onSuccess { packet ->
                        logger.d { "client <== server [${transport.type}]: $packet" }
                        onPacket(packet)
                    }
                    .onFailure { e ->
                        if (this@EngineIOSessionImpl.transport == transport) {
                            onError(EngineIoException("Transport error [${transport.type}]", cause = e))
                        }
                    }
            }
        }
    }

    private suspend fun onPacket(packet: Packet) {
        onHeartbeat()

        when (packet) {
            is Packet.Ping -> runCatching {
                sendPacket(Packet.Pong())
                _incoming.send(packet)
            }

            is Packet.Error -> onError(EngineIoException("Server error", code = packet.data))
            is Packet.Message, is Packet.Binary -> _incoming.send(packet)
            is Packet.Close -> onClose("io server disconnect")
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

        when (_state.value) {
            State.Opening, State.Upgrading -> {
                logger.d { "client ==> server: Engine.IO is not ready, waiting for OPEN state" }
                _state.first { it == State.Open }
                logger.d { "client ==> server: Engine.IO is ready" }
            }

            State.Open -> Unit // Socket is already open
            is State.Closing, is State.Closed -> throw EngineIOSocketClosedException()
        }
        runCatching {
            val currentTransport = transport.value
            logger.d { "client ==> server [${currentTransport.type}]: $packet" }
            currentTransport.send(packet)
        }
            .onFailure { onError(EngineIoException("Transport error", cause = it)) }
            .getOrThrow()
    }

    override fun close() {
        onClose("io client disconnect")
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

    private fun onError(exception: Exception) {
        onClose("transport error", exception)
    }

    private fun onClose(reason: String, cause: Exception? = null) {
        if (state.value is State.Closing || state.value is State.Closed) return
        _state.value = State.Closing(reason, cause)

        cause?.let { logger.w(it) { "Close Engine.IO socket session: $reason" } }
            ?: run { logger.d { "Close Engine.IO socket session: $reason" } }

        _incoming.cancel()

        scope.launch {
            if (reason == "io client disconnect") runCatching { sendPacket(Packet.Close) }
            _state.value = State.Closed(reason, cause)
            transport.value.close()
            scope.cancel(reason, cause)
        }
    }
}
