package com.malkbaty.safeplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkStreamTest {
    @Test fun validatesHttpsAndRejectsCredentialsOrFragments() {
        assertEquals("https://example.com/a.m3u8?token=abc", validateStreamUrl("  https://example.com/a.m3u8?token=abc  "))
        listOf("", "http://example.com/a", "file:///sdcard/a", "https://user:pass@example.com/a", "https://example.com/a#foo", "https://example.com:0/a", "https://example.com:99999/a", "https://bad host/a").forEach { assertNull(validateStreamUrl(it)) }
    }
    @Test fun detectsPlaylistFormatsIgnoringCaseAndQuery() {
        assertEquals("application/x-mpegURL", streamMimeType("https://example.com/PLAY.M3U8?foo=bar"))
        assertEquals("application/dash+xml", streamMimeType("https://example.com/a.mpd?token=1"))
        assertNull(streamMimeType("https://example.com/a.mp4?name=x.m3u8"))
    }
}
