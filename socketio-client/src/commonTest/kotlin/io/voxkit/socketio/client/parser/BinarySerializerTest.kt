package io.voxkit.socketio.client.parser

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BinarySerializerTest {
    @Test
    fun testBinarySerialization() {
        val buffer = byteArrayOf(1, 2, 3)
        val buffers = mutableListOf<ByteArray>()

        val json = ioJson(buffers)
        assertEquals(
            JsonObject(
                mapOf(
                    "key" to JsonPrimitive("value"),
                    "binary" to JsonObject(
                        mapOf(
                            "num" to JsonPrimitive(0),
                            "_placeholder" to JsonPrimitive(true),
                        ),
                    ),
                ),
            ),
            json.encodeToJsonElement(Foo("value", Binary(buffer))),
        )
        assertContentEquals(buffer, buffers[0])
    }

    @Test
    fun testBinaryDeserialization() {
        val buffer = byteArrayOf(1, 2, 3)
        val buffers = mutableListOf(buffer)
        val json = ioJson(buffers)

        val jsonObject = JsonObject(
            mapOf(
                "key" to JsonPrimitive("value"),
                "binary" to JsonObject(
                    mapOf(
                        "num" to JsonPrimitive(0),
                        "_placeholder" to JsonPrimitive(true),
                    ),
                ),
            ),
        )

        val foo = json.decodeFromJsonElement<Foo>(jsonObject)
        assertEquals(Foo("value", Binary(buffer)), foo)
    }
}

@Serializable
private data class Foo(val key: String, @Contextual val binary: Binary)
