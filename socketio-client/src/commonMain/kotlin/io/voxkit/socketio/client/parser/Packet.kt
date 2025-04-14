package io.voxkit.socketio.client.parser

import kotlinx.serialization.json.JsonElement

/**
 * Represents a packet in the Socket.IO protocol.
 */
public data class Packet(
    /**
     * The packet type.
     */
    val type: Type,

    /**
     * Packet's namespace.
     */
    val namespace: String = "/",

    /**
     * Packet's payload
     */
    val payload: Payload? = null,

    /**
     * Packet's acknowledgment ID.
     */
    val ackId: Long? = null,
) {
    public enum class Type {
        CONNECT,
        DISCONNECT,
        EVENT,
        ACK,
        CONNECT_ERROR,
    }

    /**
     * Represents the payload of a packet.
     */
    public data class Payload(
        /**
         * The packet's data encoded as a list of JSON elements.
         */
        val data: List<JsonElement>,

        /**
         * The packet's binary data attachments.
         */
        val buffers: MutableList<ByteArray> = mutableListOf(),
    )
}

internal val Packet.isBinary: Boolean
    get() = payload?.buffers?.isNotEmpty() == true
