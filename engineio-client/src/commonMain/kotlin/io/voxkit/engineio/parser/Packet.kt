package io.voxkit.engineio.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Represents a packet in the Engine.IO protocol.
 **/
public sealed interface Packet {

    /**
     * Open packet with handshake information.
     */
    @Serializable
    public data class Open(
        val sid: String,
        val upgrades: List<String>,
        val pingInterval: Long,
        val pingTimeout: Long,
        val maxPayload: Long? = null,
    ) : Packet

    /**
     * Close packet.
     */
    public data object Close : Packet

    /**
     * Ping packet.
     */
    public data class Ping(val data: String = "") : Packet

    /**
     * Pong packet.
     */
    public data class Pong(val data: String = "") : Packet

    /**
     * Upgrade packet.
     */
    public data object Upgrade : Packet

    /**
     * Noop packet.
     */
    public data object Noop : Packet

    /**
     * Error packet.
     */
    public data class Error(val data: String) : Packet

    /**
     * Message packet with text data.
     */
    public data class Message(val data: String = "") : Packet

    /**
     * Binary packet with binary data.
     */
    public data class Binary(val data: ByteArray) : Packet {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false

            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }
}

internal fun Packet.data(): String = when (this) {
    is Packet.Open -> Json.encodeToString(this)
    is Packet.Ping -> data
    is Packet.Pong -> data
    is Packet.Message -> data
    is Packet.Binary -> data.decodeToString()
    is Packet.Error -> data
    else -> ""
}
