package io.voxkit.socketio.client.parser

import io.voxkit.socketio.client.ConnectError
import io.voxkit.socketio.client.ConnectSuccess
import io.voxkit.socketio.client.util.decodeJsonOrNull
import io.voxkit.socketio.client.util.packetPayloadOf
import io.voxkit.socketio.client.util.toPacketPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DefaultParserTest {
    private val parser: Parser = DefaultParser()

    @Test
    fun testEncodingTextData() {
        val testCases = listOf(
            Triple(
                Packet(Packet.Type.CONNECT),
                Parser.Encoded.Text("0"),
                "Failed to encode CONNECT packet",
            ),
            Triple(
                Packet(
                    Packet.Type.CONNECT,
                    "/admin",
                    payload = ConnectSuccess(sid = "oSO0OpakMV_3jnilAAAA").toPacketPayload(),
                ),
                Parser.Encoded.Text("""0/admin,{"sid":"oSO0OpakMV_3jnilAAAA"}"""),
                """Failed to encode CONNECT packet with namespace "/admin" and data""",
            ),
            Triple(
                Packet(
                    Packet.Type.CONNECT_ERROR,
                    payload = ConnectError(message = "Not authorized").toPacketPayload(),
                ),
                Parser.Encoded.Text("""4{"message":"Not authorized"}"""),
                """Failed to encode CONNECT_ERROR packet with data""",
            ),
            Triple(
                Packet(Packet.Type.DISCONNECT),
                Parser.Encoded.Text("1"),
                "Failed to encode DISCONNECT packet",
            ),
            Triple(
                Packet(Packet.Type.EVENT, payload = packetPayloadOf("foo")),
                Parser.Encoded.Text("""2["foo"]"""),
                "Failed to encode EVENT packet with data",
            ),
            Triple(
                Packet(Packet.Type.EVENT, "/admin", payload = packetPayloadOf("bar")),
                Parser.Encoded.Text("""2/admin,["bar"]"""),
                """Failed to encode EVENT packet with "/admin" namespace and data""",
            ),
            Triple(
                Packet(Packet.Type.EVENT, payload = packetPayloadOf("foo"), ackId = 12),
                Parser.Encoded.Text("""212["foo"]"""),
                "Failed to encode EVENT packet with acknowledgment ID",
            ),
            Triple(
                Packet(Packet.Type.ACK, "/admin", payload = packetPayloadOf("bar"), ackId = 13),
                Parser.Encoded.Text("""3/admin,13["bar"]"""),
                "Failed to encode ACK packet with acknowledgment ID",
            ),
        )

        for ((packet, expected, message) in testCases) {
            assertEquals(expected, parser.encode(packet), message)
        }
    }

    @Test
    fun testEncodingBinaryData() {
        val testCases = listOf(
            Triple(
                Packet(
                    Packet.Type.EVENT,
                    payload = packetPayloadOf("baz", binaryOf(1, 2, 3, 4)),
                ),
                listOf(
                    """51-["baz",{"_placeholder":true,"num":0}]""",
                    byteArrayOf(1, 2, 3, 4),
                ),
                "Failed to encode binary EVENT packet",
            ),

            Triple(
                Packet(
                    Packet.Type.EVENT,
                    namespace = "/admin",
                    payload = packetPayloadOf("baz", binaryOf(1, 2), binaryOf(3, 4)),
                ),
                listOf(
                    """52-/admin,["baz",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""",
                    byteArrayOf(1, 2),
                    byteArrayOf(3, 4),
                ),
                "Failed to encode binary EVENT packet with multiple attachments",
            ),

            Triple(
                Packet(
                    Packet.Type.EVENT,
                    namespace = "/admin",
                    payload = packetPayloadOf(mapOf("foo" to "bar", "baz" to binaryOf(1, 2, 3, 4))),
                ),
                listOf(
                    """51-/admin,{"foo":"bar","baz":{"_placeholder":true,"num":0}}""",
                    byteArrayOf(1, 2, 3, 4),
                ),
                "Failed to encode EVENT mixing binary and JSON data",
            ),
            Triple(
                Packet(
                    Packet.Type.ACK,
                    namespace = "/",
                    payload = packetPayloadOf("bar", binaryOf(1, 2, 3, 4)),
                    ackId = 15,
                ),
                listOf(
                    """61-15["bar",{"_placeholder":true,"num":0}]""",
                    byteArrayOf(1, 2, 3, 4),
                ),
                "Failed to encode BINARY_EVENT packet with multiple attachments",
            ),

            Triple(
                Packet(
                    Packet.Type.ACK,
                    namespace = "/",
                    payload = packetPayloadOf(binaryOf(1, 2, 3, 4)),
                    ackId = 15,
                ),
                listOf(
                    """61-15[{"_placeholder":true,"num":0}]""",
                    byteArrayOf(1, 2, 3, 4),
                ),
                "Failed to encode BINARY_EVENT packet with multiple attachments",
            ),
        )

        for ((packet, expected, message) in testCases) {
            val encoded = parser.encode(packet)
            assertIs<Parser.Encoded.Binary>(encoded)
            assertEquals(expected[0] as String, encoded.buffers[0].decodeToString(), message)
            encoded.buffers.drop(1).forEachIndexed { i, buf ->
                assertContentEquals(expected[i + 1] as ByteArray, buf, message)
            }
        }
    }

    @Test
    fun testDecodingTextData() {
        val testCases = listOf(
            Triple(
                "0",
                Packet(Packet.Type.CONNECT),
                "Failed to decode CONNECT packet",
            ),
            Triple(
                """0/admin,{"sid":"oSO0OpakMV_3jnilAAAA"}""",
                Packet(
                    Packet.Type.CONNECT,
                    "/admin",
                    payload = ConnectSuccess(sid = "oSO0OpakMV_3jnilAAAA").toPacketPayload(),
                ),
                """Failed to decode CONNECT packet with namespace "/admin" and data""",
            ),
            Triple(
                """4{"message":"Not authorized"}""",
                Packet(
                    Packet.Type.CONNECT_ERROR,
                    payload = ConnectError(message = "Not authorized").toPacketPayload(),
                ),
                "Failed to decode CONNECT_ERROR packet with data",
            ),
            Triple(
                """4"Not authorized"""",
                Packet(Packet.Type.CONNECT_ERROR, payload = packetPayloadOf("Not authorized")),
                "Failed to decode CONNECT_ERROR packet with data",
            ),
            Triple(
                "1",
                Packet(Packet.Type.DISCONNECT),
                "Failed to decode DISCONNECT packet",
            ),
            Triple(
                """2["foo"]""",
                Packet(Packet.Type.EVENT, payload = packetPayloadOf("foo")),
                "Failed to decode EVENT packet with data",
            ),
            Triple(
                """2/admin,["bar"]""",
                Packet(Packet.Type.EVENT, "/admin", payload = packetPayloadOf("bar")),
                """Failed to decode EVENT packet with "/admin" namespace and data""",
            ),
            Triple(
                """212["foo"]""",
                Packet(Packet.Type.EVENT, payload = packetPayloadOf("foo"), ackId = 12),
                "Failed to decode EVENT packet with acknowledgment ID",
            ),
            Triple(
                """3/admin,13["bar"]""",
                Packet(Packet.Type.ACK, "/admin", payload = packetPayloadOf("bar"), ackId = 13),
                "Failed to decode ACK packet with acknowledgment ID",
            ),
        )

        for ((encoded, expected, message) in testCases) {
            val decoded = parser.decode(encoded)
            assertIs<Parser.Decoded.Completed>(decoded)
            assertEquals(expected, decoded.packet, message)
        }
    }

    @Test
    fun testDecodingBinaryEvent() {
        val binaryData = listOf(
            """51-["baz",{"_placeholder":true,"num":0}]""",
            byteArrayOf(1, 2, 3, 4),
        )

        val decodedPartial = parser.decode(binaryData[0] as String)
        assertIs<Parser.Decoded.Partial1>(decodedPartial)

        val decoded = parser.decodeBinary(binaryData[1] as ByteArray, decodedPartial)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")

        assertEquals(Packet.Type.EVENT, decoded.packet.type)
        assertEquals(
            buildList {
                add(JsonPrimitive("baz"))
                add(
                    JsonObject(
                        mapOf(
                            "_placeholder" to JsonPrimitive(true),
                            "num" to JsonPrimitive(0),
                        ),
                    ),
                )
            },
            decoded.packet.payload!!.data,
        )
        assertContentEquals(byteArrayOf(1, 2, 3, 4), decoded.packet.payload!!.buffers[0])
    }

    @Test
    fun testDecodingBinaryEventWithMultipleAttachments() {
        val binaryDataMultiple = listOf(
            """52-/admin,["baz",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""",
            byteArrayOf(1, 2),
            byteArrayOf(3, 4),
        )

        val decodedPartial = parser.decode(binaryDataMultiple[0] as String)
        assertIs<Parser.Decoded.Partial1>(decodedPartial)
        assertEquals(Packet.Type.EVENT, decodedPartial.type)

        var decoded = parser.decodeBinary(binaryDataMultiple[1] as ByteArray, decodedPartial)
        assertIs<Parser.Decoded.Partial1>(decoded, "Should decode as partial packet")

        decoded = parser.decodeBinary(binaryDataMultiple[2] as ByteArray, decoded)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")

        assertEquals(Packet.Type.EVENT, decoded.packet.type)
        assertEquals("/admin", decoded.packet.namespace)
        assertEquals("baz", decoded.packet.payload!!.decodeJsonOrNull<String>(0))
        assertEquals(binaryOf(1, 2), decoded.packet.payload!!.decodeJsonOrNull<Binary>(1))
        assertEquals(binaryOf(3, 4), decoded.packet.payload!!.decodeJsonOrNull<Binary>(2))
    }

    @Test
    fun testDecodingBinaryAck() {
        val binaryData = listOf(
            """61-15["bar",{"_placeholder":true,"num":0}]""",
            byteArrayOf(1, 2, 3, 4),
        )

        val decodedPartial = parser.decode(binaryData[0] as String)
        assertIs<Parser.Decoded.Partial1>(decodedPartial)
        assertEquals(Packet.Type.ACK, decodedPartial.type)

        val decoded = parser.decodeBinary(binaryData[1] as ByteArray, decodedPartial)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")
        assertEquals(Packet.Type.ACK, decoded.packet.type)
        assertEquals(15, decoded.packet.ackId)
        assertEquals("bar", decoded.packet.payload!!.decodeJsonOrNull<String>(0))
        assertEquals(binaryOf(1, 2, 3, 4), decoded.packet.payload!!.decodeJsonOrNull<Binary>(1))
    }

    @Test
    fun testDecodeBinaryAcWithMultipleAttachments() {
        val binaryDataMultiple = listOf(
            """62-15[{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""",
            byteArrayOf(1, 2, 3, 4),
            byteArrayOf(5, 6),
        )

        val decodedPartial = parser.decode(binaryDataMultiple[0] as String)
        assertIs<Parser.Decoded.Partial1>(decodedPartial)
        assertEquals(Packet.Type.ACK, decodedPartial.type)

        var decoded = parser.decodeBinary(binaryDataMultiple[1] as ByteArray, decodedPartial)
        assertIs<Parser.Decoded.Partial1>(decoded, "Should decode as partial packet")

        decoded = parser.decodeBinary(binaryDataMultiple[2] as ByteArray, decoded)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")
        assertEquals(Packet.Type.ACK, decoded.packet.type)
        assertEquals(15, decoded.packet.ackId)
        assertEquals(binaryOf(1, 2, 3, 4), decoded.packet.payload!!.decodeJsonOrNull<Binary>(0))
        assertEquals(binaryOf(5, 6), decoded.packet.payload!!.decodeJsonOrNull<Binary>(1))
    }
}
