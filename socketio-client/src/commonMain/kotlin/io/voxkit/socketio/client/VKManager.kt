package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.http.*
import io.voxkit.engineio.client.DisconnectReason
import io.voxkit.engineio.client.Engine
import io.voxkit.engineio.client.engineIO
import io.voxkit.socketio.client.Manager.Event
import io.voxkit.socketio.client.parser.DefaultParser
import io.voxkit.socketio.client.parser.Packet
import io.voxkit.socketio.client.parser.Parser
import io.voxkit.socketio.logging.VoxKitLoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import io.voxkit.engineio.parser.Packet as EnginePacket

internal class VKManager(
    private val serverUrl: Url,
    private val ioOptions: IOOptions,
    val options: ManagerOptions,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val loggerFactory: VoxKitLoggerFactory,
) : Manager {

    private val _events = MutableSharedFlow<Event>()
    override val events: Flow<Event> = _events.asSharedFlow()

    private val _incoming = MutableSharedFlow<Packet>()
    val incoming: Flow<Packet> = _incoming.asSharedFlow()

    private val state = MutableStateFlow<State>(State.New)
    val connected: Boolean get() = state.value == State.Connected

    var recovered: Boolean = false
        private set

    private val logger = loggerFactory.createLogger("Manager@${hashCode()}")
    private val parser = DefaultParser()
    private var reconnectionAttemptCount = 0

    private val _engine = MutableStateFlow<Engine?>(null)

    // Visible for testing
    val engine: StateFlow<Engine?> = _engine.asStateFlow()

    // TODO: atomic
    private val sockets = mutableMapOf<String, VKSocket>()
    private val connectedSockets = MutableStateFlow<Set<String>>(emptySet())
    private val job = SupervisorJob() + CoroutineName("Manager@${hashCode()}")
    private val mutex = Mutex()

    init {
        launchObservingConnectedSockets()
        if (options.reconnection) launchReconnectionLoop()
        if (options.autoConnect) scope.launch(job, start = CoroutineStart.UNDISPATCHED) { connect() }
    }

    private fun launchReconnectionLoop() {
        scope.launch(job, start = CoroutineStart.UNDISPATCHED) {
            state.first { it is State.Connected }
            while (true) {
                state.first { it is State.Disconnected }
                connectedSockets.first { it.isNotEmpty() }
                connectAsync(recovering = true)
                state.first { it is State.Connected }
            }
        }
    }

    private fun launchObservingConnectedSockets() {
        scope.launch(job, start = CoroutineStart.UNDISPATCHED) {
            connectedSockets
                .drop(1) // skip initial value
                .collect { namespaces ->
                    if (namespaces.isEmpty()) {
                        logger.d { "There is no connected sockets anymore. Disconnect manger." }
                        disconnect()
                    } else if (state.value !is State.Connected && state.value !is State.Connecting) {
                        logger.d { "We have at least one connected socket, but engine is not opened. Connect manager." }
                        connectAsync(recovering = false)
                    }
                }
        }
    }

    override suspend fun connect() = coroutineScope {
        val connected = launch(job) { state.first { it == State.Connected } }
        val disconnected = async(job) { state.filterIsInstance<State.Disconnected>().first() }

        connectAsync(false)

        select {
            connected.onJoin { }
            disconnected.onAwait { throw it.cause }
        }
        coroutineContext.cancelChildren()
    }

    private fun connectAsync(recovering: Boolean) = scope.launch(job) {
        if (state.value == State.Connected) return@launch

        mutex.withLock {
            if (state.value != State.New && state.value !is State.Disconnected) {
                // already connecting or connected
                return@launch
            }
            state.value = State.Connecting

            logger.d { "Connect socket.io manager. Recovering: $recovering" }

            reconnectionAttemptCount = if (recovering) 1 else 0

            val connectionResult = async { connectWithRetries() }
            val disconnected = async {
                val state = state.filterIsInstance<State.Disconnected>().first()
                Result.failure<Engine>(state.cause)
            }
            val result: Result<Engine> = select {
                connectionResult.onAwait { it }
                disconnected.onAwait { it }
            }
            coroutineContext.cancelChildren()

            _engine.value = result
                .onSuccess { engine ->
                    logger.d { "Connection succeed with number of reconnect attempts: $reconnectionAttemptCount" }
                    recovered = recovering
                    state.value = State.Connected
                    if (reconnectionAttemptCount > 0) {
                        _events.emit(Event.Reconnect(reconnectionAttemptCount))
                    }
                    launchReceivingEngineIOPackets(engine)
                    if (recovering) reconnectSockets()
                }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        logger.w(e) { "Connection failed after reconnect attempts: $reconnectionAttemptCount" }
                    }
                    recovered = false
                    _events.emit(Event.ReconnectionFailed)
                    state.value = State.Disconnected(DisconnectReason.TRANSPORT_ERROR, e)
                }
                .getOrNull()
        }
    }

    private fun reconnectSockets() {
        sockets
            .filter { (namespace) -> connectedSockets.value.contains(namespace) }
            .forEach { (_, socket) -> scope.launch(job) { socket.sendConnectPacket() } }
    }

    private fun disconnect() {
        logger.i { "Disconnect socket.io manager" }
        state.value = State.Disconnected(
            DisconnectReason.CLIENT_DISCONNECT,
            CancellationException("Client disconnect")
        )
        _engine.value?.close()
        _engine.value = null
    }

    private suspend fun connectWithRetries(): Result<Engine> {
        var error: Throwable? = null

        while (reconnectionAttemptCount <= options.reconnectionAttempts) {
            if (reconnectionAttemptCount > 0) {
                logger.d { "Reconnect. Attempt: $reconnectionAttemptCount" }
                _events.emit(Event.ReconnectAttempt(reconnectionAttemptCount))
            } else {
                logger.d { "Connect. Attempt: $reconnectionAttemptCount" }
            }

            val engine = createEngineIO()

            val engineState = runCatching {
                withTimeoutOrNull(options.timeout) {
                    engine.state.first { it == Engine.State.Open || it is Engine.State.Closed }
                }
            }
                .onFailure { e ->
                    if (e is CancellationException) {
                        engine.close()
                        throw e
                    }
                }
                .getOrElse { Engine.State.Closed(DisconnectReason.TRANSPORT_ERROR, it) }

            if (engineState == Engine.State.Open) {
                return Result.success(engine)
            }

            engineState as Engine.State.Closed
            val message = "Connection attempt failed: ${engineState.reason} ${engineState.cause?.message}"
            engineState.cause?.let { logger.w(it) { message } } ?: logger.w { message }

            val e = SocketConnectException(message).also { error = it }
            if (reconnectionAttemptCount == 0) {
                _events.emit(Event.Error(e))
            } else {
                _events.emit(Event.ReconnectError(e))
            }

            if (options.reconnection) {
                val duration = options.calculateReconnectionDelay(reconnectionAttemptCount)
                logger.d { "Try to reconnect in $duration" }
                delay(duration.coerceAtMost(options.reconnectionDelayMax))
            } else {
                break
            }

            reconnectionAttemptCount++
        }

        return Result.failure(error!!)
    }

    private fun createEngineIO(): Engine {
        return engineIO(serverUrl, httpClient) {
            path = options.path
            headers.appendAll(options.headers)
            parameters.appendAll(options.parameters)
            timestampParam = takeIf { options.timestampRequests }?.let { options.timestampParam }
            transports = options.transports
            loggingLevel = ioOptions.engineLoggingLevel
            logger = ioOptions.logger
            dispatcher = ioOptions.dispatcher
        }
    }

    private fun launchReceivingEngineIOPackets(engine: Engine) {
        scope.launch(job) {
            runCatching {
                for (engineIoPacket in engine.incoming) {
                    onEngineIOPacket(engineIoPacket, engine.incoming)
                }
            }.recoverCatching { e ->
                when (e) {
                    is ClosedReceiveChannelException,
                    is CancellationException -> {
                        if (_engine.value == engine && state.value !is State.Disconnected) {
                            logger.d { "engine.io incoming packets channel closed. Transit Manager to DISCONNECTED state." }
                            state.value = State.Disconnected(DisconnectReason.TRANSPORT_CLOSE, e)
                            engine.close()
                            _engine.value = null
                        } else {
                            throw e
                        }
                    }

                    else -> throw e
                }
            }.getOrThrow()

        }
    }

    private suspend fun onEngineIOPacket(enginePacket: EnginePacket, next: ReceiveChannel<EnginePacket>) {
        when (enginePacket) {
            is EnginePacket.Message -> {
                val packet = runCatching { decodeEngineIOPacket(enginePacket, next) }
                    .getOrElse {
                        logger.w(it) { "Failed to decode engine.io packet: $enginePacket. Discard it." }
                        return
                    }
                logger.d { "client <== server: $packet" }
                _incoming.emit(packet)
            }

            is EnginePacket.Ping -> _events.emit(Event.Ping)

            else -> logger.w { "Discard unexpected engine.io packet: $enginePacket" }
        }
    }

    private suspend fun decodeEngineIOPacket(
        enginePacket: EnginePacket.Message,
        next: ReceiveChannel<EnginePacket>
    ): Packet {
        var packet = parser.decode(enginePacket.data)

        if (packet.type == Packet.Type.BINARY_EVENT || packet.type == Packet.Type.BINARY_ACK) {
            var decoded: Parser.Decoded = Parser.Decoded.Partial(packet)
            while (decoded !is Parser.Decoded.Completed) {
                val nextEngineIoPacket = next.receive()
                check(nextEngineIoPacket is EnginePacket.Binary)
                decoded = parser.decodeBinary(nextEngineIoPacket.data, decoded)
            }
            packet = decoded.packet
        }

        return packet
    }

    override fun socket(namespace: String, auth: AuthSocketOption?): Socket {
        return sockets.getOrPut(namespace) {
            VKSocket(
                options = options.socketOption,
                namespace = namespace,
                manager = this,
                auth = auth,
                scope = scope,
                loggerFactory = loggerFactory
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun send(packet: Packet) {
        if (packet.type == Packet.Type.CONNECT) {
            connectedSockets.value += packet.namespace
        }

        // wait for engine.io to be opened
        val engine = _engine
            .filterNotNull()
            .flatMapLatest { eng -> eng.state.map { it to eng } }
            .filter { it.first == Engine.State.Open }
            .map { it.second }
            .first()


        try {
            when (val encoded = parser.encode(packet)) {
                is Parser.Encoded.Binary -> {
                    engine.send(encoded.buffers[0].decodeToString())
                    encoded.buffers.drop(1).forEach { engine.send(it) }
                }

                is Parser.Encoded.Text -> engine.send(encoded.data)
            }

            logger.d { "client ==> server: $packet" }
        } finally {
            if (packet.type == Packet.Type.DISCONNECT) {
                connectedSockets.value -= packet.namespace
            }
        }
    }

    override fun close() {
        logger.d { "Close socket.io manager." }
        sockets.values.forEach { it.close() }
        job.cancel()
        state.value = State.Disconnected(DisconnectReason.CLIENT_DISCONNECT, CancellationException("Manager closed"))
        _engine.value?.close()
        _engine.value = null
    }

    private sealed interface State {
        data object New : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: DisconnectReason, val cause: Throwable) : State
    }
}
