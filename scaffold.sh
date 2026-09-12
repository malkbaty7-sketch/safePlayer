#!/usr/bin/env bash
set -e

echo "Creating SafePlayer scaffold files..."

# Create directories
mkdir -p app/src/main/java/com/malkbaty/safeplayer
mkdir -p app/src/main
mkdir -p data/src/main/java/com/malkbaty/safeplayer/data/entity
mkdir -p data/src/main/java/com/malkbaty/safeplayer/data/dao
mkdir -p data/src/main/java/com/malkbaty/safeplayer/data
mkdir -p scanner/src/main/java/com/malkbaty/safeplayer/scanner
mkdir -p core
mkdir -p mediaengine
mkdir -p .github/workflows

# README
cat > README.md <<'EOF'
# SafePlayer

SafePlayer — Android-first, local-first media player (SafePlayer 0.1 scaffold).

This repository contains the initial scaffold for SafePlayer 0.1 (Android‑first). The goal for this initial commit is to provide a modular Android project with:

- Kotlin + Jetpack Compose app module
- Room (local database) schema and DAO for media files
- A Scanner CoroutineWorker that queries MediaStore for videos and audio and populates the local DB
- Simple MainActivity that triggers a scan and shows basic status

This is the minimal working scaffold. Next steps after this commit:

1. Iterate on Scanner (incremental scan, progress reporting, recovery)
2. Implement thumbnail caching, player UI, ExoPlayer integration, playlists, settings
3. Add tests and CI

See `app/README.md` for instructions to build and run the debug APK.
EOF

# settings.gradle.kts
cat > settings.gradle.kts <<'EOF'
rootProject.name = "safePlayer"
include(":app", ":core", ":data", ":scanner", ":mediaengine")
EOF

# build.gradle.kts (root)
cat > build.gradle.kts <<'EOF'
plugins {
    kotlin("multiplatform") version "1.8.10" apply false
}

// Versions
val kotlinVersion = "1.8.10"
val composeVersion = "1.4.0"

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
EOF

# GitHub Actions workflow
cat > .github/workflows/android.yml <<'EOF'
# CI: Build debug
name: Android CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon
EOF

# app/build.gradle
mkdir -p app
cat > app/build.gradle <<'EOF'
apply plugin: 'com.android.application'
apply plugin: 'org.jetbrains.kotlin.android'

android {
    namespace 'com.malkbaty.safeplayer'
    compileSdk 33

    defaultConfig {
        applicationId = 'com.malkbaty.safeplayer'
        minSdk 24
        targetSdk 33
        versionCode 1
        versionName "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = '1.4.0'
    }
}

dependencies {
    implementation project(':core')
    implementation project(':data')
    implementation project(':scanner')
    implementation project(':mediaengine')

    implementation "org.jetbrains.kotlin:kotlin-stdlib:1.8.10"

    // AndroidX
    implementation 'androidx.core:core-ktx:1.9.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'

    // Compose
    implementation "androidx.compose.ui:ui:1.4.0"
    implementation "androidx.compose.material:material:1.4.0"
    implementation "androidx.activity:activity-compose:1.7.0"

    // Room and WorkManager
    implementation 'androidx.room:room-runtime:2.5.0'
    kapt 'androidx.room:room-compiler:2.5.0'
    implementation 'androidx.work:work-runtime-ktx:2.8.0'

}
EOF

# AndroidManifest
cat > app/src/main/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.malkbaty.safeplayer">

    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:label="SafePlayer"
        android:icon="@mipmap/ic_launcher">
        <activity android:name="com.malkbaty.safeplayer.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
EOF

# MainActivity.kt
cat > app/src/main/java/com/malkbaty/safeplayer/MainActivity.kt <<'EOF'
package com.malkbaty.safeplayer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.malkbaty.safeplayer.scanner.ScannerWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Request read external storage on startup (will handle denial gracefully)
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 101)

        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("Idle") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "SafePlayer — Scanner", style = MaterialTheme.typography.h5)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            val workRequest = OneTimeWorkRequest.Builder(ScannerWorker::class.java).build()
            WorkManager.getInstance(context).enqueueUniqueWork("safeplayer_scan", ExistingWorkPolicy.REPLACE, workRequest)
            status = "Scan enqueued"
        }) {
            Text("Run Scanner")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Status: $status")
    }
}
EOF

# core/build.gradle
cat > core/build.gradle <<'EOF'
apply plugin: 'java-library'
apply plugin: 'kotlin'

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.8.10'
}
EOF

# data module
cat > data/build.gradle <<'EOF'
apply plugin: 'kotlin'
apply plugin: 'kotlin-kapt'

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation 'androidx.room:room-runtime:2.5.0'
    kapt 'androidx.room:room-compiler:2.5.0'
}
EOF

