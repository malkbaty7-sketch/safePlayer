package com.malkbaty.safeplayer.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class Source(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long? = null,
    @ColumnInfo(name = "status") val status: String = "ACTIVE",
    @ColumnInfo(name = "discovered_count") val discoveredCount: Int = 0
)
