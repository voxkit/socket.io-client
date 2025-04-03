package io.voxkit.socketio.client.parser

/**
 * Parser interface for encoding and decoding Socket.IO packets.
 */
internal interface Parser {
    /**
     * Encodes a packet into a Socket.IO format.
     *
     * @param packet The packet to encode.
     */
    fun encode(packet: Packet): Encoded

    /**
     * Decodes a Socket.IO packet from a string.
     */
    fun decode(text: String): Packet

    /**
     * Decodes a Socket.IO packet from a byte array.
     *
     * @param bytes The byte array to decode.
     * @param partial Partial decoded packet.`.
     */
    fun decodeBinary(bytes: ByteArray, partial: Decoded): Decoded

    /**
     * Encoded representation of a Socket.IO packet.
     */
    sealed interface Encoded {
        /**
         * Encoded packet as a string.
         */
        data class Text(val data: String) : Encoded

        /**
         * Encoded packet as a binary event or acknowledgment.
         */
        data class Binary(val data: String, val buffers: List<ByteArray>) : Encoded
    }

    /**
     * Decoded representation of a Socket.IO packet.
     */
    sealed interface Decoded {
        /**
         * Decoded packet.
         */
        data class Completed(val packet: Packet) : Decoded

        /**
         * Partial decoded packet, which may contain binary data.
         */
        data class Partial(val packet: Packet) : Decoded
    }
}
