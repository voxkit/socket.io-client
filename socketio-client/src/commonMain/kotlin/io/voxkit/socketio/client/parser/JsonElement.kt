package io.voxkit.socketio.client.parser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val JsonElement.isAttachmentPlaceholder: Boolean
    get() {
        val jsonObject = this as? JsonObject ?: return false
        val isPlaceholder = jsonObject["_placeholder"] == JsonPrimitive(true)
        val isNum = (jsonObject["num"] as? JsonPrimitive)?.isString == false
        return isPlaceholder && isNum
    }

internal val JsonElement.stringOrNull: String?
    get() {
        val jsonPrimitive = this as? JsonPrimitive ?: return null
        return jsonPrimitive.contentOrNull?.takeIf { jsonPrimitive.isString }
    }
