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
    @Test fun sortsNumericMetadataAndPlacesUnknownLast() {
        val entries = listOf(MediaEntry("a", "a", "video", 0, durationMs = 1000, sizeBytes = 100),
            MediaEntry("b", "b", "video", 0, durationMs = 10000, sizeBytes = 2), MediaEntry("c", "c", "video", 0))
        assertEquals(listOf("a", "b", "c"), searchLibrary(entries, LibraryQuery(sort = MediaSort.DURATION)).map { it.uri })
        assertEquals(listOf("b", "a", "c"), searchLibrary(entries, LibraryQuery(sort = MediaSort.SIZE)).map { it.uri })
    }
    @Test fun resumeUsesLastPlayedTimestampNotPosition() {
        val entries = listOf(MediaEntry("a", "a", "video", 0, positionMs = 900000, lastPlayedAt = 10),
            MediaEntry("b", "b", "audio", 0, positionMs = 1000, lastPlayedAt = 20),
            MediaEntry("c", "c", "video", 0, positionMs = 0, lastPlayedAt = 30))
        assertEquals("b", lastWatched(entries)?.uri)
        assertEquals(null, lastWatched(emptyList()))
    }
    @Test fun homeHighlightsAreLimitedAndOrderedIndependentlyOfFilters() {
        val highlights = libraryHighlights(items, 2)
        assertEquals(listOf("3", "2"), highlights.recent.map { it.uri })
        assertEquals(listOf("3", "1"), highlights.favorites.map { it.uri })
        assertEquals(emptyList<MediaEntry>(), libraryHighlights(items, 0).recent)
    }
    @Test fun historyAndWatchLaterFiltersCombineAndHistorySortsByPlayback() {
        val entries = listOf(
            MediaEntry("a", "A", "video", 100, lastPlayedAt = 20, watchLater = true),
            MediaEntry("b", "B", "audio", 200, lastPlayedAt = 40, watchLater = true),
            MediaEntry("c", "C", "video", 300, watchLater = true),
            MediaEntry("d", "D", "video", 400, lastPlayedAt = 50)
        )
        assertEquals(listOf("b", "a"), searchLibrary(entries, LibraryQuery(sort = MediaSort.LAST_PLAYED, watchLaterOnly = true, historyOnly = true)).map { it.uri })
        assertEquals(listOf("a"), searchLibrary(entries, LibraryQuery(type = "video", watchLaterOnly = true, historyOnly = true)).map { it.uri })
    }
    @Test fun formatsLongPlaybackPositions() {
        assertEquals("01:01", formatPlaybackTime(61_000))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000))
    }
}
