package com.malkbaty.safeplayer

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, "safeplayer-incremental-test-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SafePlayerTests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("failed to create media")
        val canonicalUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(uri)).toString()
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.context.assets.open("chapters.m4v").use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) } ?: error("cannot write test media")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val scanner = MediaScanner(context, db.mediaDao())
            val access = scanner.access()
            assertTrue(access.video && access.audio && !access.limitedVideo)
            val first = scanner.scan(access, incremental = true)
            assertTrue(!first.incremental)
            val row = db.mediaDao().observeAll().first().first { it.uri == canonicalUri }
            db.mediaDao().setFavorite(row.uri, true)
            db.mediaDao().savePosition(row.uri, 1234)
            val renamed = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "safeplayer-renamed-${System.nanoTime()}.mp4") }
            assertEquals(1, resolver.update(uri, renamed, null, null))
            val second = scanner.scan(access, incremental = true)
            assertTrue(second.incremental)
            assertTrue(second.found >= 1)
            val after = db.mediaDao().observeAll().first().first { it.uri == canonicalUri }
            assertTrue(after.favorite)
            assertEquals(1234L, after.positionMs)
        } finally {
            try { resolver.delete(uri, null, null) } catch (_: SecurityException) { }
            prefs.edit().clear().commit()
            db.close()
        }
    }
}
