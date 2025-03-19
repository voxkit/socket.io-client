package io.voxkit.engineio.client

import co.touchlab.kermit.LoggerConfig
import io.ktor.client.request.*
import io.ktor.http.*
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.parser.Parser

internal class EngineIOOptions(
    val host: String,
    val port: Int,
    val path: String,
    val secure: Boolean,
    val parameters: Parameters,
    val headers: Headers,
    val transports: Set<TransportType>,
    val loggerConfig: LoggerConfig
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
