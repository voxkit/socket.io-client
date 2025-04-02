package io.voxkit.socketio.client.util

import io.voxkit.socketio.client.parser.DefaultParser
import io.voxkit.socketio.client.parser.Packet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Converts the given object to a [Packet.Data] representation.
 * Only `ByteArray` and Kotlin serializable objects are supported.
 */
public inline fun <reified T> T.arg(): Packet.Data {
    return when (this) {
        is ByteArray -> Packet.Data.Binary(this)
        else -> Packet.Data.Json(DefaultParser.JSON.encodeToJsonElement(this))
    }
}

/**
 * Creates a list of [Packet.Data] from the provided arguments.
 * This function is useful for creating a packet with multiple data elements.
 *
 * The arguments can be of any type, but only `ByteArray` and Kotlin serializable objects are supported.
 *
 * @param arg The arguments to convert to [Packet.Data].
 * @return A list of [Packet.Data] representing the provided arguments.
 */
public inline fun <reified T : Any> dataOf(arg: T): List<Packet.Data> = listOf(arg.arg())

/**
 * Creates a list of [Packet.Data] from the provided arguments.
 * This function is useful for creating a packet with multiple data elements.
 *
 * The arguments can be of any type, but only `ByteArray` and Kotlin serializable objects are supported.
 *
 * @param arg1 The first argument to convert to [Packet.Data].
 * @param arg2 The second argument to convert to [Packet.Data].
 * @return A list of [Packet.Data] representing the provided arguments.
 */
public inline fun <reified T1 : Any, reified T2 : Any> dataOf(arg1: T1, arg2: T2): List<Packet.Data> =
    listOf(arg1.arg(), arg2.arg())

/**
 * Creates a list of [Packet.Data] from the provided arguments.
 * This function is useful for creating a packet with multiple data elements.
 *
 * The arguments can be of any type, but only `ByteArray` and Kotlin serializable objects are supported.
 *
 * @param arg1 The first argument to convert to [Packet.Data].
 * @param arg2 The second argument to convert to [Packet.Data].
 * @param arg3 The third argument to convert to [Packet.Data].
 * @return A list of [Packet.Data] representing the provided arguments.
 */
public inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> dataOf(
    arg1: T1,
    arg2: T2,
    arg3: T3
): List<Packet.Data> = listOf(arg1.arg(), arg2.arg(), arg3.arg())

/**
 * Creates a list of [Packet.Data] from the provided arguments.
 * This function is useful for creating a packet with multiple data elements.
 *
 * The arguments can be of any type, but only `ByteArray` and Kotlin serializable objects are supported.
 *
 * @param arg1 The first argument to convert to [Packet.Data].
 * @param arg2 The second argument to convert to [Packet.Data].
 * @param arg3 The third argument to convert to [Packet.Data].
 * @param arg4 The fourth argument to convert to [Packet.Data].
 * @return A list of [Packet.Data] representing the provided arguments.
 */
public inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any, reified T4 : Any> dataOf(
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4
): List<Packet.Data> = listOf(arg1.arg(), arg2.arg(), arg3.arg(), arg4.arg())

/**
 * Creates an array of [Packet.Data] from the provided arguments.
 * This function is useful for creating a packet with multiple data elements.
 *
 * The arguments can be of any type, but only `ByteArray` and Kotlin serializable objects are supported.
 *
 * @param arg The arguments to convert to [Packet.Data].
 * @return An array of [Packet.Data] representing the provided arguments.
 */
public inline fun <reified T : Any> argsOf(arg: T): Array<Packet.Data> = arrayOf(arg.arg())

/**
 * Creates an array of [Packet.Data] from the provided arguments.
 * This function is useful for creating a packet with multiple data elements.
 *
 * The arguments can be of any type, but only `ByteArray` and Kotlin serializable objects are supported.
 *
 * @param arg1 The first argument to convert to [Packet.Data].
 * @param arg2 The second argument to convert to [Packet.Data].
 * @return An array of [Packet.Data] representing the provided arguments.
 */
public inline fun <reified T1 : Any, reified T2 : Any> argsOf(arg1: T1, arg2: T2): Array<Packet.Data> =
    arrayOf(arg1.arg(), arg2.arg())

/**
 * Creates an array of [Packet.Data] from the provided arguments.
 * This function is useful for creating a packet with multiple data elements.
 *
 * The arguments can be of any type, but only `ByteArray` and Kotlin serializable objects are supported.
 *
 * @param arg1 The first argument to convert to [Packet.Data].
 * @param arg2 The second argument to convert to [Packet.Data].
 * @param arg3 The third argument to convert to [Packet.Data].
 * @return An array of [Packet.Data] representing the provided arguments.
 */
public inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> argsOf(
    arg1: T1,
    arg2: T2,
    arg3: T3
): Array<Packet.Data> = arrayOf(arg1.arg(), arg2.arg(), arg3.arg())

/**
 * Creates an array of [Packet.Data] from the provided arguments.
 * This function is useful for creating a packet with multiple data elements.
 *
 * The arguments can be of any type, but only `ByteArray` and Kotlin serializable objects are supported.
 *
 * @param arg1 The first argument to convert to [Packet.Data].
 * @param arg2 The second argument to convert to [Packet.Data].
 * @param arg3 The third argument to convert to [Packet.Data].
 * @param arg4 The fourth argument to convert to [Packet.Data].
 * @return An array of [Packet.Data] representing the provided arguments.
 */

public inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any, reified T4 : Any> argsOf(
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4
): Array<Packet.Data> = arrayOf(arg1.arg(), arg2.arg(), arg3.arg(), arg4.arg())

/**
 * Extension property to get the JSON element from a [Packet.Data] instance.
 *
 * @return The JSON element if the [Packet.Data] instance is of type [Packet.Data.Json] or `null`.
 */
public val Packet.Data.jsonElementOrNull: JsonElement? get() = (this as? Packet.Data.Json)?.element

/**
 * Extension property to get the JSON element from a [Packet.Data] instance.
 *
 * @throws IllegalStateException if the [Packet.Data] instance is not of type [Packet.Data.Json].
 */
public val Packet.Data.jsonElement: JsonElement
    get() = (this as? Packet.Data.Json)?.element ?: error("Not a JSON element")

/**
 * Extension property to get the byte array from a [Packet.Data] instance.
 *
 * @return The byte array if the [Packet.Data] instance is of type [Packet.Data.Binary] or `null`.
 */
public val Packet.Data.bytesOrNull: ByteArray? get() = (this as? Packet.Data.Binary)?.buffer

/**
 * Extension property to get the byte array from a [Packet.Data] instance.
 *
 * @throws IllegalStateException if the [Packet.Data] instance is not of type [Packet.Data.Binary].
 */
public val Packet.Data.bytes: ByteArray
    get() = (this as? Packet.Data.Binary)?.buffer ?: error("Not a binary element")
