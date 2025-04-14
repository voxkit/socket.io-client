package io.voxkit.engineio.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.http.*
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.client.transports.pollingTransport
import io.voxkit.engineio.client.transports.webSocketTransport
import io.voxkit.engineio.parser.Packet
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a session for Engine.IO.
 */
public interface Engine {
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
     * A state of [Engine] session.
     */
    public val state: StateFlow<State>

    /**
     * Sends a text packet to the Engine.IO server.
     *
     * @throws [ClosedEngineException] if the session is closed.
     */
    public suspend fun send(message: String)

    /**
     * Sends a binary packet to the Engine.IO server.
     *
     * @throws [ClosedEngineException] if the session is closed.
     */
    public suspend fun send(data: ByteArray)

    /**
     * Closes the session.
     */
    public fun close()

    public sealed interface State {
        public data object Opening : State
        public data object Open : State
        public data class Closed(val reason: CloseReason, val cause: Throwable?) : State
    }
}

/**
 * Creates a new [Engine] using the provided `urlString` and `block` to configure the [EngineIOOptions].
 *
 * @param urlString The URL string to connect to. If the URL starts with "ws://" or "wss://", it will be the session
 * will be use websocket transport only.
 * @param httpClient A [HttpClient] to use for the connection.
 * @param block A lambda function to configure the [EngineIOOptions].
 */
public fun engineIO(urlString: String, httpClient: HttpClient, block: EngineOptionsBuilder.() -> Unit = {}): Engine =
    engineIO(Url(urlString), httpClient, block)

/**
 * Creates a new [Engine] using the provided `url` and `block` to configure the [EngineIOOptions].
 *
 * @param url The URL string to connect to. If the URL starts with "ws://" or "wss://", it will be the session
 * will be use websocket transport only.
 * @param httpClient A [HttpClient] to use for the connection.
 * @param block A lambda function to configure the [EngineIOOptions].
 */
public fun engineIO(url: Url, httpClient: HttpClient, block: EngineOptionsBuilder.() -> Unit = {}): Engine {
    val options = EngineOptionsBuilder(url).apply(block).build()
    return engineIO(httpClient, options)
}

/**
 * Creates a new [Engine] using the provided [block] to configure the [EngineIOOptions].
 *
 * @param httpClient A [HttpClient] to use for the connection.
 * @param block A lambda function to configure the [EngineIOOptions].
 */
public fun engineIO(httpClient: HttpClient, block: EngineOptionsBuilder.() -> Unit = {}): Engine {
    val options = EngineOptionsBuilder().apply(block).build()
    return engineIO(httpClient, options)
}

private fun engineIO(httpClient: HttpClient, options: EngineIOOptions): Engine {
    val transportType = if (options.transports.contains(TransportType.POLLING)) {
        TransportType.POLLING
    } else {
        options.transports.first()
    }
    val scope = CoroutineScope(SupervisorJob() + options.dispatcher + CoroutineName("engine.io"))
    val transport = when (transportType) {
        TransportType.POLLING -> scope.pollingTransport(httpClient, options)
        TransportType.WEBSOCKET -> scope.webSocketTransport(httpClient, options)
    }
    return VoxKitEngine(
        initialTransport = transport,
        options = options,
        httpClient = httpClient,
        scope = scope,
    )
}
