package io.voxkit.engineio.client

public enum class CloseReason {
    CLIENT_DISCONNECT,
    SERVER_DISCONNECT,
    TRANSPORT_ERROR,
    TRANSPORT_CLOSE,
    PING_TIMEOUT,
}
