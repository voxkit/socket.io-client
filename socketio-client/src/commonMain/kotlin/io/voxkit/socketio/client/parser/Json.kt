package io.voxkit.socketio.client.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

@PublishedApi
internal fun ioJson(buffers: MutableList<ByteArray>?): Json = Json {
    buffers?.let { serializersModule = SerializersModule { contextual(BinarySerializer(buffers)) } }
    ignoreUnknownKeys = true
    encodeDefaults = true
}
