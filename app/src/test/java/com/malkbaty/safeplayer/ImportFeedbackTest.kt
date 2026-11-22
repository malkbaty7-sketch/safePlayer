package com.malkbaty.safeplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportFeedbackTest {
    @Test fun successNamesTheCount() {
        assertEquals("تمت إضافة 1 ملف إلى المكتبة.", importResultMessage(1, 0))
        assertEquals("تمت إضافة 3 ملفات إلى المكتبة.", importResultMessage(3, 0))
    }

    @Test fun partialFailureMentionsRetry() {
        val text = importResultMessage(2, 1)
        assertEquals(true, text.contains("أُضيف 2"))
        assertEquals(true, text.contains("تعذرت إضافة 1"))
        assertEquals(true, text.contains("إعادة المحاولة"))
    }

    @Test fun totalFailureAndEmptySelectionAreDistinct() {
        assertEquals(true, importResultMessage(0, 2).contains("تحقق من الوصول"))
        assertEquals("لم يتم اختيار ملفات للإضافة.", importResultMessage(0, 0))
    }
}
