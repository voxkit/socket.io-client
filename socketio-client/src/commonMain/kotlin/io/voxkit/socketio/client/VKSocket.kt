package io.voxkit.socketio.client

import io.voxkit.engineio.client.DisconnectReason
import io.voxkit.socketio.client.Manager.State
import io.voxkit.socketio.client.Socket.Event
import io.voxkit.socketio.client.parser.Packet
import io.voxkit.socketio.client.util.dataOf
import io.voxkit.socketio.client.util.decodeJsonOrNull
import io.voxkit.socketio.client.util.jsonElementOrNull
import io.voxkit.socketio.client.util.stringOrNull
import io.voxkit.socketio.logging.VoxKitLoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

internal class VKSocket(
    private val namespace: String,
    private val manager: VKManager,
    private val auth: AuthSocketOption?,
    private val scope: CoroutineScope,
    loggerFactory: VoxKitLoggerFactory,
) : Socket {

    override var active: Boolean = false
        private set

    private var _connected = MutableStateFlow(false)
    override val connected: Boolean get() = _connected.value && manager.state.value == State.Connected

    override val disconnected: Boolean get() = connected.not()

    override var id: String? = null
        private set

    override val io: Manager = manager

    override val recovered: Boolean get() = manager.recovered

    private val _events = MutableSharedFlow<Event>()
    override val events: Flow<Event> = merge(_events, manager.incoming.customEvents())

    private val logger = loggerFactory.createLogger("socket.io [$namespace]")

    // TODO: atomic
    private var ackId = 0L
    private val incomingFlow = manager.incoming.filter { it.namespace == namespace }
    private val outgoingChannel = MutableStateFlow<Channel<Packet>?>(null)
    private val outgoingDispatcher = Dispatchers.Default.limitedParallelism(1, "OutgoingDispatcher")

    init {
        startConnectionLoop()
        startSendingPackets()

        scope.launch {
            try {
                awaitCancellation()
            } finally {
                outgoingChannel.value?.cancel()
            }
        }
    }

    private fun startConnectionLoop() {
        scope.launch {
            manager.state.first { it is State.Connected }

            while (true) {
                val disconnected = manager.state.first { it is State.Disconnected } as State.Disconnected
                if (_connected.value) {
                    _events.emit(Event.Disconnect(disconnected.reason, disconnected.cause))
                    _connected.value = false
                }
                manager.state.first { it is State.Connected }
                if (active) runCatching { connect() }
            }
        }
    }

    private fun startSendingPackets() {
        suspend fun sendPacketToManager(packet: Packet) {
            if (_connected.value.not()) {
                logger.d { "Send packet. Manager is not connected yet. Wait for connection." }
            }

            _connected.first { it }
            logger.d { "Send packet: $packet" }

            runCatching {
                manager.send(packet)
            }.onFailure {
                logger.w(it) { "Sending packet failed. Will try to send it again." }
                sendPacketToManager(packet)
            }
        }

        scope.launch {
            outgoingChannel.filterNotNull().collect { channel ->
                logger.d { "New outgoing packets channel created." }
                runCatching {
                    for (packet in channel) {
                        sendPacketToManager(packet)
                    }
                }
                logger.d { "Outgoing packets channel closed." }
            }
        }
    }

    private fun Flow<Packet>.customEvents(): Flow<Event> {
        fun Packet.toCustomEventOrNull(): Event? {
            require(type == Packet.Type.EVENT || type == Packet.Type.BINARY_EVENT) { "Packet type is not EVENT or BINARY_EVENT" }

            val event = data?.firstOrNull()?.jsonElementOrNull?.stringOrNull ?: run {
                logger.w { "EVENT packet doesn't contain event name in data. Discard it. $data" }
                return null
            }

            val ack = ackId?.let {
                Socket.Ack { args ->
                    val hasBinaryData = args.any { it is Packet.Data.Binary }
                    val ackType = if (hasBinaryData) Packet.Type.BINARY_ACK else Packet.Type.ACK
                    val ackPacket = Packet(ackType, namespace, args.toList(), ackId)
                    outgoingChannel.value?.send(ackPacket)
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

    override fun connect(): Job = scope.launch {
        manager.connect()

        val data = auth?.let { dataOf(mapOf(it.paramName to JsonPrimitive(it.token))) }

        val connectAck = async(start = CoroutineStart.UNDISPATCHED) {
            incomingFlow.first { it.type == Packet.Type.CONNECT || it.type == Packet.Type.CONNECT_ERROR }
        }

        manager.send(Packet(Packet.Type.CONNECT, namespace, data))

        val connectResult = try {
            withTimeout(manager.options.timeout) { connectAck.await() }
        } catch (e: TimeoutCancellationException) {
            val exception = SocketConnectException("Connection timed out")
            _events.emit(Event.ConnectError(exception))
            throw e
        }

        when (connectResult.type) {
            Packet.Type.CONNECT -> {
                val packetData = connectResult.data?.firstOrNull() as? Packet.Data.Json
                val success = packetData?.decodeJsonOrNull<ConnectSuccess>()
                id = success?.sid
                outgoingChannel.value = Channel(capacity = Channel.UNLIMITED)
                active = true
                _connected.value = true
                _events.emit(Event.Connect)
            }

            Packet.Type.CONNECT_ERROR -> {
                val packetData = connectResult.data?.firstOrNull() as? Packet.Data.Json
                val error = packetData?.decodeJsonOrNull<ConnectError>()
                val e = SocketConnectException(error?.message ?: "Unknown error")
                _events.emit(Event.ConnectError(e))
                throw e
            }

            else -> {
                val e = SocketConnectException("Unexpected packet type: ${connectResult.type}")
                _events.emit(Event.ConnectError(e))
                throw e
            }
        }
    }

    override fun disconnect(): Job {
        if (_connected.value.not()) return Job().apply { complete() }
        _connected.value = false
        active = false
        return scope.launch {
            _events.emit(Event.Disconnect(DisconnectReason.CLIENT_REQUEST, null))
            outgoingChannel.value?.send(Packet(Packet.Type.DISCONNECT, namespace))
            outgoingChannel.value?.close()
        }
    }

    override fun send(event: String, vararg args: Packet.Data): Job {
        check(active) { "Socket is not active" }
        val packet = Packet(
            type = Packet.Type.EVENT,
            namespace = namespace,
            data = dataOf(event) + args.toList(),
        )
        return sendPacket(packet)
    }

    override suspend fun sendWithAck(event: String, vararg args: Packet.Data): List<Packet.Data> {
        check(active) { "Socket is not active" }
        val packet = Packet(
            type = Packet.Type.EVENT,
            namespace = namespace,
            data = dataOf(event) + args.toList(),
            ackId = ackId++,
        )
        val ackPacket = scope.async { sendPacketWithAck(packet) }
        return ackPacket.await().data ?: emptyList()
    }

    private suspend fun sendPacketWithAck(packet: Packet): Packet = coroutineScope {
        val ackPacket = async(start = CoroutineStart.UNDISPATCHED) {
            incomingFlow
                .filter { it.type == Packet.Type.ACK || it.type == Packet.Type.BINARY_ACK }
                .first { it.ackId == packet.ackId }
        }
        sendPacket(packet)
        ackPacket.await()
    }

    private fun sendPacket(packet: Packet): Job = scope.launch(outgoingDispatcher) {
        outgoingChannel.value?.send(packet)
    }
}

@Serializable
internal data class ConnectSuccess(val sid: String)

@Serializable
internal data class ConnectError(val message: String)
