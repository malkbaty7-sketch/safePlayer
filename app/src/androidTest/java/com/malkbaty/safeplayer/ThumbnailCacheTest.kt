package com.malkbaty.safeplayer

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailCacheTest {
    @Test fun evictsLeastRecentlyUsedWithoutRecyclingDisplayedBitmap() {
        val first = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val cache = ThumbnailCache(first.allocationByteCount + 1)
        val key1 = ThumbnailKey("first", 1, 1)
        val key2 = ThumbnailKey("second", 2, 2)
        cache.put(key1, first)
        cache.put(key2, second)
        assertNull(cache.get(key1))
        assertNotNull(cache.get(key2))
        assertEquals(false, first.isRecycled)
        cache.evictAll()
        assertNull(cache.get(key2))
        first.recycle(); second.recycle()
    }
}
