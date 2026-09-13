package com.malkbaty.safeplayer.data.dao

import androidx.room.*
import com.malkbaty.safeplayer.data.entity.Source

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY added_at DESC")
    suspend fun getAll(): List<Source>

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): Source?

    @Query("SELECT * FROM sources WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): Source?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: Source): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Source>)

    @Update
    suspend fun update(source: Source)

    @Delete
    suspend fun delete(source: Source)

    @Query("UPDATE sources SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE sources SET discovered_count = :count, last_scanned_at = :scannedAt WHERE id = :id")
    suspend fun updateScanInfo(id: Long, count: Int, scannedAt: Long)
}
