package io.voxkit.socketio.client.parser

public data class Binary(val buffer: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Binary) return false

        if (!buffer.contentEquals(other.buffer)) return false

        return true
    }

    override fun hashCode(): Int {
        return buffer.contentHashCode()
    }
}

/**
 * Extension function to encode a `String` into a `Packet.Data.Binary` object.
 *
 * @return A `Packet.Data.Binary` object containing the encoded byte array of the string.
 */
public fun String.encodeToBinary(): Binary {
    return Binary(encodeToByteArray())
}

/**
 * Extension function to convert a `ByteArray` into a `Packet.Data.Binary` object.
 *
 * @return A `Packet.Data.Binary` object containing the byte array.
 */
public fun ByteArray.toBinary(): Binary {
    return Binary(this)
}

/**
 * Function to create a `Packet.Data.Binary` object from a variable number of `Byte` elements.
 *
 * @param elements The bytes to include in the `Packet.Data.Binary` object.
 * @return A `Packet.Data.Binary` object containing the provided bytes.
 */
public fun binaryOf(vararg elements: Byte): Binary {
    return Binary(elements)
}
