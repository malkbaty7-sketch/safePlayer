package com.malkbaty.safeplayer

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
    val positionMs: Long = 0
)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<MediaEntry>>

    // IGNORE preserves favorites and playback position when a document is selected again.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: MediaEntry)

    @Query("UPDATE media SET favorite = :favorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, favorite: Boolean)

    @Query("UPDATE media SET positionMs = :position WHERE uri = :uri")
    suspend fun savePosition(uri: String, position: Long)

    @Query("DELETE FROM media WHERE uri = :uri")
    suspend fun delete(uri: String)
}

@Database(entities = [MediaEntry::class], version = 1, exportSchema = false)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
}

fun filterMedia(items: List<MediaEntry>, query: String, type: String, favoritesOnly: Boolean): List<MediaEntry> =
    items.filter { it.title.contains(query.trim(), ignoreCase = true) &&
        (type == "all" || it.type == type) && (!favoritesOnly || it.favorite) }
