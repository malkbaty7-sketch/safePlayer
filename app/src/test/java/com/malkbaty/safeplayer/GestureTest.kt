package com.malkbaty.safeplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureTest {
    @Test fun seekIsClampedToTheMediaAndGestureRange() {
        assertEquals(0L, gestureSeekPosition(5_000, -1f, 100_000))
        assertEquals(100_000L, gestureSeekPosition(90_000, 1f, 100_000))
        assertEquals(40_000L, gestureSeekPosition(10_000, 0.5f, 100_000))
        assertEquals(70_000L, gestureSeekPosition(10_000, 2f, 100_000))
    }
}
