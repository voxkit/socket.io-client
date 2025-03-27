package io.voxkit.socketio.client

import kotlin.time.Duration

public data class SocketOptions(
    /**
     * The default timeout used when waiting for an acknowledgement (not to be mixed up with the already existing
     * timeout option, which is used by the [Manager] during the connection).
     *
     * Default: [Duration.INFINITE]
     */
    val ackTimeout: Duration = Duration.INFINITE,

    /**
     * Credentials that are sent when accessing a namespace.
     *
     * Default: null
     */
    val auth: AuthSocketOption? = null,

    /**
     * The maximum number of retries. Above the limit, the packet will be discarded.
     *
     * Default: [Int.MAX_VALUE]
     */
    val retries: Int = Int.MAX_VALUE,
)

public data class AuthSocketOption(
    /**
     * The name of the parameter used to send the token.
     */
    val paramName: String,

    /**
     * The token used to access the namespace.
     */
    val token: String,
)
