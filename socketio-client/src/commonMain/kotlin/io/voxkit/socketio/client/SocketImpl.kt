package io.voxkit.socketio.client

import io.voxkit.socketio.client.Manager.State
import io.voxkit.socketio.client.Socket.Event
import io.voxkit.socketio.client.parser.DefaultParser
import io.voxkit.socketio.client.parser.Packet
import io.voxkit.socketio.client.util.stringOrNull
import io.voxkit.socketio.client.util.dataOf
import io.voxkit.socketio.client.util.jsonElementOrNull
import io.voxkit.socketio.logging.VoxKitLoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

internal class SocketImpl(
    private val namespace: String,
    private val manager: ManagerImpl,
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
    private val incoming = manager.incoming.filter { it.namespace == namespace }
    private val outgoing = Channel<Packet>(capacity = Channel.UNLIMITED)

    init {
        startConnectionLoop()
        startSendingPackets()
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
            while (true) {
                for (packet in outgoing) {
                    sendPacketToManager(packet)
                }
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
                Socket.Ack {
                    val ackType = if (type == Packet.Type.EVENT) Packet.Type.ACK else Packet.Type.BINARY_ACK
                    val ackPacket = Packet(ackType, namespace, it.toList(), ackId)
                    outgoing.send(ackPacket)
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


    override suspend fun connect() = coroutineScope {
        manager.connect()

        val data = auth?.let { dataOf(mapOf(it.paramName to JsonPrimitive(it.token))) }

        val connectAck = async(start = CoroutineStart.UNDISPATCHED) {
            incoming.first { it.type == Packet.Type.CONNECT || it.type == Packet.Type.CONNECT_ERROR }
        }

        manager.send(Packet(Packet.Type.CONNECT, namespace, data))

        val ack = try {
            withTimeout(manager.options.timeout) { connectAck.await() }
        } catch (e: TimeoutCancellationException) {
            val exception = SocketIOConnectException("Connection timed out")
            _events.emit(Event.ConnectError(exception))
            throw e
        }

        when (ack.type) {
            Packet.Type.CONNECT -> {
                val packetData = ack.data?.firstOrNull() as? Packet.Data.Json
                val success = packetData?.element?.let { DefaultParser.JSON.decodeFromJsonElement<ConnectSuccess>(it) }
                id = success?.sid
                active = true
                _connected.value = true
                _events.emit(Event.Connect)
            }

            Packet.Type.CONNECT_ERROR -> {
                val packetData = ack.data?.firstOrNull() as? Packet.Data.Json
                val error = packetData?.element?.let { DefaultParser.JSON.decodeFromJsonElement<ConnectError>(it) }
                val e = SocketIOConnectException(error?.message ?: "Unknown error")
                _events.emit(Event.ConnectError(e))
                throw e
            }

            else -> {
                val e = SocketIOConnectException("Unexpected packet type: ${ack.type}")
                _events.emit(Event.ConnectError(e))
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        if (_connected.value.not()) return
        _connected.value = false
        active = false
        _events.emit(Event.Disconnect("io client disconnect", null))
        outgoing.send(Packet(Packet.Type.DISCONNECT, namespace))
    }

    override suspend fun send(event: String, vararg args: Packet.Data) {
        check(active) { "Socket is not active" }
        val packet = Packet(
            type = Packet.Type.EVENT,
            namespace = namespace,
            data = dataOf(event) + args.toList(),
        )
        outgoing.send(packet)
    }

    override suspend fun sendWithAck(event: String, vararg args: Packet.Data): List<Packet.Data> {
        check(active) { "Socket is not active" }
        val packet = Packet(
            type = Packet.Type.EVENT,
            namespace = namespace,
            data = dataOf(event) + args.toList(),
            ackId = ackId++,
        )
        val ackPacket = sendPacketWithAck(packet)
        return ackPacket.data ?: emptyList()
    }

    private suspend fun sendPacketWithAck(packet: Packet): Packet = coroutineScope {
        val ackPacket = async(start = CoroutineStart.UNDISPATCHED) {
            incoming.filter { it.type == Packet.Type.ACK || it.type == Packet.Type.BINARY_ACK }
                .first { it.ackId == ackId }
        }
        outgoing.send(packet)
        ackPacket.await()
    }

    override fun close() {
        outgoing.cancel()
    }
}

@Serializable
internal data class ConnectSuccess(val sid: String)

@Serializable
internal data class ConnectError(val message: String)
