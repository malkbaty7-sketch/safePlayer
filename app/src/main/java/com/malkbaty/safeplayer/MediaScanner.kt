package com.malkbaty.safeplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Scans only sources the user granted. It never deletes existing rows on partial results. */
class MediaScanner(private val context: Context, private val dao: MediaDao) {
    data class Access(val video: Boolean, val audio: Boolean, val limitedVideo: Boolean)
    data class Result(val found: Int, val added: Int)

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(): Access {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT < 33) {
            val read = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            return Access(read, read, false)
        }
        val fullVideo = granted(Manifest.permission.READ_MEDIA_VIDEO)
        val selected = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return Access(fullVideo || selected, granted(Manifest.permission.READ_MEDIA_AUDIO), !fullVideo && selected)
    }

    class FullAccessRequired : Exception()

    /** Read-only comparison. Missing results can also mean an unavailable volume, not deletion. */
    suspend fun findUnavailable(entries: List<MediaEntry>): List<MediaEntry> = withContext(Dispatchers.IO) {
        fun requireFullAccess() {
            val access = access()
            if (!access.video || !access.audio || access.limitedVideo) throw FullAccessRequired()
        }
        requireFullAccess()
        val visible = HashSet<String>()
        for (collection in listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)) {
            val cursor = context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?: throw IllegalStateException("MediaStore query returned no cursor")
            cursor.use {
                val id = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    visible.add(ContentUris.withAppendedId(collection, it.getLong(id)).toString())
                }
            }
        }
        requireFullAccess()
        unavailableMediaStoreEntries(entries, visible)
    }

    suspend fun scan(access: Access): Result = withContext(Dispatchers.IO) {
        var found = 0
        var added = 0
        suspend fun scanType(type: String) {
            val collection = if (type == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, "duration")
            val cursor = context.contentResolver.query(collection, columns, null, null, null)
                ?: throw IllegalStateException("MediaStore query returned no cursor")
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val durationIndex = it.getColumnIndexOrThrow("duration")
                val batch = ArrayList<MediaEntry>(100)
                suspend fun flush() {
                    if (batch.isNotEmpty()) {
                        added += dao.mergeScanned(batch)
                        batch.clear()
                    }
                }
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = it.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val name = it.getString(nameIndex)?.takeIf(String::isNotBlank) ?: "ملف وسائط"
                    val addedAt = it.getLong(dateIndex).coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE / 1000) * 1000
                    val size = if (it.isNull(sizeIndex)) null else it.getLong(sizeIndex).takeIf { value -> value >= 0 }
                    val duration = if (it.isNull(durationIndex)) null else it.getLong(durationIndex).takeIf { value -> value > 0 }
                    batch.add(MediaEntry(uri, name, type, addedAt, durationMs = duration, sizeBytes = size))
                    found++
                    if (batch.size == 100) flush()
                }
                flush()
            }
        }
        if (access.video) scanType("video")
        if (access.audio) scanType("audio")
        ThumbnailLoader.clearIfCreated()
        Result(found, added)
    }
}

/** Select only canonical external MediaStore rows; never infer absence of SAF documents. */
fun unavailableMediaStoreEntries(entries: List<MediaEntry>, visibleUris: Set<String>): List<MediaEntry> {
    val video = Regex("content://media/external/video/media/[0-9]+")
    val audio = Regex("content://media/external/audio/media/[0-9]+")
    return entries.filter { entry ->
        val canonical = when (entry.type) {
            "video" -> video.matches(entry.uri)
            "audio" -> audio.matches(entry.uri)
            else -> false
        }
        canonical && entry.uri !in visibleUris
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}
