package io.voxkit.yeast

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class YeastTest {
    private fun waitUntilNextMillisecond() {
        val now = Clock.System.now().toEpochMilliseconds()
        while (Clock.System.now().toEpochMilliseconds() == now) { /* do nothing */ }
    }

    @Test
    fun testPrependsIteratedSeedWhenSamePreviousId() {
        waitUntilNextMillisecond()

        val ids = listOf(Yeast.yeast(), Yeast.yeast(), Yeast.yeast())
        assertFalse(ids[0].contains("."))
        assertTrue(ids[1].contains(".0"))
        assertTrue(ids[2].contains(".1"))
    }

    @Test
    fun testResetsTheSeed() {
        waitUntilNextMillisecond()

        val ids = listOf(Yeast.yeast(), Yeast.yeast(), Yeast.yeast())
        assertFalse(ids[0].contains("."))
        assertTrue(ids[1].contains(".0"))
        assertTrue(ids[2].contains(".1"))

        waitUntilNextMillisecond()

        val newIds = listOf(Yeast.yeast(), Yeast.yeast(), Yeast.yeast())
        assertFalse(newIds[0].contains("."))
        assertTrue(newIds[1].contains(".0"))
        assertTrue(newIds[2].contains(".1"))
    }

    @Test
    fun testDoesNotCollide() {
        val length = 30000
        val ids = mutableListOf<String>()

        repeat(length) { ids.add(Yeast.yeast()) }

        ids.sort()

        for (i in 0..<(length - 1)) {
            assertNotEquals(ids[i], ids[i + 1])
        }
    }

    @Test
    fun testCanConvertIdToTimestamp() {
        waitUntilNextMillisecond()

        val now = Clock.System.now().toEpochMilliseconds()
        val id = Yeast.yeast()

        assertEquals(Yeast.encode(now), id)
        assertEquals(Yeast.decode(id), now)
    }
}
