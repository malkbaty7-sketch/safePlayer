package com.malkbaty.safeplayer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaMigrationTest {
    @Test fun migrationPreservesExistingLibrary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS media (uri TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, type TEXT NOT NULL, addedAt INTEGER NOT NULL, favorite INTEGER NOT NULL, positionMs INTEGER NOT NULL)")
                db.execSQL("INSERT INTO media VALUES ('content://a', 'clip', 'video', 123, 1, 8000)")
                db.version = 1
            }
            val db = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.MIGRATION_1_2, MediaDatabase.MIGRATION_2_3).build()
            try {
                val item = db.mediaDao().observeAll().first().single()
                assertEquals(true, item.favorite)
                assertEquals(8000L, item.positionMs)
                assertEquals(0L, item.lastPlayedAt)
                assertNull(item.durationMs)
                assertEquals(false, item.watchLater)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun migrationFromV2PreservesHistoryAndMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2-test.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS media (uri TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, type TEXT NOT NULL, addedAt INTEGER NOT NULL, favorite INTEGER NOT NULL, positionMs INTEGER NOT NULL, lastPlayedAt INTEGER NOT NULL DEFAULT 0, durationMs INTEGER, sizeBytes INTEGER)")
                db.execSQL("INSERT INTO media VALUES ('content://b', 'clip', 'video', 123, 1, 8000, 10000, 90000, 12345)")
                db.version = 2
            }
            val db = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.MIGRATION_2_3).build()
            try {
                val item = db.mediaDao().observeAll().first().single()
                assertEquals(true, item.favorite)
                assertEquals(8000L, item.positionMs)
                assertEquals(10000L, item.lastPlayedAt)
                assertEquals(90000L, item.durationMs)
                assertEquals(12345L, item.sizeBytes)
                assertEquals(false, item.watchLater)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
