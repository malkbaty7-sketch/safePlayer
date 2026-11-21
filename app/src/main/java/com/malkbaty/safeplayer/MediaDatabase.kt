package com.malkbaty.safeplayer

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.ColumnInfo
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media")
data class MediaEntry(
    @PrimaryKey val uri: String,
    val title: String,
    val type: String,
    val addedAt: Long,
    val favorite: Boolean = false,
    val positionMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastPlayedAt: Long = 0,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null
)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<MediaEntry>>

    // IGNORE preserves favorites and playback position when a document is selected again.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: MediaEntry)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<MediaEntry>): List<Long>

    @Query("UPDATE media SET durationMs = COALESCE(:duration, durationMs), sizeBytes = COALESCE(:size, sizeBytes) WHERE uri = :uri")
    suspend fun updateMetadata(uri: String, duration: Long?, size: Long?)

    @Transaction
    suspend fun mergeScanned(entries: List<MediaEntry>): Int {
        val inserted = insertAll(entries).count { it != -1L }
        entries.forEach { updateMetadata(it.uri, it.durationMs, it.sizeBytes) }
        return inserted
    }

    @Query("UPDATE media SET lastPlayedAt = MAX(lastPlayedAt, :time) WHERE uri = :uri")
    suspend fun markPlayed(uri: String, time: Long)

    @Query("UPDATE media SET favorite = :favorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, favorite: Boolean)

    @Query("UPDATE media SET positionMs = :position WHERE uri = :uri")
    suspend fun savePosition(uri: String, position: Long)

    @Query("DELETE FROM media WHERE uri = :uri")
    suspend fun delete(uri: String)
}

@Database(entities = [MediaEntry::class], version = 2, exportSchema = false)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media ADD COLUMN durationMs INTEGER")
                db.execSQL("ALTER TABLE media ADD COLUMN sizeBytes INTEGER")
            }
        }
        @Volatile private var instance: MediaDatabase? = null
        fun get(context: Context): MediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, "safeplayer.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

