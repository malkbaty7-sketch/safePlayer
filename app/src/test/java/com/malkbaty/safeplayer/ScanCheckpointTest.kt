package com.malkbaty.safeplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanCheckpointTest {
    @Test fun usesOnlyValidMatchingCheckpoints() {
        assertEquals(5L, checkpointGeneration("v1", 5, "v1", 8))
        assertEquals(8L, checkpointGeneration("v1", 8, "v1", 8))
        assertNull(checkpointGeneration(null, -1, "v1", 8))
        assertNull(checkpointGeneration("v1", 5, "v2", 8))
        assertNull(checkpointGeneration("v1", 9, "v1", 8))
        assertNull(checkpointGeneration("v1", -1, "v1", 8))
    }
}
