package com.neurone.myblocker.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeqMathTest {
    @Test fun wrapsAround() {
        assertEquals(0L, FullTunnel.seqAdd(0xFFFFFFFFL, 1))
        assertEquals(5L, FullTunnel.seqAdd(0xFFFFFFFFL, 6))
        assertEquals(0xFFFFFFFFL, FullTunnel.seqAdd(0L, -1))
    }

    @Test fun signedDistance() {
        assertEquals(10L, FullTunnel.seqDiff(20, 10))
        assertEquals(-10L, FullTunnel.seqDiff(10, 20))
        assertTrue(FullTunnel.seqDiff(3, 0xFFFFFFFDL) > 0) // just after wrap counts as ahead
        assertTrue(FullTunnel.seqDiff(0xFFFFFFFDL, 3) < 0)
    }
}
