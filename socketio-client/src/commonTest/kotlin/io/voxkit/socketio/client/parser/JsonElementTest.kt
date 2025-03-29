package io.voxkit.socketio.client.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonElementTest {
    @Test
    fun testIsAttachmentPlaceholder() {
        val testCases = listOf(
            """{"_placeholder":true, "num":0}""" to true,
            """{"_placeholder":true, "num":1}""" to true,
            """{"_placeholder":false, "num":0}""" to false,
            """{"_placeholder":true}""" to false,
            """{"num":0}""" to false,
            """{"_placeholder":true, "num":"0"}""" to false,
            """{"_placeholder":true, "num":0, "extra":"data"}""" to true,
        )

        for ((jsonString, expected) in testCases) {
            val jsonElement = Json.decodeFromString<JsonElement>(jsonString)
            assertEquals(
                expected,
                jsonElement.isAttachmentPlaceholder,
                "Expected $expected for $jsonString"
            )
        }
    }
}
