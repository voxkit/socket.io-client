//package io.voxkit.socketio.client
//
//import io.ktor.client.*
//import io.voxkit.engineio.client.EngineIOSession
//import io.voxkit.engineio.client.EngineIOSocketClosedException
//import io.voxkit.engineio.client.engineIOHttpClient
//import io.voxkit.engineio.client.engineIOSession
//import io.voxkit.engineio.parser.Packet
//import kotlinx.coroutines.*
//import kotlinx.coroutines.channels.Channel
//import kotlinx.coroutines.flow.MutableSharedFlow
//import kotlinx.coroutines.flow.SharedFlow
//import kotlinx.coroutines.flow.asSharedFlow
//import kotlin.coroutines.CoroutineContext
//import kotlin.math.min
//import kotlin.math.pow
//import kotlin.random.Random
//
//internal class ManagerImpl(
//    private val uri: String,
//    private val options: ManagerOptions
//) : Manager, CoroutineScope {
//    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
//
//    private val httpClient: HttpClient = engineIOHttpClient()
//    private var engineSession: EngineIOSession? = null
//    private val sockets = mutableMapOf<String, SocketImpl>()
//    private var reconnectionAttempts = 0
//
//    private val _events = MutableSharedFlow<Manager.Event>(extraBufferCapacity = 10)
//    val events: SharedFlow<Manager.Event> = _events.asSharedFlow()
//
//    private var reconnectJob: Job? = null
//    private var connectingPromise: CompletableDeferred<Unit>? = null
//
//    init {
//        if (options.autoConnect) {
//            launch { connect() }
//        }
//    }
//
//    override suspend fun connect() {
//        if (engineSession != null || connectingPromise != null) return
//
//        val connecting = CompletableDeferred<Unit>()
//        connectingPromise = connecting
//
//        try {
//            val session = httpClient.engineIOSession(uri) {
//                // Configure engine.io options if needed
//            }
//            engineSession = session
//
//            // Handle incoming packets
//            launch {
//                try {
//                    processIncomingPackets(session)
//                } catch (e: Exception) {
//                    if (e !is CancellationException) {
//                        maybeReconnect(e)
//                    }
//                }
//            }
//
//            reconnectionAttempts = 0
//            connecting.complete(Unit)
//        } catch (e: Exception) {
//            connecting.completeExceptionally(e)
//            maybeReconnect(e)
//        } finally {
//            connectingPromise = null
//        }
//    }
//
//    private suspend fun processIncomingPackets(session: EngineIOSession) {
//        for (packet in session.incoming) {
//            when (packet) {
//                is Packet.Message -> {
//                    val message = packet.data as? String ?: continue
//                    // TODO: Decode Socket.IO packet and dispatch to appropriate socket
//                    // Each Socket.IO packet contains namespace and event/data
//                }
//                is Packet.Binary -> {
//                    // Handle binary data
//                }
//                else -> {
//                    // Engine.IO specific packets are handled internally by the Engine.IO client
//                }
//            }
//        }
//    }
//
//    override suspend fun socket(namespace: String, auth: AuthSocketOption?): Socket {
//        val fullNamespace = if (namespace.startsWith("/")) namespace else "/$namespace"
//
//        return sockets.getOrPut(fullNamespace) {
//            SocketImpl(
//                namespace = fullNamespace,
//                manager = this,
//                auth = auth
//            ).also {
//                // Connect the socket if engine is already connected
//                engineSession?.let { _ ->
//                    it.onConnect()
//                }
//            }
//        }
//    }
//
//    private suspend fun maybeReconnect(cause: Throwable) {
//        if (!options.reconnection || reconnectionAttempts >= options.reconnectionAttempts) {
//            _events.emit(Manager.Event.ReconnectionFailed)
//            return
//        }
//
//        // Clean up existing session
//        engineSession?.close()
//        engineSession = null
//
//        // Start reconnection process
//        if (reconnectJob?.isActive != true) {
//            reconnectJob = launch {
//                reconnectionAttempts++
//                _events.emit(Manager.Event.ReconnectAttempt(reconnectionAttempts))
//
//                val delay = calculateBackoff(
//                    attempt = reconnectionAttempts,
//                    baseDelay = options.reconnectionDelay.inWholeMilliseconds,
//                    maxDelay = options.reconnectionDelayMax.inWholeMilliseconds,
//                    factor = options.randomizationFactor
//                )
//
//                delay(delay)
//
//                try {
//                    connect()
//                    _events.emit(Manager.Event.Reconnect(reconnectionAttempts))
//                } catch (e: Exception) {
//                    _events.emit(Manager.Event.ReconnectError(e))
//                    maybeReconnect(e)
//                }
//            }
//        }
//    }
//
//    private fun calculateBackoff(
//        attempt: Int,
//        baseDelay: Long,
//        maxDelay: Long,
//        factor: Double
//    ): Long {
//        val calculatedDelay = min(maxDelay.toDouble(), baseDelay * 2.0.pow(attempt - 1))
//        val jitter = 1 + factor - Random.nextDouble() * factor * 2
//        return (calculatedDelay * jitter).toLong()
//    }
//
//    internal suspend fun send(namespace: String, packetData: String) {
//        val session = engineSession ?: throw EngineIOSocketClosedException()
//        // Format data according to Socket.IO protocol and send via Engine.IO
//        session.send(packetData)
//    }
//
//    internal fun close() {
//        launch {
//            reconnectJob?.cancel()
//            engineSession?.close()
//            engineSession = null
//
//            // Notify all sockets
//            sockets.values.forEach { it.onDisconnect("io client disconnect", null) }
//            sockets.clear()
//
//            cancel() // Cancel the scope
//        }
//    }
//
//    // Socket implementation
//    private inner class SocketImpl(
//        private val namespace: String,
//        private val manager: ManagerImpl,
//        private val auth: AuthSocketOption?
//    ) : Socket {
//        override val active: Boolean = true
//        override var connected: Boolean = false
//            private set
//        override val disconnected: Boolean
//            get() = !connected
//        override val id: String? = null  // Will be set after connection
//        override val io: Manager = manager
//
//        private val _events = Channel<Socket.Event>(Channel.BUFFERED)
//        val events: Channel<Socket.Event> = _events
//
//        internal suspend fun onConnect() {
//            connected = true
//            _events.send(Socket.Event.Connect)
//        }
//
//        internal suspend fun onDisconnect(reason: String, cause: Throwable?) {
//            connected = false
//            _events.send(Socket.Event.Disconnect(reason, cause))
//        }
//    }
//}
