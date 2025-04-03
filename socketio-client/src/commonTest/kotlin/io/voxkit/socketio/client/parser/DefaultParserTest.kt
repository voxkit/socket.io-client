package io.voxkit.socketio.client.parser

import io.voxkit.socketio.client.ConnectError
import io.voxkit.socketio.client.ConnectSuccess
import io.voxkit.socketio.client.util.dataOf
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
                "Failed to encode CONNECT packet"
            ),
            Triple(
                Packet(Packet.Type.CONNECT, "/admin", data = dataOf(ConnectSuccess(sid = "oSO0OpakMV_3jnilAAAA"))),
                Parser.Encoded.Text("""0/admin,{"sid":"oSO0OpakMV_3jnilAAAA"}"""),
                """Failed to encode CONNECT packet with namespace "/admin" and data"""
            ),
            Triple(
                Packet(Packet.Type.CONNECT_ERROR, data = dataOf(ConnectError(message = "Not authorized"))),
                Parser.Encoded.Text("""4{"message":"Not authorized"}"""),
                """Failed to encode CONNECT_ERROR packet with data"""
            ),
            Triple(
                Packet(Packet.Type.DISCONNECT),
                Parser.Encoded.Text("1"),
                "Failed to encode DISCONNECT packet"
            ),
            Triple(
                Packet(Packet.Type.EVENT, data = dataOf("foo")),
                Parser.Encoded.Text("""2["foo"]"""),
                "Failed to encode EVENT packet with data"
            ),
            Triple(
                Packet(Packet.Type.EVENT, "/admin", data = dataOf("bar")),
                Parser.Encoded.Text("""2/admin,["bar"]"""),
                """Failed to encode EVENT packet with "/admin" namespace and data"""
            ),
            Triple(
                Packet(Packet.Type.EVENT, data = dataOf("foo"), ackId = 12),
                Parser.Encoded.Text("""212["foo"]"""),
                "Failed to encode EVENT packet with acknowledgment ID"
            ),
            Triple(
                Packet(Packet.Type.ACK, "/admin", data = dataOf("bar"), ackId = 13),
                Parser.Encoded.Text("""3/admin,13["bar"]"""),
                "Failed to encode ACK packet with acknowledgment ID"
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
                    Packet.Type.BINARY_EVENT,
                    data = dataOf("baz", byteArrayOf(1, 2, 3, 4))
                ),
                listOf(
                    """51-["baz",{"_placeholder":true,"num":0}]""",
                    byteArrayOf(1, 2, 3, 4),
                ),
                "Failed to encode BINARY_EVENT packet"
            ),

            Triple(
                Packet(
                    Packet.Type.BINARY_EVENT,
                    namespace = "/admin",
                    data = dataOf("baz", byteArrayOf(1, 2), byteArrayOf(3, 4))
                ),
                listOf(
                    """52-/admin,["baz",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""",
                    byteArrayOf(1, 2),
                    byteArrayOf(3, 4),
                ),
                "Failed to encode BINARY_EVENT packet with multiple attachments"
            ),

            Triple(
                Packet(
                    Packet.Type.BINARY_ACK,
                    namespace = "/",
                    data = dataOf("bar", byteArrayOf(1, 2, 3, 4)),
                    ackId = 15
                ),
                listOf(
                    """61-15["bar",{"_placeholder":true,"num":0}]""",
                    byteArrayOf(1, 2, 3, 4),
                ),
                "Failed to encode BINARY_EVENT packet with multiple attachments"
            ),

            Triple(
                Packet(
                    Packet.Type.BINARY_ACK,
                    namespace = "/",
                    data = dataOf(byteArrayOf(1, 2, 3, 4)),
                    ackId = 15
                ),
                listOf(
                    """61-15[{"_placeholder":true,"num":0}]""",
                    byteArrayOf(1, 2, 3, 4),
                ),
                "Failed to encode BINARY_EVENT packet with multiple attachments"
            )
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
                "Failed to decode CONNECT packet"
            ),
            Triple(
                """0/admin,{"sid":"oSO0OpakMV_3jnilAAAA"}""",
                Packet(Packet.Type.CONNECT, "/admin", data = dataOf(ConnectSuccess(sid = "oSO0OpakMV_3jnilAAAA"))),
                """Failed to decode CONNECT packet with namespace "/admin" and data"""
            ),
            Triple(
                """4{"message":"Not authorized"}""",
                Packet(Packet.Type.CONNECT_ERROR, data = dataOf(ConnectError(message = "Not authorized"))),
                """Failed to decode CONNECT_ERROR packet with data"""
            ),
            Triple(
                "1",
                Packet(Packet.Type.DISCONNECT),
                "Failed to decode DISCONNECT packet"
            ),
            Triple(
                """2["foo"]""",
                Packet(Packet.Type.EVENT, data = dataOf("foo")),
                "Failed to decode EVENT packet with data"
            ),
            Triple(
                """2/admin,["bar"]""",
                Packet(Packet.Type.EVENT, "/admin", data = dataOf("bar")),
                """Failed to decode EVENT packet with "/admin" namespace and data"""
            ),
            Triple(
                """212["foo"]""",
                Packet(Packet.Type.EVENT, data = dataOf("foo"), ackId = 12),
                "Failed to decode EVENT packet with acknowledgment ID"
            ),
            Triple(
                """3/admin,13["bar"]""",
                Packet(Packet.Type.ACK, "/admin", data = dataOf("bar"), ackId = 13),
                "Failed to decode ACK packet with acknowledgment ID"
            )
        )

        for ((encoded, expected, message) in testCases) {
            val decoded = parser.decode(encoded)
            assertEquals(expected, decoded, message)
        }
    }

    @Test
    fun testDecodingBinaryEvent() {
        val binaryData = listOf(
            """51-["baz",{"_placeholder":true,"num":0}]""",
            byteArrayOf(1, 2, 3, 4)
        )

        val packet = parser.decode(binaryData[0] as String)
        assertEquals(Packet.Type.BINARY_EVENT, packet.type)

        val decoded = parser.decodeBinary(binaryData[1] as ByteArray, Parser.Decoded.Partial(packet))
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")

        assertEquals(
            Packet(
                Packet.Type.BINARY_EVENT,
                data = dataOf("baz", byteArrayOf(1, 2, 3, 4))
            ),
            decoded.packet
        )
    }

    @Test
    fun testDecodingBinaryEventWithMultipleAttachments() {
        val binaryDataMultiple = listOf(
            """52-/admin,["baz",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""",
            byteArrayOf(1, 2),
            byteArrayOf(3, 4)
        )

        val packet = parser.decode(binaryDataMultiple[0] as String)
        assertEquals(Packet.Type.BINARY_EVENT, packet.type)

        var decoded = parser.decodeBinary(binaryDataMultiple[1] as ByteArray, Parser.Decoded.Partial(packet))
        assertIs<Parser.Decoded.Partial>(decoded, "Should decode as partial packet")

        decoded = parser.decodeBinary(binaryDataMultiple[2] as ByteArray, decoded)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")

        assertEquals(
            Packet(
                Packet.Type.BINARY_EVENT,
                namespace = "/admin",
                data = dataOf(
                    "baz",
                    byteArrayOf(1, 2),
                    byteArrayOf(3, 4),
                )
            ),
            decoded.packet
        )
    }

    @Test
    fun testDecodingBinaryAck() {
        val binaryData = listOf(
            """61-15["bar",{"_placeholder":true,"num":0}]""",
            byteArrayOf(1, 2, 3, 4)
        )

        val packet = parser.decode(binaryData[0] as String)
        assertEquals(Packet.Type.BINARY_ACK, packet.type)

        val decoded = parser.decodeBinary(binaryData[1] as ByteArray, Parser.Decoded.Partial(packet))
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")
        assertEquals(
            Packet(
                Packet.Type.BINARY_ACK,
                data = dataOf("bar", byteArrayOf(1, 2, 3, 4)),
                ackId = 15
            ),
            decoded.packet
        )
    }

    @Test
    fun testDecodeBinaryAcWithMultipleAttachments() {
        val binaryDataMultiple = listOf(
            """61-15[{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""",
            byteArrayOf(1, 2, 3, 4),
            byteArrayOf(5, 6)
        )

        val packet = parser.decode(binaryDataMultiple[0] as String)
        assertEquals(Packet.Type.BINARY_ACK, packet.type)

        var decoded = parser.decodeBinary(binaryDataMultiple[1] as ByteArray, Parser.Decoded.Partial(packet))
        assertIs<Parser.Decoded.Partial>(decoded, "Should decode as partial packet")

        decoded = parser.decodeBinary(binaryDataMultiple[2] as ByteArray, decoded)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")

        assertEquals(
            Packet(
                Packet.Type.BINARY_ACK,
                data = dataOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6)),
                ackId = 15
            ),
            decoded.packet
        )
    }
}
