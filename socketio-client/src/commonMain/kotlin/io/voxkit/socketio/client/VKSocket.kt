package io.voxkit.socketio.client

import io.voxkit.engineio.client.CloseReason
import io.voxkit.socketio.client.Socket.Ack
import io.voxkit.socketio.client.Socket.Event
import io.voxkit.socketio.client.parser.Packet
import io.voxkit.socketio.client.util.dataOf
import io.voxkit.socketio.client.util.decodeJsonOrNull
import io.voxkit.socketio.client.util.jsonElementOrNull
import io.voxkit.socketio.client.util.stringOrNull
import io.voxkit.socketio.logging.VoxKitLoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

internal class VKSocket(
    private val options: SocketOptions,
    private val namespace: String,
    private val manager: VKManager,
    private val auth: AuthSocketOption?,
    private val scope: CoroutineScope,
    loggerFactory: VoxKitLoggerFactory,
) : Socket {

    override var id: String? = null
        private set

    override val active: Boolean
        get() {
            val currentState = state.value
            if (currentState !is State.Disconnected) return true

            return when (currentState.reason) {
                CloseReason.CLIENT_DISCONNECT,
                CloseReason.SERVER_DISCONNECT -> false

                else -> true
            }
        }

    override val connected: Boolean get() = state.value == State.Connected && manager.connected
    override val disconnected: Boolean get() = connected.not()
    override val io: Manager = manager
    override val recovered: Boolean get() = manager.recovered

    private val _events = MutableSharedFlow<Event>()
    override val events: Flow<Event> by lazy { merge(_events, incomingPackets.customEvents()) }

    private val logger = loggerFactory.createLogger("Socket@${hashCode()} [$namespace]")
    private val job = SupervisorJob() + CoroutineName("Socket@${hashCode()}")

    // TODO: atomic
    private var ackId = 0L
    private val incomingPackets = manager.incoming.filter { it.namespace == namespace }
    private val outgoingPackets = Channel<Packet>(capacity = Channel.UNLIMITED)
    private val connectMutex = Mutex()
    private val disconnectMutex = Mutex()
    private val state = MutableStateFlow<State>(State.New)

    init {
        launchSendingPackets()
        launchReceivingConnectionPackets()
    }

    private fun Flow<Packet>.customEvents(): Flow<Event> {
        fun Packet.toCustomEventOrNull(): Event? {
            require(type == Packet.Type.EVENT || type == Packet.Type.BINARY_EVENT) { "Packet type is not EVENT or BINARY_EVENT" }

            val event = data?.firstOrNull()?.jsonElementOrNull?.stringOrNull ?: run {
                logger.w { "EVENT packet doesn't contain event name in data. Discard it. $data" }
                return null
            }

            val ack = ackId?.let {
                Ack { args ->
                    val hasBinaryData = args.any { it is Packet.Data.Binary }
                    val ackType = if (hasBinaryData) Packet.Type.BINARY_ACK else Packet.Type.ACK
                    val ackPacket = Packet(ackType, namespace, args.toList(), ackId)
                    outgoingPackets.send(ackPacket)
                }
            }

            return Event.Custom(event, args = data.drop(1), ack = ack)
        }

        return filter { it.namespace == namespace }.mapNotNull { packet ->
            when (packet.type) {
                Packet.Type.EVENT, Packet.Type.BINARY_EVENT -> packet.toCustomEventOrNull()
                else -> null
            }
        }
    }

    private fun launchSendingPackets() {
        scope.launch(job) {
            try {
                for (packet in outgoingPackets) {
                    state.first { it == State.Connected || it == State.Disconnecting }
                    if (packet.type == Packet.Type.DISCONNECT) {
                        state.value = State.Disconnected(
                            CloseReason.CLIENT_DISCONNECT,
                            CancellationException("Client disconnect")
                        )
                        _events.emit(Event.Disconnect(CloseReason.CLIENT_DISCONNECT, null))
                    }
                    sendPacketToManager(packet)
                    if (packet.type == Packet.Type.DISCONNECT) {
                        logger.d { "Socket disconnected." }
                    }
                }
            } finally {
                outgoingPackets.cancel()
            }
        }
    }

    private suspend fun sendPacketToManager(packet: Packet) {
        var sent = false
        var attempt = 0
        while (sent.not()) {
            logger.d { "Send packet: $packet" }
            sent = runCatching {
                manager.send(packet)
                true
            }.recoverCatching { e ->
                if (e is CancellationException && coroutineContext.isActive.not()) throw e
                if (attempt++ == options.retries) {
                    logger.w { "Sending packet failed. Max retries reached. Discarding packet." }
                    true
                } else {
                    logger.d(e) { "Sending packet failed. Will try to send it again." }
                    false
                }

            }.getOrThrow()
        }
    }

    private fun launchReceivingConnectionPackets() {
        scope.launch {
            incomingPackets.collect { packet ->
                when (packet.type) {
                    Packet.Type.CONNECT -> onConnectSuccess(packet)
                    Packet.Type.DISCONNECT -> TODO("Handling DISCONNECT packet is not implemented yet!!!!")
                    Packet.Type.CONNECT_ERROR -> onConnectError(packet)
                    else -> Unit // ignore other packets
                }
            }
        }
    }

    private suspend fun onConnectSuccess(packet: Packet) {
        val packetData = packet.data?.firstOrNull() as? Packet.Data.Json
        val success = packetData?.decodeJsonOrNull<ConnectSuccess>()
        logger.d { "Socket connected to namespace [$namespace]. SID: ${success?.sid}" }
        id = success?.sid
        state.value = State.Connected
        _events.emit(Event.Connect)
    }

    private suspend fun onConnectError(packet: Packet) {
        val packetData = packet.data?.firstOrNull() as? Packet.Data.Json
        val error = packetData?.decodeJsonOrNull<ConnectError>()
        logger.d { "Socket connection to namespace [$namespace] failed: ${error?.message}" }
        val e = SocketConnectException(error?.message ?: "Unknown error")
        state.value = State.Disconnected(CloseReason.SERVER_DISCONNECT, e)
        _events.emit(Event.ConnectError(e))
    }

    override suspend fun connect() = coroutineScope {
        val connected = launch(job, start = CoroutineStart.UNDISPATCHED) {
            state.filterIsInstance<State.Connected>().first()
        }
        val disconnected = async(job, start = CoroutineStart.UNDISPATCHED) {
            state.filterIsInstance<State.Disconnected>().first()
        }

        connectAsync()

        val result: Result<Unit> = select {
            connected.onJoin { Result.success(Unit) }
            disconnected.onAwait { Result.failure(it.cause) }
        }
        coroutineContext.cancelChildren()
        result.getOrThrow()
    }

    private fun connectAsync() = scope.launch(job) {
        if (state.value == State.Connecting || state.value == State.Connected) return@launch

        connectMutex.withLock {
            if (state.value != State.New && state.value !is State.Disconnected) return@launch
            state.value = State.Connecting
            logger.i { "Connect socket to namespace [$namespace]" }
            sendConnectPacket()
        }
    }

    suspend fun sendConnectPacket() {
        runCatching {
            val data = (auth ?: options.auth)?.let { dataOf(mapOf(it.paramName to it.token)) }
            manager.send(Packet(Packet.Type.CONNECT, namespace, data))
        }
    }

    override suspend fun disconnect() {
        disconnectAsync()
        state.filterIsInstance<State.Disconnected>().first()
    }

    private fun disconnectAsync() = scope.launch(job) {
        if (state.value == State.Disconnecting || state.value is State.Disconnected) return@launch

        disconnectMutex.withLock {
            if (state.value != State.Connecting && state.value != State.Connected) return@launch
            state.value = State.Disconnecting

            logger.d { "Disconnect socket" }
            sendPacket(Packet(Packet.Type.DISCONNECT, namespace))
        }
    }

    override suspend fun send(event: String, vararg args: Packet.Data) {
        logger.i { "Send event: $event $args" }
        val packet = Packet(type = Packet.Type.EVENT, namespace = namespace, data = dataOf(event) + args.toList())
        sendPacket(packet)
    }

    override suspend fun sendWithAck(event: String, vararg args: Packet.Data): List<Packet.Data> = coroutineScope {
        logger.i { "Send event with ack: $event $args" }
        val packet = Packet(
            type = Packet.Type.EVENT,
            namespace = namespace,
            data = dataOf(event) + args.toList(),
            ackId = ackId++,
        )
        sendPacketWithAck(packet).data ?: emptyList()
    }

    private suspend fun sendPacketWithAck(packet: Packet): Packet = coroutineScope {
        val ackPacket = async(start = CoroutineStart.UNDISPATCHED) {
            incomingPackets
                .filter { it.type == Packet.Type.ACK || it.type == Packet.Type.BINARY_ACK }
                .first { it.ackId == packet.ackId }
        }
        sendPacket(packet)
        withTimeout(options.ackTimeout) { ackPacket.await() }
    }

    private suspend fun sendPacket(packet: Packet) {
        outgoingPackets.send(packet)
    }

    fun close() {
        logger.i { "Close socket" }
        scope.launch {
            disconnect()
            job.cancel()
        }
    }

    suspend fun onManagerConnectError(reason: CloseReason, cause: Throwable) {
        if (state.value == State.Connecting) {
            state.value = State.Disconnected(reason, cause)
            _events.emit(Event.ConnectError(cause))
        }
    }

    private interface State {
        data object New : State
        data object Connecting : State
        data object Connected : State
        data object Disconnecting : State
        data class Disconnected(val reason: CloseReason, val cause: Throwable) : State
    }
}

@Serializable
internal data class ConnectSuccess(val sid: String)

@Serializable
internal data class ConnectError(val message: String)
