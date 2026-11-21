package com.malkbaty.safeplayer

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleMimeTest {
    @Test fun acceptsOnlySupportedExtensionsIgnoringCase() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMime("ترجمة.SRT"))
        assertEquals(MimeTypes.TEXT_VTT, subtitleMime("caption.vtt"))
        assertNull(subtitleMime("caption.ass"))
        assertNull(subtitleMime("srt"))
    }
}
