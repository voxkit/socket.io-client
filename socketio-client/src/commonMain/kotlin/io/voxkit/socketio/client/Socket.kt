package io.voxkit.socketio.client

import io.voxkit.socketio.client.parser.Packet
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A Socket is the fundamental class for interacting with the server.
 * A Socket belongs to a certain Namespace (by default /) and uses an underlying [Manager] to communicate.
 */
public interface Socket : AutoCloseable {
    /**
     * Whether the socket will automatically try to reconnect.
     */
    public val active: Boolean

    /**
     * Whether the socket is currently connected to the server.
     */
    public val connected: Boolean

    /**
     * Whether the socket is currently disconnected from the server.
     */
    public val disconnected: Boolean

    /**
     * A unique identifier for the socket session. Set after the connect event is triggered,
     * and updated after the reconnect event.
     *
     * **Caution:**
     *
     * The id attribute is an ephemeral ID that is not meant to be used in your application
     * (or only for debugging purposes) because:
     *
     * - this ID is regenerated after each reconnection (for example when the WebSocket connection is severed,
     *   or when the user refreshes the page)
     * - two different browser tabs will have two different IDs
     * - there is no message queue stored for a given ID on the server (i.e. if the client is disconnected,
     *   the messages sent from the server to this ID are lost)
     */
    public val id: String?

    /**
     * A reference to the underlying [Manager] instance.
     */
    public val io: Manager

    /**
     * Whether the connection state was successfully recovered during the last reconnection.
     */
    public val recovered: Boolean

    /**
     * Socket events are emitted when the socket is connected, disconnected,
     * or when an event is received from the server.
     */
    public val events: Flow<Event>

    /**
     * Manually connects the socket.
     */
    public suspend fun connect()

    /**
     * Manually disconnects the socket.
     */
    public suspend fun disconnect()

    /**
     * Sends an event to the socket.
     *
     * @param event The event name to send.
     * @param args The arguments to send with the event.
     */
    public suspend fun send(event: String, vararg args: Packet.Data)

    /**
     * Sends an event to the socket and waits for an acknowledgment from the server.
     *
     * @param event The event name to send.
     * @param args The arguments to send with the event.
     * @return A list of [JsonObject] received as acknowledgment from the server.
     */
    public suspend fun sendWithAck(event: String, vararg args: Packet.Data): List<Packet.Data>

    /**
     * The [Socket] event
     */
    public sealed interface Event {
        /**
         * This event is fired by the [Socket] instance upon connection and reconnection.
         */
        public data object Connect : Event

        /**
         * This event is fired by the [Socket] instance upon connection failure.
         */
        public data class ConnectError(val error: Throwable) : Event

        /**
         * This event is fired by the [Socket] instance upon disconnection.
         *
         * Here is the list of possible reasons:
         *```
         *   | Reason                | Description                                               | Automatic     |
         *   |                       |                                                           | reconnection? |
         *   |-----------------------|-----------------------------------------------------------|---------------|
         *   | io server disconnect  | The server has forcefully disconnected the socket with    | ❌ NO         |
         *   |                       | socket.disconnect()                                       |               |
         *   |-----------------------|-----------------------------------------------------------|---------------|
         *   | io client disconnect  | The socket was manually disconnected using                | ❌ NO         |
         *   |                       | socket.disconnect()                                       |               |
         *   |-----------------------|-----------------------------------------------------------|---------------|
         *   | ping timeout          | The server did not send a PING within the pingInterval +  | ✅ YES        |
         *   |                       | pingTimeout range                                         |               |
         *   |-----------------------|-----------------------------------------------------------|---------------|
         *   | transport close       | The connection was closed (example: the user has lost     | ✅ YES        |
         *   |                       | connection, or the network was changed from WiFi to 4G)   |               |
         *   |-----------------------|-----------------------------------------------------------|---------------|
         *   | transport error       | The connection has encountered an error (example: the     | ✅ YES        |
         *   |                       | server was killed during a HTTP long-polling cycle)       |               |
         *```
         */
        public data class Disconnect(val reason: String, val cause: Throwable?) : Event

        public data class CustomEvent(val event: String, val args: List<JsonElement>) : Event
    }
}
