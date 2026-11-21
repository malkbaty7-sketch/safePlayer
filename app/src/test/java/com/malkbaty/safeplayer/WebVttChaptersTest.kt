package com.malkbaty.safeplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebVttChaptersTest {
    private fun parse(text: String) = parseWebVttChapters(text.toByteArray(Charsets.UTF_8))

    @Test fun readsHeaderIdentifiersNotesAndSorts() {
        val result = parse("\uFEFFWEBVTT\r\n\r\nNOTE this is ignored\r\nnote text\r\n\r\nsecond\r\n00:20.000 --> 00:30.000\r\nالفصل الثاني\r\n\r\n00:00:01.250 --> 00:00:10.000 align:start\r\nمقدمة\r\nمتابعة\r\n")
        assertEquals(listOf(1250L, 20000L), result.map { it.startMs })
        assertEquals(listOf("مقدمة متابعة", "الفصل الثاني"), result.map { it.title })
    }

    @Test fun rejectsMissingHeaderAndMalformedCues() {
        assertThrows(IllegalArgumentException::class.java) { parse("hello\n\n00:00.000 --> 00:01.000\nTitle") }
        assertThrows(IllegalArgumentException::class.java) { parse("WEBVTT\n\n00:60.000 --> 01:00.000\nTitle") }
        assertThrows(IllegalArgumentException::class.java) { parse("WEBVTT\n\n00:10.000 --> 00:01.000\nTitle") }
        assertThrows(IllegalArgumentException::class.java) { parse("WEBVTT\n\n00:00.000 --> 00:01.000\n") }
    }

    @Test fun rejectsOversizedAndEmptyFiles() {
        assertThrows(IllegalArgumentException::class.java) { parseWebVttChapters(ByteArray(MAX_CHAPTER_FILE_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { parse("WEBVTT\n\n") }
    }
}
