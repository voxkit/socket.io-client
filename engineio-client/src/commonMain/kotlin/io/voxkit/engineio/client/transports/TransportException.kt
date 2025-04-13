package io.voxkit.engineio.client.transports

import io.ktor.client.statement.*

public class TransportException(
    public val response: HttpResponse? = null,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)
