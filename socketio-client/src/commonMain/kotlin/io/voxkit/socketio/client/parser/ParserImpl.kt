package io.voxkit.socketio.client.parser

import io.ktor.utils.io.core.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ParserImpl : Parser, AutoCloseable {
    override val decodedPackets: Channel<Packet>
        get() = TODO("Not yet implemented")

    override fun encode(packet: Packet): Parser.Encoded {
        return when (packet.type) {
            Packet.Type.BINARY_EVENT, Packet.Type.BINARY_ACK -> Parser.Encoded.Binary(encodeAsBinary(packet))
            else -> Parser.Encoded.Text(encodeAsText(packet))
        }
    }

    private fun encodeAsBinary(packet: Packet): List<ByteArray> {
        checkNotNull(packet.data) { "Packet data cannot be null for binary encoding" }
        val text = encodeAsText(packet)
        val buffers = packet.data.mapNotNull { (it as? Packet.Data.Binary)?.buffer }
        check(buffers.isNotEmpty()) { "No binary data found in packet" }
        return listOf(text.toByteArray()) + buffers
    }

    /**
     * <packet type>[<# of binary attachments>-][<namespace>,][<acknowledgment id>][JSON-stringified payload without binary]
     */
    private fun encodeAsText(packet: Packet): String {
        var placeholderIndex = 0
        val dataWithPlaceholders = packet.data?.map { el -> toJsonElement({ placeholderIndex++ }, el) }

        return buildString {
            // packet type
            append(packet.type.ordinal)

            // number of binary attachments
            placeholderIndex.takeIf { it > 0 }?.let { append("$it-") }

            // namespace
            if (packet.namespace != "/") append("${packet.namespace},")

            // acknowledgment id
            packet.ackId?.let { append(it) }

            // JSON-stringified payload without binary
            dataWithPlaceholders?.let { elements ->
                if (elements.size == 1) {
                    append(Json.encodeToString(elements[0]))
                } else {
                    append(Json.encodeToString(elements))
                }
            }
        }
    }

    private fun toJsonElement(placeholderIndex: () -> Int, data: Packet.Data): JsonElement {
        return when (data) {
            is Packet.Data.Json -> data.element

            is Packet.Data.Binary -> {
                JsonObject(
                    mapOf(
                        "_placeholder" to JsonPrimitive(true),
                        "num" to JsonPrimitive(placeholderIndex())
                    )
                )
            }
        }
    }

    override suspend fun decode(data: Parser.Encoded): Parser.Decoded {
        TODO("Not yet implemented")
    }

    override fun close() {
        decodedPackets.close()
    }
}
