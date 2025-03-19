package io.voxkit.engineio.client

import co.touchlab.kermit.Logger
import io.ktor.client.*
import io.ktor.client.call.*
import io.voxkit.engineio.client.transports.Transport
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.client.transports.pollingTransport
import io.voxkit.engineio.client.transports.webSocketTransport
import io.voxkit.engineio.parser.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a session for Engine.IO.
 */
public interface EngineIOSession : CoroutineScope {
    /**
     * A session ID.
     */
    public val id: String?

    /**
     * An incoming packets channel.
     */
    public val incoming: ReceiveChannel<Packet>

    /**
     * A transport type.
     */
    public val transportType: StateFlow<TransportType>

    /**
     * A state flow of [HttpClientCall]s.
     * It may be emitted multiple times for polling transport. For websocket transport it will be emitted only once.
     */
    public val call: StateFlow<HttpClientCall?>

    /**
     * Sends a text packet to the Engine.IO server.
     *
     * @throws [EngineIOSocketClosedException] if the session is closed.
     */
    public suspend fun send(message: String)

    /**
     * Sends a binary packet to the Engine.IO server.
     *
     * @throws [EngineIOSocketClosedException] if the session is closed.
     */
    public suspend fun send(data: ByteArray)

    /**
     * Closes the session.
     */
    public suspend fun close()
}

/**
 * Creates a new [EngineIOSession] using the provided [urlString] and [block] to configure the [EngineIOOptions].
 *
 * @param urlString The URL string to connect to. If the URL starts with "ws://" or "wss://", it will be the session
 * will be use websocket transport only.
 * @param block A lambda function to configure the [EngineIOOptions].
 */
public suspend fun HttpClient.engineIOSession(
    urlString: String,
    block: EngineIOOptionsBuilder.() -> Unit = {}
): EngineIOSession {
    val options = EngineIOOptionsBuilder(urlString).apply(block).build()
    return engineIOSession(options)
}

/**
 * Creates a new [EngineIOSession] using the provided [block] to configure the [EngineIOOptions].
 *
 * @param block A lambda function to configure the [EngineIOOptions].
 */
public suspend fun HttpClient.engineIOSession(block: EngineIOOptionsBuilder.() -> Unit = {}): EngineIOSession {
    val options = EngineIOOptionsBuilder().apply(block).build()
    return engineIOSession(options)
}

private suspend fun HttpClient.engineIOSession(options: EngineIOOptions): EngineIOSession {
    val logger = Logger(options.loggerConfig, "EngineIO")
    val transportType = selectTransportType(options)
    val transport = createTransport(logger, transportType, options)
    val handshakePacket = transport.incoming.receive()
    return EngineIOSessionImpl(logger, transport, options, handshakePacket, httpClient = this)
}

private suspend fun HttpClient.createTransport(
    logger: Logger,
    transportType: TransportType,
    options: EngineIOOptions
): Transport {
    logger.v { "create transport '${transportType}'" }
    return when (transportType) {
        TransportType.POLLING -> pollingTransport(options)
        TransportType.WEBSOCKET -> webSocketTransport(options)
    }
}

private fun selectTransportType(options: EngineIOOptions): TransportType {
    return if (options.transports.contains(TransportType.POLLING)) {
        TransportType.POLLING
    } else {
        options.transports.first()
    }
}