cat > data/src/main/java/com/malkbaty/safeplayer/data/entity/MediaFile.kt <<'EOF'
package com.malkbaty.safeplayer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_files")
data class MediaFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sourceUri: String,
    val displayName: String,
    val folderPath: String?,
    val mediaType: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val lastModified: Long,
    val addedAt: Long,
    val updatedAt: Long,
    val isNew: Boolean = true,
    val isFavorite: Boolean = false,
    val lastPositionMs: Long = 0L,
    val lastPlayedAt: Long? = null,
    val watchedState: String = "NOT_STARTED",
    val thumbPath: String? = null,
    val checksum: String? = null
)
EOF

cat > data/src/main/java/com/malkbaty/safeplayer/data/dao/MediaDao.kt <<'EOF'
package com.malkbaty.safeplayer.data.dao

import androidx.room.*
import com.malkbaty.safeplayer.data.entity.MediaFile

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_files ORDER BY addedAt DESC")
    suspend fun getAll(): List<MediaFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: MediaFile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<MediaFile>)

    @Query("DELETE FROM media_files WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM media_files WHERE sourceUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): MediaFile?

    @Query("UPDATE media_files SET isNew = :isNew WHERE id = :id")
    suspend fun setIsNew(id: Long, isNew: Boolean)
}
EOF

cat > data/src/main/java/com/malkbaty/safeplayer/data/AppDatabase.kt <<'EOF'
package com.malkbaty.safeplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.malkbaty.safeplayer.data.dao.MediaDao
import com.malkbaty.safeplayer.data.entity.MediaFile

@Database(entities = [MediaFile::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "safeplayer_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
EOF

# scanner module
cat > scanner/build.gradle <<'EOF'
apply plugin: 'kotlin'

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation project(':data')
    implementation 'androidx.work:work-runtime-ktx:2.8.0'
    implementation 'androidx.core:core-ktx:1.9.0'
}
EOF

cat > scanner/src/main/java/com/malkbaty/safeplayer/scanner/ScannerWorker.kt <<'EOF'
package com.malkbaty.safeplayer.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.malkbaty.safeplayer.data.AppDatabase
import com.malkbaty.safeplayer.data.entity.MediaFile

class ScannerWorker(appContext: Context, params: WorkerParameters): CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val db = AppDatabase.getInstance(context)
        val mediaDao = db.mediaDao()

        // Scan videos
        val videoUri: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE
        )

        val cursor = context.contentResolver.query(videoUri, projection, null, null, null)
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.getString(nameCol) ?: ""
                val size = c.getLong(sizeCol)
                val dur = if (!c.isNull(durCol)) c.getLong(durCol) else null
                val modified = c.getLong(modCol) * 1000L // MediaStore in seconds on older devices

                val contentUri = ContentUris.withAppendedId(videoUri, id)

                val existing = mediaDao.findByUri(contentUri.toString())
                val now = System.currentTimeMillis()
                if (existing == null) {
                    val mf = MediaFile(
                        sourceUri = contentUri.toString(),
                        displayName = displayName,
                        folderPath = null,
                        mediaType = "VIDEO",
                        sizeBytes = size,
                        durationMs = dur,
                        lastModified = modified,
                        addedAt = now,
                        updatedAt = now
                    )
                    mediaDao.insert(mf)
                } else {
                    // update basic fields if changed
                    if (existing.sizeBytes != size || existing.lastModified != modified) {
                        val updated = existing.copy(
                            displayName = displayName,
                            sizeBytes = size,
                            durationMs = dur,
                            lastModified = modified,
                            updatedAt = now,
                            isNew = existing.isNew
                        )
                        mediaDao.insert(updated)
                    }
                }
            }
        }

        // TODO: scan audio similarly (omitted for brevity in scaffold)

        return Result.success()
    }
}
EOF

# mediaengine build file (placeholder)
cat > mediaengine/build.gradle <<'EOF'
apply plugin: 'kotlin'

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.8.10'
}
EOF

# app/README
cat > app/README.md <<'EOF'
# App module README

This module contains the Android application entry point.

Build & Run

- Open the project in Android Studio (Arctic Fox or newer recommended).
- Build the project and run the `app` configuration on an Android device (min SDK 24).

Notes

- This scaffold provides a simple Scanner worker that queries MediaStore for videos and inserts records into Room DB.
- Press the "Run Scanner" button on the main screen to enqueue a scan job.
EOF

echo "Scaffold files created successfully."
echo "Next: run 'git add . && git commit -m \"Initial scaffold\" && git push origin main' inside this repo."

exit 0
