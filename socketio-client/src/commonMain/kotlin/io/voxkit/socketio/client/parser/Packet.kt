package io.voxkit.socketio.client.parser

import io.voxkit.socketio.client.util.isAttachmentPlaceholder
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
     * Packet's data
     */
    val data: List<Data>? = null,

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
        BINARY_EVENT,
        BINARY_ACK,
    }

    public sealed interface Data {
        public data class Json(val element: JsonElement) : Data

        public data class Binary(val buffer: ByteArray) : Data {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Binary) return false

                if (!buffer.contentEquals(other.buffer)) return false

                return true
            }

            override fun hashCode(): Int {
                return buffer.contentHashCode()
            }
        }
    }
}

internal val Packet.placeholdersCount: Int
    get() = data?.count { (it as? Packet.Data.Json)?.element?.isAttachmentPlaceholder == true } ?: 0
