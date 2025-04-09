package io.voxkit.engineio.client

import io.ktor.client.request.*
import io.ktor.http.*
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.parser.Parser
import io.voxkit.socketio.logging.LoggingLevel
import io.voxkit.socketio.logging.Logger
import io.voxkit.socketio.logging.VoxKitLoggerFactory
import io.voxkit.socketio.logging.defaultLogger
import io.voxkit.yeast.Yeast
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

public class EngineOptionsBuilder {
    /**
     * The host name of the server.
     * Default is "localhost".
     */
    public var host: String = "localhost"

    /**
     * The port number of the server.
     * Default is 80 for unsecure and 443 for secure.
     */
    public var port: Int = DEFAULT_PORT

    /**
     * The path.
     * Default is "engine.io/".
     */
    public var path: String = "/engine.io/"

    /**
     * The secure flag.
     */
    public var secure: Boolean = false

    /**
     * The timestamp parameter name. If set it will be used to append a timestamp to the URL.
     */
    public var timestampParam: String? = null

    /**
     * The transports to use.
     * Default is [TransportType.POLLING] and [TransportType.WEBSOCKET].
     */
    public var transports: Set<TransportType> = setOf(
        TransportType.POLLING,
        TransportType.WEBSOCKET
    )

    /**
     * The query parameters to append to the URL.
     */
    public val parameters: ParametersBuilder = ParametersBuilder()

    /**
     * The headers to append to the request.
     */
    public val headers: HeadersBuilder = HeadersBuilder()

    public var loggingLevel: LoggingLevel = LoggingLevel.NONE

    public var logger: Logger? = null

    public var dispatcher: CoroutineDispatcher = Dispatchers.Default

    internal fun build(): EngineIOOptions {
        require(transports.isNotEmpty()) { "At least one transport must be specified." }

        return EngineIOOptions(
            host = host,
            port = port,
            path = path,
            secure = secure,
            parameters = parameters.apply { timestampParam?.let { append(it, Yeast.yeast()) } }.build(),
            headers = headers.build(),
            transports = transports,
            loggerFactory = VoxKitLoggerFactory(logger ?: defaultLogger(), loggingLevel),
            dispatcher = dispatcher,
        )
    }
}

internal fun EngineOptionsBuilder(url: String): EngineOptionsBuilder {
    return EngineOptionsBuilder(Url(url))
}

internal fun EngineOptionsBuilder(url: Url): EngineOptionsBuilder {
    val transportSet = when {
        url.protocol.name.startsWith("http") -> setOf(TransportType.POLLING, TransportType.WEBSOCKET)
        url.protocol.name.startsWith("ws") -> setOf(TransportType.WEBSOCKET)
        else -> setOf(TransportType.POLLING, TransportType.WEBSOCKET)
    }

    return EngineOptionsBuilder().apply {
        host = url.host
        port = url.port
        path = url.encodedPath
        secure = url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.WSS
        parameters.appendAll(url.parameters)
        transports = transportSet
    }
}

internal class EngineIOOptions(
    val host: String,
    val port: Int,
    val path: String,
    val secure: Boolean,
    val parameters: Parameters,
    val headers: Headers,
    val transports: Set<TransportType>,
    val loggerFactory: VoxKitLoggerFactory,
    val dispatcher: CoroutineDispatcher,
) {
    internal fun buildRequest(transport: TransportType, sid: String?, builder: HttpRequestBuilder) {
        builder.url.protocol = when (transport) {
            TransportType.POLLING -> if (secure) URLProtocol.HTTPS else URLProtocol.HTTP
            TransportType.WEBSOCKET -> if (secure) URLProtocol.WSS else URLProtocol.WS
        }

        val transportName = when (transport) {
            TransportType.POLLING -> "polling"
            TransportType.WEBSOCKET -> "websocket"
        }

        builder.url.host = host
        builder.url.port = port
        builder.url.path(path)
        builder.url.parameters.appendAll(parameters)
        builder.url.parameters.append("EIO", "${Parser.PROTOCOL}")
        builder.url.parameters.append("transport", transportName)
        sid?.let { builder.url.parameters.append("sid", it) }
        builder.headers.appendAll(headers)
    }
}
