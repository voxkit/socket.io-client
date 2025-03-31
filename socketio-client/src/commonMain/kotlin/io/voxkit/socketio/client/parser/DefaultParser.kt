package io.voxkit.socketio.client.parser

import io.ktor.utils.io.core.*
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
internal class DefaultParser : Parser {
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
                if (elements.size == 1 && elements[0] !is JsonPrimitive) {
                    append(Json.encodeToString(elements[0]))
                } else {
                    append(Json.encodeToString(elements))
                }
            }
        }
    }

    private fun toJsonElement(placeholderIndex: () -> Int, data: Packet.Data): JsonElement {
        return when (data) {
            is Packet.Data.Binary -> {
                JsonObject(
                    mapOf(
                        "_placeholder" to JsonPrimitive(true),
                        "num" to JsonPrimitive(placeholderIndex())
                    )
                )
            }

            is Packet.Data.Json -> data.element
        }
    }

    override fun decode(text: String): Packet = decodeText(text)

    override fun decode(bytes: ByteArray, partial: Parser.Decoded.Partial?): Parser.Decoded {
        return if (partial == null) decodeFirstChunk(bytes) else decodeNextChunk(partial, bytes)
    }

    private fun decodeFirstChunk(bytes: ByteArray): Parser.Decoded.Partial {
        val packet = decodeText(bytes.decodeToString())
        require(packet.type == Packet.Type.BINARY_EVENT || packet.type == Packet.Type.BINARY_ACK) {
            "Invalid packet type for binary data: ${packet.type}"
        }
        return Parser.Decoded.Partial(packet)
    }

    private fun decodeNextChunk(partial: Parser.Decoded.Partial, bytes: ByteArray): Parser.Decoded {
        val placeholdersCount = partial.packet.placeholdersCount
        require(placeholdersCount > 0) { "No placeholders found in the packet for binary data" }
        val packet = partial.packet.copy(data = replacePlaceholder(partial.packet.data, bytes))
        return if (placeholdersCount == 1) {
            Parser.Decoded.Completed(packet)
        } else {
            Parser.Decoded.Partial(packet)
        }
    }

    private fun replacePlaceholder(data: List<Packet.Data>?, bytes: ByteArray): List<Packet.Data>? {
        data ?: return data
        val newData = data.toMutableList()

        repeat(newData.size) { i ->
            val el = newData[i]
            if (el is Packet.Data.Json && el.element.isAttachmentPlaceholder) {
                newData[i] = Packet.Data.Binary(bytes)
                return newData
            }
        }

        return newData
    }

    private fun decodeText(text: String): Packet {
        require(text.isNotEmpty()) { "Empty data string" }

        val packetType = text[0].digitToIntOrNull()
        require(packetType != null && packetType in 0..Packet.Type.entries.size) { "Invalid packet type: ${text[0]}" }

        val type = Packet.Type.entries[packetType]
        val (_, attachmentsCount, namespace, ackId, payload) = decodeNextToken(PacketParts(text.drop(1)))
        if (type == Packet.Type.BINARY_EVENT || type == Packet.Type.BINARY_ACK) {
            require(attachmentsCount != null) { "Missing attachments count for binary packet" }
        }

        val jsonElement = payload?.let { JSON.decodeFromString<JsonElement>(it) }
        require(jsonElement == null || jsonElement is JsonObject || jsonElement is JsonArray) { "Invalid JSON payload: $payload" }

        val packetData = when (jsonElement) {
            is JsonObject -> listOf(Packet.Data.Json(jsonElement))
            is JsonArray -> jsonElement.map { Packet.Data.Json(it) }
            null -> null
            else -> error("Invalid JSON data type")
        }

        return Packet(type = type, namespace = namespace ?: "/", data = packetData, ackId = ackId)
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
        val ackId = tok.toInt()
        return tokens.copy(text = tokens.text.substringAfter(tok), ackId = ackId)
    }

    private data class PacketParts(
        val text: String,
        val attachmentsCount: Int? = null,
        val namespace: String? = null,
        val ackId: Int? = null,
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
        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
