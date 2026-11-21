package com.malkbaty.safeplayer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncrementalScanTest {
    @Test fun periodicRescanFindsChangedMediaAndPreservesUserState() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_AUDIO)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
        val prefs = context.getSharedPreferences("media_scan_generations", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "safeplayer-incremental-test-${System.nanoTime()}.mp3")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/SafePlayerTests")
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("failed to create media")
        try {
            val scanner = MediaScanner(context, db.mediaDao())
            val access = scanner.access()
            assertTrue(access.video && access.audio && !access.limitedVideo)
            val first = scanner.scan(access, incremental = true)
            assertTrue(!first.incremental)
            val row = db.mediaDao().observeAll().first().first { it.uri == uri.toString() }
            db.mediaDao().setFavorite(row.uri, true)
            db.mediaDao().savePosition(row.uri, 1234)
            val renamed = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "safeplayer-renamed-${System.nanoTime()}.mp3") }
            assertEquals(1, resolver.update(uri, renamed, null, null))
            val second = scanner.scan(access, incremental = true)
            assertTrue(second.incremental)
            assertTrue(second.found >= 1)
            val after = db.mediaDao().observeAll().first().first { it.uri == uri.toString() }
            assertTrue(after.favorite)
            assertEquals(1234L, after.positionMs)
        } finally {
            resolver.delete(uri, null, null)
            prefs.edit().clear().commit()
            db.close()
        }
    }
}
