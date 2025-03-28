package io.voxkit.socketio.client.parser

import kotlinx.coroutines.channels.Channel

internal interface Parser {
    val decodedPackets: Channel<Packet>

    fun encode(packet: Packet): Encoded
    suspend fun decode(data: Encoded): Decoded

    sealed interface Encoded {
        data class Text(val data: String) : Encoded
        data class Binary(val data: List<ByteArray>) : Encoded
    }

    sealed interface Decoded {
        data class Completed(val packet: Packet) : Decoded
        data class Partial(val packet: Packet, val buffers: List<ByteArray>) : Decoded
    }
}
