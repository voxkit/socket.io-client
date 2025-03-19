package io.voxkit.engineio.client

import io.voxkit.engineio.parser.Packet

public open class EngineIoException(
    message: String? = null,
    public val transport: String? = null,
    public val code: Any? = null,
    cause: Throwable? = null
) : Exception(message, cause)


public class EngineIOInvalidHandshakeException(
    public val packet: Packet
) : EngineIoException("Invalid handshake: $packet")

public class EngineIOSocketClosedException : EngineIoException("Socket closed")
