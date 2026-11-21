package com.malkbaty.safeplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFilterTest {
    private val items = listOf(
        MediaEntry("1", "فيديو رحلة", "video", 1, true),
        MediaEntry("2", "Music.mp3", "audio", 2),
        MediaEntry("3", "album.mp3", "audio", 3, true)
    )
    @Test fun combinesSearchTypeAndFavorites() {
        assertEquals(listOf("1"), searchLibrary(items, LibraryQuery(" رحلة ", "video", true)).map { it.uri })
        assertEquals(emptyList<MediaEntry>(), searchLibrary(items, LibraryQuery("music", "audio", true)))
        assertEquals(listOf("2"), searchLibrary(items, LibraryQuery("MUSIC")).map { it.uri })
    }
    @Test fun sortsWithoutMutatingSource() {
        assertEquals(listOf("3", "2", "1"), searchLibrary(items, LibraryQuery()).map { it.uri })
        assertEquals(listOf("1", "2", "3"), searchLibrary(items, LibraryQuery(sort = MediaSort.OLDEST)).map { it.uri })
        assertEquals(listOf("3", "2", "1"), searchLibrary(items, LibraryQuery(sort = MediaSort.NAME)).map { it.uri })
        assertEquals(listOf("1", "2", "3"), items.map { it.uri })
    }
    @Test fun sortTieUsesStableUri() {
        val tied = listOf(MediaEntry("b", "same", "audio", 0), MediaEntry("a", "Same", "video", 0))
        assertEquals(listOf("a", "b"), searchLibrary(tied, LibraryQuery(sort = MediaSort.NAME)).map { it.uri })
    }
}
