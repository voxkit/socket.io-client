package io.voxkit.socketio.client

import co.touchlab.kermit.LoggerConfig
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter
import io.ktor.client.*
import io.ktor.http.*
import io.voxkit.engineio.client.engineIOHttpClient
import io.voxkit.engineio.client.transports.TransportType
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public class ManagerOptionsBuilder {
    /**
     * Whether to automatically connect upon creation. If set to `false, you need to manually connect.
     *
     * Default: `true`
     */
    public var autoConnect: Boolean = true

    /**
     * The headers to append to the request.
     */
    public val headers: HeadersBuilder = HeadersBuilder()

    /**
     * The query parameters to append to the URL.
     */
    public val parameters: ParametersBuilder = ParametersBuilder()

    /**
     * It is the name of the path that is captured on the server side.
     *
     * Default: `/socket.io/`
     *
     * Caution: the server and the client values must match (unless you are using a path-rewriting proxy in between).
     */
    public var path: String = "/socket.io/"

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
    public var randomizationFactor: Double = 0.5

    /**
     * Whether reconnection is enabled or not. If set to false, you need to manually reconnect.
     *
     * Default: `true`
     */
    public var reconnection: Boolean = true

    /**
     * The maximum number of reconnection attempts before giving up.
     *
     * Default: [Int.MAX_VALUE]
     */
    public var reconnectionAttempts: Int = Int.MAX_VALUE

    /**
     * The delay between reconnection attempts.
     *
     * Default: `1`
     */
    public var reconnectionDelay: Duration = 1.seconds

    /**
     * The maximum delay between reconnection attempts.
     *
     * Default: 5 seconds
     */
    public var reconnectionDelayMax: Duration = 5.seconds

    /**
     * The timeout for each connection attempt.
     *
     * Default: 20 seconds
     */
    public var timeout: Duration = 20.seconds

    /**
     * The timestamp parameter name.
     *
     * Default: `t`
     */
    public var timestampParam: String = "t"

    /**
     * Whether to append a timestamp to the URL.
     *
     * Default: `true`
     */
    public var timestampRequests: Boolean = true

    /**
     * The transports to use.
     *
     * Default is [TransportType.POLLING] and [TransportType.WEBSOCKET].
     */
    public var transports: Set<TransportType> = setOf(
        TransportType.POLLING,
        TransportType.WEBSOCKET
    )

    public val socketBuilder: SocketOptionsBuilder = SocketOptionsBuilder()

    internal fun build(): ManagerOptions {
        check(reconnectionAttempts > 0) { "Reconnection attempts must be greater than 0" }

        return ManagerOptions(
            autoConnect = autoConnect,
            headers = headers.build(),
            parameters = parameters.build(),
            path = path,
            randomizationFactor = randomizationFactor,
            reconnection = reconnection,
            reconnectionAttempts = reconnectionAttempts,
            reconnectionDelay = reconnectionDelay,
            reconnectionDelayMax = reconnectionDelayMax,
            timeout = timeout,
            timestampParam = timestampParam,
            timestampRequests = timestampRequests,
            transports = transports,
            socketOption = socketBuilder.build(),
        )
    }
}

internal data class ManagerOptions(
    val autoConnect: Boolean,
    val headers: Headers,
    val parameters: Parameters,
    val path: String,
    val randomizationFactor: Double,
    val reconnection: Boolean,
    val reconnectionAttempts: Int,
    val reconnectionDelay: Duration,
    val reconnectionDelayMax: Duration,
    val timeout: Duration,
    val timestampParam: String,
    val timestampRequests: Boolean,
    val transports: Set<TransportType>,
    val socketOption: SocketOptions,
)

internal fun ManagerOptions.calculateReconnectionDelay(attempt: Int): Duration {
    val duration = if (attempt < 3) {
        val from = (1 - randomizationFactor).coerceIn(0.0, 1.0)
        val to = (1 + randomizationFactor).coerceIn(1.0, 2.0)
        val randomFactor = Random.nextDouble(from, to)
        reconnectionDelay * 2.0.pow(attempt) * randomFactor
    } else {
        reconnectionDelayMax
    }

    return duration.coerceAtMost(reconnectionDelayMax)
}
