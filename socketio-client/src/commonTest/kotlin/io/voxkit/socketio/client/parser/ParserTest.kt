package io.voxkit.socketio.client.parser

import io.ktor.utils.io.core.*
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParserTest {
    @Test
    fun testEncodingTextData() {
        val parser = ParserImpl()

        val testCases = listOf(
            Triple(
                Packet(Packet.Type.CONNECT),
                Parser.Encoded.Text("0"),
                "Failed to encode CONNECT packet"
            ),
            Triple(
                Packet(Packet.Type.CONNECT, "/admin", data = listOf(Sid().asPacketData())),
                Parser.Encoded.Text("""0/admin,{"sid":"oSO0OpakMV_3jnilAAAA"}"""),
                """Failed to encode CONNECT packet with namespace "/admin" and data"""
            ),
            Triple(
                Packet(Packet.Type.CONNECT_ERROR, data = listOf(ConnectError().asPacketData())),
                Parser.Encoded.Text("""4{"message":"Not authorized"}"""),
                """Failed to encode CONNECT_ERROR packet with data"""
            ),
            Triple(
                Packet(Packet.Type.DISCONNECT),
                Parser.Encoded.Text("1"),
                "Failed to encode DISCONNECT packet"
            ),
            Triple(
                Packet(Packet.Type.EVENT, data = listOf("foo".asPacketData())),
                Parser.Encoded.Text("""2["foo"]"""),
                "Failed to encode EVENT packet with data"
            ),
            Triple(
                Packet(Packet.Type.EVENT, "/admin", data = listOf("bar".asPacketData())),
                Parser.Encoded.Text("""2/admin,["bar"]"""),
                """Failed to encode EVENT packet with "/admin" namespace and data"""
            ),
            Triple(
                Packet(Packet.Type.EVENT, data = listOf("foo".asPacketData()), ackId = 12),
                Parser.Encoded.Text("""212["foo"]"""),
                "Failed to encode EVENT packet with acknowledgment ID"
            ),
            Triple(
                Packet(Packet.Type.ACK, "/admin", data = listOf("bar".asPacketData()), ackId = 13),
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
        val parser = ParserImpl()

        val testCases = listOf(
            Triple(
                Packet(
                    Packet.Type.BINARY_EVENT,
                    data = listOf("baz".asPacketData(), byteArrayOf(1, 2, 3, 4).asPacketData())
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
                    data = listOf(
                        "baz".asPacketData(),
                        byteArrayOf(1, 2).asPacketData(),
                        byteArrayOf(3, 4).asPacketData()
                    )
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
                    data = listOf(
                        "bar".asPacketData(),
                        byteArrayOf(1, 2, 3, 4).asPacketData(),
                    ),
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
        val parser = ParserImpl()

        val testCases = listOf(
            Triple(
                "0",
                Packet(Packet.Type.CONNECT),
                "Failed to decode CONNECT packet"
            ),
            Triple(
                """0/admin,{"sid":"oSO0OpakMV_3jnilAAAA"}""",
                Packet(Packet.Type.CONNECT, "/admin", data = listOf(Sid().asPacketData())),
                """Failed to decode CONNECT packet with namespace "/admin" and data"""
            ),
            Triple(
                """4{"message":"Not authorized"}""",
                Packet(Packet.Type.CONNECT_ERROR, data = listOf(ConnectError().asPacketData())),
                """Failed to decode CONNECT_ERROR packet with data"""
            ),
            Triple(
                "1",
                Packet(Packet.Type.DISCONNECT),
                "Failed to decode DISCONNECT packet"
            ),
            Triple(
                """2["foo"]""",
                Packet(Packet.Type.EVENT, data = listOf("foo".asPacketData())),
                "Failed to decode EVENT packet with data"
            ),
            Triple(
                """2/admin,["bar"]""",
                Packet(Packet.Type.EVENT, "/admin", data = listOf("bar".asPacketData())),
                """Failed to decode EVENT packet with "/admin" namespace and data"""
            ),
            Triple(
                """212["foo"]""",
                Packet(Packet.Type.EVENT, data = listOf("foo".asPacketData()), ackId = 12),
                "Failed to decode EVENT packet with acknowledgment ID"
            ),
            Triple(
                """3/admin,13["bar"]""",
                Packet(Packet.Type.ACK, "/admin", data = listOf("bar".asPacketData()), ackId = 13),
                "Failed to decode ACK packet with acknowledgment ID"
            )
        )

        for ((encoded, expected, message) in testCases) {
            val decoded = parser.decode(encoded)
            assertEquals(expected, decoded, message)
        }
    }

    @Test
    fun testDecodingBinaryData() {
        val parser = ParserImpl()

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
                data = listOf("baz".asPacketData(), byteArrayOf(1, 2, 3, 4).asPacketData())
            ),
            decoded.packet
        )
    }
}

@Serializable
data class Sid(val sid: String = "oSO0OpakMV_3jnilAAAA")

@Serializable
data class ConnectError(val message: String = "Not authorized")
