package io.voxkit.socketio.client

public open class SocketIOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

public class SocketIOConnectException(message: String): SocketIOException(message)
