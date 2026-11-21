package com.malkbaty.safeplayer

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ChaptersTest {
    @Test fun extractsSortsAndDeduplicatesValidId3Chapters() {
        fun frame(id: String, start: Int, title: String) = ChapterFrame(id, start, start + 1000, -1, -1,
            arrayOf<Id3Frame>(TextInformationFrame("TIT2", null, title)))
        val second = frame("2", 2000, "الثاني")
        val result = id3Chapters(Metadata(second, frame("1", 1000, " الأول "), second, frame("bad", -1, "bad")))
        assertEquals(listOf(PlaybackChapter("1", "الأول", 1000), PlaybackChapter("2", "الثاني", 2000)), result)
    }
    @Test fun usesIdentifierWhenTitleMissing() {
        val frame = ChapterFrame("intro", 0, 1000, -1, -1, emptyArray<Id3Frame>())
        assertEquals(listOf(PlaybackChapter("intro", "intro", 0)), id3Chapters(Metadata(frame)))
    }
}
