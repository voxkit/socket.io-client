package io.voxkit.socketio.client.util

import io.voxkit.socketio.client.parser.Binary
import io.voxkit.socketio.client.parser.Packet
import io.voxkit.socketio.client.parser.ioJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/**
 * Decodes a JSON element at the specified index in the packet's data into an object of type `T`.
 * Returns `null` if the index is out of bounds or if decoding fails.
 *
 * @param index The index of the JSON element in the packet's data.
 * @return The decoded object of type `T`, or `null` if decoding is not possible.
 */
public inline fun <reified T> Packet.Payload.decodeJsonOrNull(index: Int): T? {
    val jsonElement = data.getOrNull(index) ?: return null
    val ioJson = ioJson(buffers)
    return ioJson.decodeFromJsonElement(jsonElement)
}

internal fun Any?.jsonElement(buffers: MutableList<ByteArray>): JsonElement {
    val ioJson = ioJson(buffers)
    return when (this) {
        is Binary -> ioJson.encodeToJsonElement(this)
        is Map<*, *> -> jsonObject(this, buffers)
        is List<*> -> jsonArray(this, buffers)
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        null -> JsonNull
        else -> ioJson.encodeToJsonElement(ioJson.serializersModule.serializer(), this)
    }
}

private fun jsonObject(map: Map<*, *>, buffers: MutableList<ByteArray>): JsonObject {
    return buildJsonObject {
        for ((key, value) in map) {
            put(key.toString(), value.jsonElement(buffers))
        }
    }
}

private fun jsonArray(list: List<*>, buffers: MutableList<ByteArray>): JsonArray {
    return buildJsonArray { list.forEach { it.jsonElement(buffers) } }
}

internal inline fun packetPayloadOf(vararg args: Any): Packet.Payload {
    val buffers = mutableListOf<ByteArray>()
    return Packet.Payload(args.map { it.jsonElement(buffers) }, buffers)
}

internal inline fun <reified T : Any> T.toPacketPayload(): Packet.Payload {
    val buffers = mutableListOf<ByteArray>()
    val ioJson = ioJson(buffers)
    return Packet.Payload(listOf(ioJson.encodeToJsonElement(this)), buffers)
}
