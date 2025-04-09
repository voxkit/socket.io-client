package io.voxkit.socketio.client

public open class SocketException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

public class SocketConnectException(message: String): SocketException(message)
