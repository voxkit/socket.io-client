package io.voxkit.socketio.client

import io.voxkit.socketio.logging.Logger
import io.voxkit.socketio.logging.LoggingLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

public class IOOptionsBuilder {
    /**
     * Whether to create a new [Manager] instance.
     *
     * Default: `false`
     *
     * A [Manager] instance is in charge of the low-level connection to the server (established with HTTP long-polling or
     *  WebSocket). It handles the reconnection logic.
     *
     * A [Socket] instance is the interface which is used to sends events to — and receive events from — the server.
     * It belongs to a given namespace.
     *
     * A single [Manager] can be attached to several [Socket] instances.
     *
     * The following example will reuse the same Manager instance for the 3 Socket instances (one single WebSocket
     * connection):
     *
     * ```kotlin
     * val socket = IO("https://example.com") // the main namespace
     * val productSocket = IO("https://example.com/product") // the "product" namespace
     * val orderSocket = IO("https://example.com/order") // the "order" namespace
     * ```
     *
     * The following example will create 3 different Manager instances (and thus 3 distinct WebSocket connections):
     *
     * ```kotlin
     * val socket = IO("https://example.com") // the main namespace
     *
     * // the "product" namespace
     * val productSocket = IO("https://example.com/product") {
     *     forceNew = true
     * }
     *
     * // the "order" namespace
     * val orderSocket = IO("https://example.com/order") {
     *    forceNew = true
     * }
     *
     * Reusing an existing namespace will also create a new Manager each time:
     *
     * ```kotlin
     * val socket1 = io() // 1st manager
     * val socket2 = io() // 2nd manager
     * val socket3 = io("/admin") // reusing the 1st manager
     * val socket4 = io("/admin") // 3rd manager
     */
    public var forceNew: Boolean = false

    /**
     * The logging level for the socket.io client.
     */
    public var loggingLevel: LoggingLevel = LoggingLevel.NONE

    /**
     * The logging level for the underlying engine.io client.
     *
     * This is useful for debugging purposes.
     */
    public var engineLoggingLevel: LoggingLevel = LoggingLevel.NONE

    /**
     * The custom logger to use for logging.
     */
    public var logger: Logger? = null

    /**
     * The dispatcher to use for coroutines.
     *
     * Default: [Dispatchers.Default]
     */
    public var dispatcher: CoroutineDispatcher = Dispatchers.Default

    internal fun build(): IOOptions = IOOptions(
        forceNew = forceNew,
        logger = logger,
        loggingLevel = loggingLevel,
        engineLoggingLevel = engineLoggingLevel,
        dispatcher = dispatcher,
    )
}

internal data class IOOptions(
    val forceNew: Boolean,
    val logger: Logger? = null,
    val loggingLevel: LoggingLevel,
    val engineLoggingLevel: LoggingLevel,
    val dispatcher: CoroutineDispatcher,
)
