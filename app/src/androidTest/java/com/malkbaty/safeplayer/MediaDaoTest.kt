package com.malkbaty.safeplayer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDaoTest {
    private lateinit var db: MediaDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MediaDatabase::class.java).build()
    }
    @After fun close() = db.close()
    @Test fun rescanDoesNotOverwriteFavoriteOrResumePosition() = runBlocking {
        val dao = db.mediaDao()
        dao.insert(MediaEntry("content://video/1", "old", "video", 10, favorite = true, positionMs = 5000))
        val result = dao.insertAll(listOf(MediaEntry("content://video/1", "new", "video", 20), MediaEntry("content://video/2", "second", "video", 30)))
        assertEquals(-1L, result[0])
        val entries = dao.observeAll().first().associateBy { it.uri }
        assertEquals(true, entries.getValue("content://video/1").favorite)
        assertEquals(5000L, entries.getValue("content://video/1").positionMs)
        assertEquals("old", entries.getValue("content://video/1").title)
        assertEquals(2, entries.size)
    }
}
