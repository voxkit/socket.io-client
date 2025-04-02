package io.voxkit.socketio.client

import io.voxkit.socketio.client.Manager.State
import io.voxkit.socketio.client.Socket.Event
import io.voxkit.socketio.client.parser.DefaultParser
import io.voxkit.socketio.client.parser.Packet
import io.voxkit.socketio.client.parser.asPacketData
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
    override val events: Flow<Event> = _events.asSharedFlow()

    private val logger = loggerFactory.createLogger("socket.io [$namespace]")

    // TODO: atomic
    private var ackId = 0
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
        scope.launch {
            while (true) {
                for (packet in outgoing) {
                    sendPacket(packet)
                }
            }
        }
    }

    private suspend fun sendPacket(packet: Packet) {
        if (_connected.value.not()) {
            logger.d { "Send packet. Manager is not connected yet. Wait for connection." }
        }

        _connected.first { it }
        logger.d { "Socket connected. Send packet: $packet" }

        runCatching {
            manager.send(packet)
        }.onFailure {
            logger.w(it) { "Sending packet failed. Will try to send it again." }
            sendPacket(packet)
        }
    }

    override suspend fun connect() = coroutineScope {
        manager.connect()

        val data = auth?.let {
            val authData = JsonObject(mapOf(it.paramName to JsonPrimitive(it.token))).asPacketData()
            listOf(authData)
        }

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
            data = listOf(event.asPacketData()) + args.toList(),
        )
        outgoing.send(packet)
    }

    override suspend fun sendWithAck(event: String, vararg args: Packet.Data): List<Packet.Data> {
        check(active) { "Socket is not active" }
        val packet = Packet(
            type = Packet.Type.EVENT,
            namespace = namespace,
            data = listOf(event.asPacketData()) + args.toList(),
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
