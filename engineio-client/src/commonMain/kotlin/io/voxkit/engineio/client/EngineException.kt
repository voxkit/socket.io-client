package io.voxkit.engineio.client

import io.voxkit.engineio.parser.Packet

public open class EngineException(
    message: String? = null,
    public val transport: String? = null,
    public val code: Any? = null,
    cause: Throwable? = null
) : Exception(message, cause)

public class InvalidHandshakeEngineException(public val packet: Packet) : EngineException("Invalid handshake: $packet")
public class ClosedEngineException : EngineException("Socket closed")
