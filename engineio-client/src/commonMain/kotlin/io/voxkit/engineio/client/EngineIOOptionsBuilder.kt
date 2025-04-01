package io.voxkit.engineio.client

import co.touchlab.kermit.LoggerConfig
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter
import io.ktor.http.*
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.yeast.Yeast

public class EngineIOOptionsBuilder {
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

    /**
     * The logger configuration.
     * Touchlab Kermit logger is used.
     *
     * @see https://kermit.touchlab.co/docs/
     */
    public var loggerConfig: LoggerConfig = loggerConfigInit(platformLogWriter(), minSeverity = Severity.Error)

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
            loggerConfig = loggerConfig,
        )
    }
}

internal fun EngineIOOptionsBuilder(url: Url): EngineIOOptionsBuilder {
    val transportSet = when {
        url.protocol.name.startsWith("http") -> setOf(TransportType.POLLING, TransportType.WEBSOCKET)
        url.protocol.name.startsWith("ws") -> setOf(TransportType.WEBSOCKET)
        else -> setOf(TransportType.POLLING, TransportType.WEBSOCKET)
    }

    return EngineIOOptionsBuilder().apply {
        host = url.host
        port = url.port
        path = url.encodedPath
        secure = url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.WSS
        parameters.appendAll(url.parameters)
        transports = transportSet
    }
}
