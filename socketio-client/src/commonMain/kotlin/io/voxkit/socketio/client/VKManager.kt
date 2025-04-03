package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.http.*
import io.voxkit.engineio.client.Engine
import io.voxkit.engineio.client.engineIOSession
import io.voxkit.socketio.client.Manager.State
import io.voxkit.socketio.client.parser.DefaultParser
import io.voxkit.socketio.client.parser.Packet
import io.voxkit.socketio.client.parser.Parser
import io.voxkit.socketio.logging.VoxKitLoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import io.voxkit.engineio.parser.Packet as EngineIOPacket

internal class VKManager(
    private val serverUrl: Url,
    val options: ManagerOptions,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val loggerFactory: VoxKitLoggerFactory,
) : Manager {

    private val _events = MutableSharedFlow<Manager.Event>()
    override val events: Flow<Manager.Event> = _events.asSharedFlow()

    private val _incoming = MutableSharedFlow<Packet>()
    val incoming: Flow<Packet> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow<State>(State.Disconnected("not connected", null))
    val state: StateFlow<State> = _state.asStateFlow()

    var recovered: Boolean = false
        private set

    private val logger = loggerFactory.createLogger("socket.io manager [${hashCode()}]")
    private val parser = DefaultParser()

    private var reconnectionAttemptCount = 0
    private var engine: Engine? = null

    // TODO: atomic
    private val sockets = mutableMapOf<String, SocketImpl>()
    private val connectedSockets = MutableStateFlow<Set<String>>(emptySet())
    private val hasConnectedSockets get() = connectedSockets.value.isNotEmpty()

    private val mutex = Mutex()

    init {
        observeConnectedSockets()
        startReconnectionLoop()

        scope.launch { if (options.autoConnect) connect() }

        scope.launch {
            try {
                awaitCancellation()
            } finally {
                engine?.close()
                engine = null
            }
        }
    }

    private fun startReconnectionLoop() {
        scope.launch {
            state.first { it is State.Connected }
            while (true) {
                val disconnected = state.first { it is State.Disconnected } as State.Disconnected
                if (
                    options.reconnection &&
                    hasConnectedSockets &&
                    disconnected.reason in listOf("ping timeout", "transport close", "transport error")
                ) {
                    runCatching { connect(recovering = true) }
                }
                state.first { it is State.Connected }
            }
        }
    }

    private fun observeConnectedSockets() {
        scope.launch {
            connectedSockets.collect { namespaces ->
                if (namespaces.isEmpty()) {
                    disconnect()
                } else if (state.value !is State.Connected) {
                    runCatching { connect(recovering = false) }
                }
            }
        }
    }

    override suspend fun connect() {
        connect(false)
    }

    private suspend fun connect(recovering: Boolean) {
        if (engine?.isActive == true) return

        mutex.withLock {
            if (engine?.isActive == true) return
            _state.value = State.Connecting
            reconnectionAttemptCount = 0
            connectWithRetries()
                .onSuccess {
                    logger.d { "Connection succeed. Reconnect attempts: $reconnectionAttemptCount" }
                    recovered = recovering
                    _state.value = State.Connected
                    if (reconnectionAttemptCount > 0) {
                        _events.emit(Manager.Event.Reconnect(reconnectionAttemptCount))
                    }
                    reconnectionAttemptCount = 1
                }
                .onFailure { e ->
                    logger.w(e) { "Connection failed. Reconnect attempts: $reconnectionAttemptCount" }
                    recovered = false
                    _events.emit(Manager.Event.ReconnectionFailed)
                    _state.value = State.Disconnected("connection failed", e)
                }
                .getOrThrow()
            startEngineIOSessionLifecycle()
        }
    }

    private suspend fun disconnect() {
        if (engine?.isActive != true) return
        mutex.withLock {
            if (engine?.isActive == true) {
                engine?.close()
                engine = null
            }
        }
    }

    private suspend fun connectWithRetries(): Result<Unit> {
        var result: Result<Unit> = Result.success(Unit)

        while (reconnectionAttemptCount <= options.reconnectionAttempts) {
            if (reconnectionAttemptCount > 0) {
                logger.d { "Reconnect. Attempt: $reconnectionAttemptCount" }
                _events.emit(Manager.Event.ReconnectAttempt(reconnectionAttemptCount))
            } else {
                logger.d { "Connect. Attempt: $reconnectionAttemptCount" }
            }

            result = runCatching { createEngineIOSession() }
                .onFailure { e ->
                    if (e is CancellationException) throw e

                    logger.w { "Connection attempt failed: ${e.message}, ${e.cause?.message}" }
                    if (reconnectionAttemptCount == 0) {
                        _events.emit(Manager.Event.Error(e))
                    } else {
                        _events.emit(Manager.Event.ReconnectError(e))
                    }

                    if (options.reconnection) {
                        val duration = options.calculateReconnectionDelay(reconnectionAttemptCount)
                        logger.d { "Try to reconnect in $duration" }
                        delay(duration.coerceAtMost(options.reconnectionDelayMax))
                    }
                }

            if (!options.reconnection || engine?.isActive == true) break

            reconnectionAttemptCount++
        }

        return result
    }

    private suspend fun createEngineIOSession() {
        engine = withTimeout(options.timeout) {
            httpClient.engineIOSession(serverUrl) {
                path = options.path
                headers.appendAll(options.headers)
                parameters.appendAll(options.parameters)
                timestampParam = takeIf { options.timestampRequests }?.let { options.timestampParam }
                transports = options.transports
                loggingLevel = loggerFactory.level
                logger = loggerFactory.logger
            }
        }
    }

    private fun startEngineIOSessionLifecycle() {
        val session = checkNotNull(engine) { "EngineIOSession is not available" }

        session.launch(SupervisorJob()) {
            for (engineIoPacket in session.incoming) {
                onEngineIOPacket(engineIoPacket, session.incoming)
            }
        }

        session.launch {
            try {
                awaitCancellation()
            } finally {
                val sessionState = session.state.value as Engine.State.Closed
                logger.d { "Engine.IO session closed. Reason: ${sessionState.reason}" }
                _state.value = State.Disconnected(sessionState.reason, sessionState.cause)
            }
        }
    }

    private suspend fun onEngineIOPacket(engineIoPacket: EngineIOPacket, next: ReceiveChannel<EngineIOPacket>) {
        when (engineIoPacket) {
            is EngineIOPacket.Message -> {
                val packet = runCatching { decodeEngineIOPacket(engineIoPacket, next) }
                    .getOrElse {
                        logger.w(it) { "Failed to decode engine.io packet: $engineIoPacket. Discard it." }
                        return
                    }
                logger.d { "client <== server: $packet" }
                _incoming.emit(packet)
            }

            is EngineIOPacket.Ping -> _events.emit(Manager.Event.Ping)

            else -> logger.w { "Discard unexpected engine.io packet: $engineIoPacket" }
        }
    }

    private suspend fun decodeEngineIOPacket(
        engineIoPacket: EngineIOPacket.Message,
        next: ReceiveChannel<EngineIOPacket>
    ): Packet {
        var packet = parser.decode(engineIoPacket.data)

        if (packet.type == Packet.Type.BINARY_EVENT || packet.type == Packet.Type.BINARY_ACK) {
            var decoded: Parser.Decoded = Parser.Decoded.Partial(packet)
            while (decoded !is Parser.Decoded.Completed) {
                val nextEngineIoPacket = next.receive()
                check(nextEngineIoPacket is EngineIOPacket.Binary)
                decoded = parser.decodeBinary(nextEngineIoPacket.data, decoded)
            }
            packet = decoded.packet
        }

        return packet
    }

    override fun socket(namespace: String, auth: AuthSocketOption?): Socket {
        return sockets.getOrPut(namespace) { SocketImpl(namespace, this, auth, scope, loggerFactory) }
    }

    override suspend fun send(packet: Packet) {
        logger.d { "client ==> server: $packet" }

        when (packet.type) {
            Packet.Type.CONNECT -> connectedSockets.value += packet.namespace
            Packet.Type.DISCONNECT -> connectedSockets.value -= packet.namespace
            else -> Unit // ignore other packet types
        }

        val session = checkNotNull(engine)

        when (val encoded = parser.encode(packet)) {
            is Parser.Encoded.Binary -> {
                session.send(encoded.buffers[0].decodeToString())
                encoded.buffers.drop(1).forEach { session.send(it) }
            }

            is Parser.Encoded.Text -> session.send(encoded.data)
        }
    }
}
