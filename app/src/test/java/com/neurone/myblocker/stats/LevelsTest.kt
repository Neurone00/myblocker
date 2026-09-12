package com.neurone.myblocker.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelsTest {
    @Test fun levelsProgress() {
        val l1 = Levels.forXp(0)
        assertEquals(1, l1.level)
        assertEquals("Light drizzle", l1.title)
        assertEquals(0, l1.progressPercent)
        val l2 = Levels.forXp(100)
        assertEquals(2, l2.level)
        assertEquals(300L, l2.nextThreshold?.minus(200))
        val mid = Levels.forXp(300)
        assertEquals(2, mid.level)
        assertEquals(50, mid.progressPercent)
        val top = Levels.forXp(10_000_000)
        assertNull(top.nextThreshold)
        assertEquals(100, top.progressPercent)
    }
}
