package io.voxkit.yeast

import kotlinx.datetime.Clock

/**
 * A Kotlin implementation of yeast. https://github.com/unshiftio/yeast
 */
public object Yeast {
    private val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_".toCharArray()
    private val alphabetLength = alphabet.size
    private var seed = 0L
    private var prev: String? = null
    private val map = alphabet.withIndex().associate { it.value to it.index }

    public fun encode(num: Long): String {
        var dividedNum = num
        return buildString {
            do {
                insert(0, alphabet[(dividedNum % alphabetLength).toInt()])
                dividedNum /= alphabetLength
            } while (dividedNum > 0)
        }
    }

    public fun decode(str: String): Long {
        return str.fold(0L) { decoded, c -> decoded * alphabetLength + (map[c] ?: 0) }
    }

    public fun yeast(): String {
        val now = encode(Clock.System.now().toEpochMilliseconds())

        return if (now != prev) {
            seed = 0
            prev = now
            now
        } else {
            "$now.${encode(seed++)}"
        }
    }
}
