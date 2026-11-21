package com.malkbaty.safeplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAvailabilityTest {
    @Test fun onlyCanonicalMissingExternalMediaStoreUrisAreReturned() {
        val entries = listOf(
            MediaEntry("content://media/external/video/media/1", "visible", "video", 0),
            MediaEntry("content://media/external/video/media/2", "missing video", "video", 0),
            MediaEntry("content://media/external/audio/media/3", "missing audio", "audio", 0),
            MediaEntry("content://provider/document/2", "document", "video", 0),
            MediaEntry("content://media/external/video/media/4/other", "noncanonical", "video", 0),
            MediaEntry("content://media/external/video/media/5", "wrong type", "audio", 0)
        )
        val result = unavailableMediaStoreEntries(entries, setOf("content://media/external/video/media/1"))
        assertEquals(setOf("content://media/external/video/media/2", "content://media/external/audio/media/3"), result.map { it.uri }.toSet())
    }
    @Test fun emptyInputsAreSafe() {
        assertEquals(emptyList<MediaEntry>(), unavailableMediaStoreEntries(emptyList(), emptySet()))
    }
}
