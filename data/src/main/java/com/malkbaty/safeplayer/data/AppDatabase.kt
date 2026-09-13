package com.malkbaty.safeplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.malkbaty.safeplayer.data.dao.MediaDao
import com.malkbaty.safeplayer.data.dao.SourceDao
import com.malkbaty.safeplayer.data.entity.MediaFile
import com.malkbaty.safeplayer.data.entity.Source

@Database(entities = [MediaFile::class, Source::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun sourceDao(): SourceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "safeplayer_db"
                )
                    // For now, allow destructive migrations during rapid iteration on feature branch.
                    // In production we will add proper Migration objects.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
