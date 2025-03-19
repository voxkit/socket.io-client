package io.voxkit.engineio.parser

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParserTest {
    @Test
    fun encodeAsString() {
        assertIs<String>(Parser.encodePacket(Packet.Message("test")))
    }

    @Test
    fun decodeAsPacket() {
        val encoded = Parser.encodePacket(Packet.Message("test"))
        val p = Parser.decodePacket(encoded as String)
        assertIs<Packet.Message>(p)
        assertEquals("test", p.data)
    }

    @Test
    fun noData() {
        val data = Parser.encodePacket(Packet.Message())
        val p = Parser.decodePacket(data as String)
        assertIs<Packet.Message>(p)
        assertEquals("", p.data)
    }

    @Test
    fun encodeOpenPacket() {
        val packet = Packet.Open(
            sid = "123",
            upgrades = listOf("websocket"),
            pingInterval = 1000,
            pingTimeout = 2000,
            maxPayload = 100000,
        )
        val data = Parser.encodePacket(packet)

        val p = Parser.decodePacket(data as String)

        assertIs<Packet.Open>(p, "Packet should be of type Open (encoded payload: $data)")
        assertEquals(packet, p)
    }

    @Test
    fun encodeClosePacket() {
        val data = Parser.encodePacket(Packet.Close)
        val p = Parser.decodePacket(data as String)
        assertEquals(Packet.Close, p)
    }

    @Test
    fun encodePingPacket() {
        val data = Parser.encodePacket(Packet.Ping("1"))
        val p = Parser.decodePacket(data as String)
        assertIs<Packet.Ping>(p)
        assertEquals("1", p.data)
    }

    @Test
    fun encodeMessagePacket() {
        val data = Parser.encodePacket(Packet.Message("aaa"))
        val p = Parser.decodePacket(data as String)
        assertIs<Packet.Message>(p, "Packet should be of type Message.Text (encoded payload: $data)")
        assertEquals("aaa", p.data)
    }

    @Test
    fun encodeMessagePacketWithBinaryData() {
        val data = Parser.encodePacket(Packet.Binary(byteArrayOf(1, 2, 3)))
        val p = Parser.decodePacket(data as ByteArray)
        assertIs<Packet.Binary>(p)
        assertContentEquals(byteArrayOf(1, 2, 3), p.data)
    }

    @Test
    fun decodeEmptyPayload() {
        val p = Parser.decodePacket(null as String?)
        assertIs<Packet.Error>(p)
        assertContains(p.data, ERROR_DATA)
    }

    @Test
    fun decodeBadFormat() {
        val p = Parser.decodePacket(":::")
        assertIs<Packet.Error>(p)
        assertContains(p.data, ERROR_DATA)
    }

    @Test
    fun decodeTextPacket() {
        val p = Parser.decodePacket("4test")
        assertIs<Packet.Message>(p)
        assertEquals("test", p.data)
    }

    @Test
    fun encodePayloads() {
        val data = Parser.encodePayload(listOf(Packet.Ping(), Packet.Pong()))
        assertIs<String>(data)
        assertEquals("2\u001e3", data)
    }

    @Test
    fun testEncodeEmptyPayload() {
        val data = Parser.encodePayload(emptyList())
        assertEquals("0:", data)
    }

    @Test
    fun encodeAndDecodePayloads() {
        val data = Parser.encodePayload(listOf(Packet.Message("a")))
        assertEquals(1, Parser.decodePayload(data).size)
    }

    @Test
    fun decodePacketWithInvalidType() {
        val p = Parser.decodePacket("9invalid")
        assertIs<Packet.Error>(p)
        assertContains(p.data, ERROR_DATA)
    }

    @Test
    fun decodePayloadWithMultiplePackets() {
        val data = "2\u001e3"

        val packets = Parser.decodePayload(data)

        assertEquals(2, packets.size)
        assertIs<Packet.Ping>(packets[0])
        assertIs<Packet.Pong>(packets[1])
    }

    @Test
    fun decodePayloadWithInvalidPacket() {
        val data = "2\u001e9invalid"

        val packets = Parser.decodePayload(data)

        assertEquals(2, packets.size)
        assertIs<Packet.Ping>(packets[0])
        assertIs<Packet.Error>(packets[1])
        assertContains(packets[1].data(), ERROR_DATA)
    }

    @Test
    fun encodeMixedBinaryAndStringContents() {
        val binaryData = byteArrayOf(1, 2, 3)
        val stringData = "test"
        val packets = listOf(
            Packet.Binary(binaryData),
            Packet.Message(stringData)
        )

        val encodedPayload = Parser.encodePayload(packets)

        val decodedPackets = Parser.decodePayload(encodedPayload)

        assertEquals(2, decodedPackets.size)
        assertIs<Packet.Binary>(decodedPackets[0])
        assertContentEquals(binaryData, (decodedPackets[0] as Packet.Binary).data)
        assertIs<Packet.Message>(decodedPackets[1])
        assertEquals(stringData, (decodedPackets[1] as Packet.Message).data)
    }

    companion object {
        const val ERROR_DATA = "parser error"
    }
}
