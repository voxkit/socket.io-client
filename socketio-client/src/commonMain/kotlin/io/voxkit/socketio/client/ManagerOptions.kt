package io.voxkit.socketio.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public data class ManagerOptions(
    /**
     * Whether to automatically connect upon creation. If set to `false, you need to manually connect.
     *
     * Default: `true`
     */
    val autoConnect: Boolean = true,

    /**
     * The randomization factor used when reconnecting (so that the clients do not reconnect at the exact same time
     * after a server crash, for example).
     *
     * Default: `0.5`
     *
     * Example with the default values:
     *
     * - 1st reconnection attempt happens between 500 and 1500 ms (1000 * 2^0 * (<something between -0.5 and 1.5>))
     * - 2nd reconnection attempt happens between 1000 and 3000 ms (1000 * 2^1 * (<something between -0.5 and 1.5>))
     * - 3rd reconnection attempt happens between 2000 and 5000 ms (1000 * 2^2 * (<something between -0.5 and 1.5>))
     * - next reconnection attempts to happen after 5000 ms
     */
    val randomizationFactor: Double = 0.5,

    /**
     * Whether reconnection is enabled or not. If set to false, you need to manually reconnect.
     *
     * Default: `true`
     */
    val reconnection: Boolean = true,

    /**
     * The maximum number of reconnection attempts before giving up.
     *
     * Default: [Int.MAX_VALUE]
     */
    val reconnectionAttempts: Int = Int.MAX_VALUE,

    /**
     * The delay between reconnection attempts.
     *
     * Default: `1`
     */
    val reconnectionDelay: Duration = 1.seconds,

    /**
     * The maximum delay between reconnection attempts.
     *
     * Default: 5 seconds
     */
    val reconnectionDelayMax: Duration = 5.seconds,

    /**
     * The timeout for each connection attempt.
     *
     * Default: 20 seconds
     */
    val timeout: Duration = 20.seconds,

    val socketOptions: SocketOptions = SocketOptions(),
)
