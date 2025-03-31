package io.voxkit.socketio.client.parser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal data class Packet(
    val type: Type,
    val namespace: String = "/",
    val data: List<Data>? = null,
    val ackId: Int? = null,
) {
    enum class Type {
        CONNECT,
        DISCONNECT,
        EVENT,
        ACK,
        CONNECT_ERROR,
        BINARY_EVENT,
        BINARY_ACK,
    }

    sealed interface Data {
        data class Json(val element: JsonElement) : Data

        data class Binary(val buffer: ByteArray) : Data {
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

internal inline fun <reified T> T.asPacketData(): Packet.Data {
    return when (this) {
        is ByteArray -> Packet.Data.Binary(this)
        else -> Packet.Data.Json(ParserImpl.JSON.encodeToJsonElement(this))
    }
}

internal val Packet.placeholdersCount: Int
    get() = data?.count { (it as? Packet.Data.Json)?.element?.isAttachmentPlaceholder == true } ?: 0
