package io.voxkit.socketio.client.parser

import io.ktor.utils.io.core.*
import io.voxkit.socketio.client.util.isAttachmentPlaceholder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parser implementation for encoding and decoding Socket.IO packets.
 * This implementation handles both text and binary packets.
 * @see https://socket.io/docs/v4/socket-io-protocol/#format
 */
@PublishedApi
internal class DefaultParser : Parser {
    override fun encode(packet: Packet): Parser.Encoded {
        return if (packet.isBinary) encodeAsBinary(packet) else Parser.Encoded.Text(encodeAsText(packet))
    }

    private fun encodeAsBinary(packet: Packet): Parser.Encoded.Binary {
        checkNotNull(packet.payload) { "Packet data cannot be null for binary encoding" }
        val text = encodeAsText(packet)
        val buffers = packet.payload.buffers
        check(buffers.isNotEmpty()) { "No binary data found in packet" }
        return Parser.Encoded.Binary(text, listOf(text.toByteArray()) + buffers)
    }

    /**
     * <packet type>[<# of binary attachments>-][<namespace>,][<acknowledgment id>][JSON-stringified payload without binary]
     */
    private fun encodeAsText(packet: Packet): String = buildString {
        // packet type
        val packetType = when {
            packet.type == Packet.Type.EVENT && packet.isBinary -> BINARY_EVENT_PACKET_TYPE
            packet.type == Packet.Type.ACK && packet.isBinary -> BINARY_ACK_PACKET_TYPE
            else -> packet.type.ordinal
        }
        append(packetType)

        // number of binary attachments
        packet.payload?.buffers?.size?.takeIf { it > 0 }?.let { append("$it-") }

        // namespace
        if (packet.namespace != "/") append("${packet.namespace},")

        // acknowledgment id
        packet.ackId?.let { append(it) }

        // JSON-stringified payload without binary
        packet.payload?.data?.let { elements ->
            if (
                elements.size == 1 &&
                elements[0] !is JsonPrimitive &&
                elements[0].isAttachmentPlaceholder.not()
            ) {
                append(NON_BINARY_JSON.encodeToString(elements[0]))
            } else {
                append(NON_BINARY_JSON.encodeToString(elements))
            }
        }
    }

    override fun decode(text: String): Parser.Decoded = decodeText(text)

    override fun decodeBinary(bytes: ByteArray, partial: Parser.Decoded): Parser.Decoded {
        if (partial !is Parser.Decoded.Partial1) return partial
        val p = partial.copy(buffers = partial.buffers + bytes)

        return if (p.numberOfAttachments == p.buffers.size) {
            val packet = decodePacketFromJson(
                payload = p.payload,
                type = p.type,
                namespace = p.namespace,
                ackId = p.ackId,
                buffers = p.buffers.toMutableList(),
            )
            Parser.Decoded.Completed(packet)
        } else {
            p
        }
    }

    private fun decodeText(text: String): Parser.Decoded {
        require(text.isNotEmpty()) { "Empty data string" }

        val packetType = text[0].digitToIntOrNull()
        require(packetType != null && packetType in 0..MAX_PACKET_TYPE_VALUE) { "Invalid packet type: ${text[0]}" }

        val (_, attachmentsCount, namespace, ackId, payload) = decodeNextToken(PacketParts(text.drop(1)))
        val type = when {
            packetType < Packet.Type.entries.size -> Packet.Type.entries[packetType]
            packetType == BINARY_EVENT_PACKET_TYPE && attachmentsCount != null -> Packet.Type.EVENT
            packetType == BINARY_ACK_PACKET_TYPE && attachmentsCount != null -> Packet.Type.ACK
            else -> error("Invalid packet type: $packetType")
        }

        return if (attachmentsCount != null && attachmentsCount > 0) {
            Parser.Decoded.Partial1(
                type = type,
                namespace = namespace ?: "/",
                payload = payload,
                ackId = ackId,
                numberOfAttachments = attachmentsCount,
                buffers = emptyList()
            )
        } else {
            val packet = decodePacketFromJson(payload, type, namespace, ackId)
            Parser.Decoded.Completed(packet)
        }
    }

    private fun decodePacketFromJson(
        payload: String?,
        type: Packet.Type,
        namespace: String?,
        ackId: Long?,
        buffers: MutableList<ByteArray> = mutableListOf()
    ): Packet {
        val jsonElement = payload?.let { NON_BINARY_JSON.decodeFromString<JsonElement>(it) }
        require(jsonElement == null || jsonElement is JsonObject || jsonElement is JsonArray) { "Invalid JSON payload: $payload" }

        val packetData = when (jsonElement) {
            is JsonObject -> listOf(jsonElement)
            is JsonArray -> jsonElement.map { it }
            null -> null
            else -> error("Invalid JSON data type")
        }

        val packet = Packet(
            type = type,
            namespace = namespace ?: "/",
            payload = packetData?.let { Packet.Payload(it, buffers) },
            ackId = ackId
        )

        return packet
    }

    private fun decodeNextToken(parts: PacketParts, token: Token? = Token.ATTACHMENT_COUNT): PacketParts {
        token ?: return parts

        val packetParts = when (token) {
            Token.ATTACHMENT_COUNT -> decodeAttachmentToken(parts)
            Token.NAMESPACE -> decodeNamespaceToken(parts)
            Token.ACK_ID -> decodeAckId(parts)
            Token.PAYLOAD -> parts.copy(payload = parts.text.takeIf { it.isNotEmpty() })
        }

        return decodeNextToken(packetParts, token.next)
    }

    private fun decodeAttachmentToken(tokens: PacketParts): PacketParts {
        val terminatorIdx = tokens.text.indexOf('-')
        if (terminatorIdx == -1) return tokens
        val tok = tokens.text.substring(0, terminatorIdx)
        val attachmentsCount = tok.toIntOrNull() ?: return tokens
        return tokens.copy(
            text = tokens.text.substring(terminatorIdx + 1),
            attachmentsCount = attachmentsCount
        )
    }

    private fun decodeNamespaceToken(tokens: PacketParts): PacketParts {
        if (tokens.text.startsWith("/").not()) return tokens
        return tokens.copy(
            text = tokens.text.substringAfter(","),
            namespace = tokens.text.substringBefore(","),
        )
    }

    private fun decodeAckId(tokens: PacketParts): PacketParts {
        val tok = tokens.text.takeWhile { it.isDigit() }
        if (tok.isEmpty()) return tokens
        val ackId = tok.toLong()
        return tokens.copy(text = tokens.text.substringAfter(tok), ackId = ackId)
    }

    private data class PacketParts(
        val text: String,
        val attachmentsCount: Int? = null,
        val namespace: String? = null,
        val ackId: Long? = null,
        val payload: String? = null,
    )

    private enum class Token {
        ATTACHMENT_COUNT, NAMESPACE, ACK_ID, PAYLOAD;

        val next: Token?
            get() = when (this) {
                ATTACHMENT_COUNT -> NAMESPACE
                NAMESPACE -> ACK_ID
                ACK_ID -> PAYLOAD
                PAYLOAD -> null
            }
    }

    companion object {
        private const val BINARY_EVENT_PACKET_TYPE = 5
        private const val BINARY_ACK_PACKET_TYPE = 6
        private const val MAX_PACKET_TYPE_VALUE = 6
        private val NON_BINARY_JSON: Json = ioJson(buffers = null)
    }
}
