package io.voxkit.socketio.client

import io.voxkit.socketio.client.parser.Packet
import kotlinx.coroutines.flow.Flow

/**
 * The Manager manages the Engine.IO client instance, which is the low-level engine that establishes the connection
 * to the server (by using transports like WebSocket or HTTP long-polling).
 * The Manager handles the reconnection logic.
 * A single Manager can be used by several Sockets.
 * In most cases, you won't use the Manager directly but use the Socket instance instead.
 */
public interface Manager {
    public val events: Flow<Event>

    /**
     * If the manager was initiated with `autoConnect` to `false`, launch a new connection attempt.
     */
    public suspend fun connect()

    /**
     * Creates a new [Socket]] for the given namespace.
     */
    public fun socket(namespace: String, auth: AuthSocketOption? = null): Socket

    /**
     * Sends Socket.IO [Packet] to server by underlying Engine.IO client.
     */
    public suspend fun send(packet: Packet)

    public sealed interface Event {
        /**
         * Fired upon a connection error.
         */
        public data class Error(val error: Throwable) : Event

        /**
         * Fired when a ping packet is received from the server.
         */
        public data object Ping : Event

        /**
         * Fired upon a successful reconnection.
         *
         * @param attempt The number of reconnection attempts made before a successful connection.
         */
        public data class Reconnect(val attempt: Int) : Event

        /**
         * Fired upon an attempt to reconnect.
         *
         * @param attempt Reconnection attempt number.
         */
        public data class ReconnectAttempt(val attempt: Int) : Event

        /**
         * Fired upon a reconnection attempt error.
         */
        public data class ReconnectError(val error: Throwable) : Event

        /**
         * Fired when couldn't reconnect within `reconnectionAttempts`.
         */
        public data object ReconnectionFailed : Event
    }

    public sealed interface State {
        public data object Connecting : State
        public data object Connected : State
        public data class Disconnected(val reason: String, val cause: Throwable?) : State
    }
}
