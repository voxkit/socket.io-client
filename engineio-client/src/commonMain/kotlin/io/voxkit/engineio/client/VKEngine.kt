package io.voxkit.engineio.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.voxkit.engineio.client.Engine.State
import io.voxkit.engineio.client.transports.PollingTransport
import io.voxkit.engineio.client.transports.Transport
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.client.transports.webSocketTransport
import io.voxkit.engineio.parser.Packet
import io.voxkit.engineio.parser.data
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class VKEngine(
    private val initialTransport: Transport,
    private val scope: CoroutineScope,
    private val options: EngineIOOptions,
    private val httpClient: HttpClient,
) : Engine {

    override val id: String? get() = handshake.value?.sid
    override val incoming: ReceiveChannel<Packet> by lazy { produceIncomingPackets() }

    private val _state = MutableStateFlow<State>(State.Opening)
    override val state: StateFlow<State> = _state

    private val logger = options.loggerFactory.createLogger("engine.io")
    private var transport = MutableStateFlow(initialTransport)
    private val handshake = MutableStateFlow<Packet.Open?>(null)
    private var engineJob = SupervisorJob()
    private var pingTimeoutJob: Job? = null

    override val transportType: StateFlow<TransportType> = transport
        .map { it.type }
        .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = initialTransport.type)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val call: StateFlow<HttpClientCall?> = transport
        .flatMapLatest { it.call }
        .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = initialTransport.call.value)

    init {
        logger.i { "engine.io created" }
        handshake()
    }

    private fun handshake() {
        logger.i { "Handshake started" }

        scope.launch(engineJob) {
            val handshakePacket = initialTransport.incoming.receive()

            if (handshakePacket !is Packet.Open) {
                onError(InvalidHandshakeEngineException(handshakePacket))
                return@launch
            }

            handshake.value = handshakePacket

            logger.i { "Handshake done: $handshakePacket" }

            onHeartbeat()
            tryUpgrade(handshakePacket)
            _state.value = State.Open
        }
    }

    private suspend fun tryUpgrade(handshake: Packet.Open) {
        val currentTransport = transport.value

        if (
            options.transports.contains(TransportType.WEBSOCKET) &&
            handshake.upgrades.contains("websocket") &&
            currentTransport is PollingTransport
        ) {
            runCatching { upgrade(currentTransport) }.onFailure { e ->
                if (e is CancellationException) throw e
                onError(e)
            }
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

        // Pause the HTTP long-polling transport
        pollingTransport.pause()

        // Open a WebSocket connection with the same session ID
        val webSocketTransport = scope.webSocketTransport(httpClient, options, id)

        runCatching {
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

            // Send an upgrade packet
            logger.d { "client ==> server: ${Packet.Upgrade}" }
            webSocketTransport.send(Packet.Upgrade)
            _state.value = State.Open

            // Close the HTTP long-polling transport
            pollingTransport.close()

            logger.d { "Transport upgraded to WebSocket" }
        }
            .onFailure { webSocketTransport.close() }
            .getOrThrow()
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    private fun produceIncomingPackets(): ReceiveChannel<Packet> {
        return scope.produce(engineJob + CoroutineName("incoming [engine.io]")) {
            handshake.filterNotNull().first()

            transport.collectLatest { currentTransport ->
                for (packet in currentTransport.incoming) {
                    logger.d { "client <== server [${currentTransport.type}]: $packet" }
                    onHeartbeat()

                    when (packet) {
                        Packet.Close -> {
                            onClose(DisconnectReason.SERVER_DISCONNECT)
                            return@collectLatest
                        }

                        is Packet.Ping -> {
                            sendPacket(Packet.Pong())
                            send(packet)
                        }

                        is Packet.Message, is Packet.Binary -> send(packet)
                        else -> logger.w { "Unexpected packet type: $packet" }
                    }
                }
            }
        }
    }

    override suspend fun send(message: String) = sendPacket(Packet.Message(message))

    override suspend fun send(data: ByteArray) = sendPacket(Packet.Binary(data))

    private suspend fun sendPacket(packet: Packet) {
        _state.first { it == State.Open }

        val currentTransport = transport.value
        logger.d { "client ==> server [${currentTransport.type}]: $packet" }

        runCatching { currentTransport.send(packet) }.onFailure { e ->
            if (e is CancellationException) throw e
            onError(e)
        }
    }

    override fun close() {
        onClose(DisconnectReason.CLIENT_DISCONNECT)
    }

    private fun onHeartbeat() {
        logger.d { "Heartbeat." }
        val handshake = handshake.value ?: return
        pingTimeoutJob?.cancel()
        pingTimeoutJob = scope.launch(engineJob) {
            // for realtime delay in test scope
            withContext(Dispatchers.Default) {
                val timeout = handshake.pingInterval + handshake.pingTimeout
                delay(timeout)
                logger.d { "Ping timeout." }
                onClose(DisconnectReason.PING_TIMEOUT)
            }
        }
    }

    private fun onError(exception: Throwable) {
        onClose(DisconnectReason.TRANSPORT_ERROR, exception)
    }

    private fun onClose(reason: DisconnectReason, cause: Throwable? = null) {
        if (state.value is State.Closed) return
        _state.value = State.Closed(reason, cause)

        cause?.let { logger.w(it) { "Close Engine.IO socket session: $reason" } }
            ?: run { logger.d { "Close Engine.IO socket session: $reason" } }

        transport.value.close()
        engineJob.cancel()
    }
}
