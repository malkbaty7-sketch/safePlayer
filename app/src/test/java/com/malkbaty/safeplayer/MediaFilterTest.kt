package com.malkbaty.safeplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFilterTest {
    private val items = listOf(
        MediaEntry("1", "فيديو رحلة", "video", 1, true),
        MediaEntry("2", "Music.mp3", "audio", 2)
    )
    @Test fun combinesSearchTypeAndFavorites() {
        assertEquals(listOf("1"), filterMedia(items, " رحلة ", "video", true).map { it.uri })
        assertEquals(emptyList<MediaEntry>(), filterMedia(items, "music", "audio", true))
        assertEquals(listOf("2"), filterMedia(items, "MUSIC", "all", false).map { it.uri })
    }
}
