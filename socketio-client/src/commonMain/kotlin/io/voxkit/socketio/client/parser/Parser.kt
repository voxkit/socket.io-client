package io.voxkit.socketio.client.parser

internal interface Parser {
    fun encode(packet: Packet): Encoded
    fun decode(text: String): Packet
    fun decode(bytes: ByteArray, partial: Decoded.Partial? = null): Decoded

    sealed interface Encoded {
        data class Text(val data: String) : Encoded
        data class Binary(val data: List<ByteArray>) : Encoded
    }

    sealed interface Decoded {
        data class Completed(val packet: Packet) : Decoded
        data class Partial(val packet: Packet) : Decoded
    }
}
