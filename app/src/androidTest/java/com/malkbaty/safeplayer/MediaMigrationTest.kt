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
            val db = Room.databaseBuilder(context, MediaDatabase::class.java, name).addMigrations(MediaDatabase.MIGRATION_1_2).build()
            try {
                val item = db.mediaDao().observeAll().first().single()
                assertEquals(true, item.favorite)
                assertEquals(8000L, item.positionMs)
                assertEquals(0L, item.lastPlayedAt)
                assertNull(item.durationMs)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
