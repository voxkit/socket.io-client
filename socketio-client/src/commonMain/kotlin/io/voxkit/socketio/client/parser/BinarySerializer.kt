package io.voxkit.socketio.client.parser

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
private data class BinaryPlaceholder(@SerialName("_placeholder") val placeholder: Boolean, val num: Int)

public class BinarySerializer(private val buffers: MutableList<ByteArray>) : KSerializer<Binary> {
    override val descriptor: SerialDescriptor = SerialDescriptor(
        serialName = "io.voxkit.socketio.client.parser.Binary",
        original = BinaryPlaceholder.serializer().descriptor,
    )

    override fun deserialize(decoder: Decoder): Binary {
        val placeholder = decoder.decodeSerializableValue(BinaryPlaceholder.serializer())
        val buffer = buffers.getOrNull(placeholder.num) ?: error("Buffer not found for index ${placeholder.num}")
        return Binary(buffer)
    }

    override fun serialize(encoder: Encoder, value: Binary) {
        val placeholder = BinaryPlaceholder(num = buffers.size, placeholder = true)
        buffers += value.buffer
        encoder.encodeSerializableValue(BinaryPlaceholder.serializer(), placeholder)
    }
}
