package io.voxkit.engineio.parser

import io.ktor.util.*
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal object Parser {
    const val PROTOCOL = 4

    private const val SEPARATOR = '\u001e'

    private val safeJson = Json { ignoreUnknownKeys = true }

    private val packets = mapOf(
        Packet.Open::class to 0,
        Packet.Close::class to 1,
        Packet.Ping::class to 2,
        Packet.Pong::class to 3,
        Packet.Message::class to 4,
        Packet.Upgrade::class to 5,
        Packet.Noop::class to 6
    )

    private val packetsList = packets
        .filterKeys { it != Packet.Binary::class }
        .entries
        .associate { it.value to it.key }

    fun encodePacket(packet: Packet): Any {
        return if (packet is Packet.Binary) {
            packet.data
        } else {
            val type = packets[packet::class]
            return "$type${packet.data()}"
        }
    }

    fun decodePacket(payload: String?): Packet {
        if (payload.isNullOrEmpty()) return Packet.Error("parser error: empty payload")

        val encodedType = payload.firstOrNull()?.digitToIntOrNull() ?: -1
        val type = packetsList[encodedType] ?: return Packet.Error("parser error: unknown packet type '$payload'")
        val data = payload.drop(1)

        val packet = when (type) {
            Packet.Open::class -> runCatching { safeJson.decodeFromString<Packet.Open>(data) }
                .getOrElse { Packet.Error("parser error: decode handshake data failed $data: $it") }

            Packet.Close::class -> Packet.Close
            Packet.Ping::class -> Packet.Ping(data)
            Packet.Pong::class -> Packet.Pong(data)
            Packet.Message::class -> Packet.Message(data)
            Packet.Upgrade::class -> Packet.Upgrade
            Packet.Noop::class -> Packet.Noop
            else -> Packet.Error("parser error: unknown packet type '$type'")
        }

        return packet
    }

    fun decodePacket(packet: ByteArray): Packet.Binary {
        return Packet.Binary(packet)
    }

    fun encodePayload(packets: List<Packet>): String {
        if (packets.isEmpty()) return "0:"

        val result = buildString {
            packets.forEachIndexed { index, packet ->
                val isLast = index == packets.lastIndex
                append(encodeBase64Packet(packet))
                if (!isLast) append(SEPARATOR)
            }
        }

        return result
    }

    private fun encodeBase64Packet(packet: Packet): Any {
        return if (packet is Packet.Binary) "b" + packet.data.encodeBase64() else encodePacket(packet)
    }

    fun decodePayload(data: String?): List<Packet> {
        if (data.isNullOrEmpty()) return listOf(Packet.Error("parser error: empty payload"))

        val messages = data.split(SEPARATOR)
        return messages.fold(mutableListOf()) { packets, msg ->
            val packet = decodeBase64Packet(msg)

            if (packet is Packet.Error) {
                packets += packet
                return packets
            }

            packets += packet
            packets
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Packet(data: String): Packet {
        return if (data.isBase64Packet) {
            runCatching { Packet.Binary(Base64.Default.decode(data.drop(1))) }
                .getOrElse { Packet.Error("parser error: decode Base64 failed [$data]: $it") }
        } else {
            decodePacket(data)
        }
    }

    private val String.isBase64Packet: Boolean get() = firstOrNull() == 'b' && length > 1
}
