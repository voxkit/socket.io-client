package io.voxkit.engineio.util

import io.ktor.http.*
import io.voxkit.engineio.client.transports.TransportType

internal fun Url.calculateTransports(): Set<TransportType> = when {
    protocol.name.startsWith("http") -> setOf(TransportType.POLLING, TransportType.WEBSOCKET)
    protocol.name.startsWith("ws") -> setOf(TransportType.WEBSOCKET)
    else -> setOf(TransportType.POLLING, TransportType.WEBSOCKET)
}
