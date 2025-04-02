package io.voxkit.socketio.client.parser

import io.ktor.utils.io.core.*
import io.voxkit.socketio.client.util.dataOf
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DefaultParserTest {
    @Test
    fun testEncodingTextData() {
        val parser = DefaultParser()

        val testCases = listOf(
            Triple(
                Packet(Packet.Type.CONNECT),
                Parser.Encoded.Text("0"),
                "Failed to encode CONNECT packet"
            ),
            Triple(
                Packet(Packet.Type.CONNECT, "/admin", data = dataOf(Sid())),
                Parser.Encoded.Text("""0/admin,{"sid":"oSO0OpakMV_3jnilAAAA"}"""),
                """Failed to encode CONNECT packet with namespace "/admin" and data"""
            ),
            Triple(
                Packet(Packet.Type.CONNECT_ERROR, data = dataOf(ConnectError())),
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
        val parser = DefaultParser()

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
        )

        for ((packet, expected, message) in testCases) {
            val encoded = parser.encode(packet)
            assertIs<Parser.Encoded.Binary>(encoded)
            assertEquals(expected[0] as String, encoded.data[0].decodeToString(), message)
            encoded.data.drop(1).forEachIndexed { i, buf ->
                assertContentEquals(expected[i + 1] as ByteArray, buf, message)
            }
        }
    }

    @Test
    fun testDecodingTextData() {
        val parser = DefaultParser()

        val testCases = listOf(
            Triple(
                "0",
                Packet(Packet.Type.CONNECT),
                "Failed to decode CONNECT packet"
            ),
            Triple(
                """0/admin,{"sid":"oSO0OpakMV_3jnilAAAA"}""",
                Packet(Packet.Type.CONNECT, "/admin", data = dataOf(Sid())),
                """Failed to decode CONNECT packet with namespace "/admin" and data"""
            ),
            Triple(
                """4{"message":"Not authorized"}""",
                Packet(Packet.Type.CONNECT_ERROR, data = dataOf(ConnectError())),
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
        val parser = DefaultParser()

        // Test binary event
        val binaryData = listOf(
            """51-["baz",{"_placeholder":true,"num":0}]""".toByteArray(),
            byteArrayOf(1, 2, 3, 4)
        )

        var decoded = parser.decode(binaryData[0])
        assertIs<Parser.Decoded.Partial>(decoded, "Should decode as partial packet")

        decoded = parser.decode(binaryData[1], decoded)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")

        assertEquals(
            Packet(
                Packet.Type.BINARY_EVENT,
                data = dataOf("baz", byteArrayOf(1, 2, 3, 4))
            ),
            decoded.packet
        )

        // Test binary event with multiple attachments
        val binaryDataMultiple = listOf(
            """52-/admin,["baz",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""".toByteArray(),
            byteArrayOf(1, 2),
            byteArrayOf(3, 4)
        )

        decoded = parser.decode(binaryDataMultiple[0])

        assertIs<Parser.Decoded.Partial>(decoded, "Should decode as partial packet")

        decoded = parser.decode(binaryDataMultiple[1], decoded)
        assertIs<Parser.Decoded.Partial>(decoded, "Should decode as partial packet")

        decoded = parser.decode(binaryDataMultiple[2], decoded)
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
        val parser = DefaultParser()

        // Test binary ack
        val binaryData = listOf(
            """61-15["bar",{"_placeholder":true,"num":0}]""".toByteArray(),
            byteArrayOf(1, 2, 3, 4)
        )

        var decoded = parser.decode(binaryData[0])
        assertIs<Parser.Decoded.Partial>(decoded, "Should decode as partial packet")
        decoded = parser.decode(binaryData[1], decoded)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")
        assertEquals(
            Packet(
                Packet.Type.BINARY_ACK,
                data = dataOf("bar", byteArrayOf(1, 2, 3, 4)),
                ackId = 15
            ),
            decoded.packet
        )

        // Test binary ack with multiple attachments
        val binaryDataMultiple = listOf(
            """61-15["bar",{"_placeholder":true,"num":0},{"_placeholder":true,"num":1}]""".toByteArray(),
            byteArrayOf(1, 2),
            byteArrayOf(3, 4)
        )

        decoded = parser.decode(binaryDataMultiple[0])
        assertIs<Parser.Decoded.Partial>(decoded, "Should decode as partial packet")

        decoded = parser.decode(binaryDataMultiple[1], decoded)
        assertIs<Parser.Decoded.Partial>(decoded, "Should decode as partial packet")

        decoded = parser.decode(binaryDataMultiple[2], decoded)
        assertIs<Parser.Decoded.Completed>(decoded, "Should complete after receiving binary data")

        assertEquals(
            Packet(
                Packet.Type.BINARY_ACK,
                data = dataOf("bar", byteArrayOf(1, 2), byteArrayOf(3, 4)),
                ackId = 15
            ),
            decoded.packet
        )
    }
}

@Serializable
data class Sid(val sid: String = "oSO0OpakMV_3jnilAAAA")

@Serializable
data class ConnectError(val message: String = "Not authorized")
