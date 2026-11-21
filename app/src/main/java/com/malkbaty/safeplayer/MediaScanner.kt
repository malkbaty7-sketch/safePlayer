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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Scans only sources the user granted. It never deletes existing rows on partial results. */
class MediaScanner(private val context: Context, private val dao: MediaDao) {
    data class Access(val video: Boolean, val audio: Boolean, val limitedVideo: Boolean)
    data class Result(val found: Int, val added: Int, val incremental: Boolean = false)
    companion object { private val scanMutex = Mutex() }
    private val checkpoints = context.getSharedPreferences("media_scan_generations", Context.MODE_PRIVATE)

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

    /** Manual scans always run in full; periodic scans may use per-volume generations. */
    suspend fun scan(access: Access, incremental: Boolean = false): Result = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val actual = access()
            if ((access.video && !actual.video) || (access.audio && !actual.audio)) throw SecurityException("Media permissions changed")
            if (!incremental || Build.VERSION.SDK_INT < 30 || !access.video || !access.audio || access.limitedVideo || actual.limitedVideo) {
                checkpoints.edit().clear().commit()
                scanFull(access)
            } else {
                try { scanGenerations() }
                catch (_: IllegalArgumentException) {
                    // Some providers do not implement generation filters correctly.
                    checkpoints.edit().clear().commit()
                    scanFull(access())
                }
            }.also { ThumbnailLoader.clearIfCreated() }
        }
    }

    private suspend fun scanFull(access: Access): Result {
        var found = 0
        var added = 0
        for (type in listOf("video", "audio")) {
            if ((type == "video" && !access.video) || (type == "audio" && !access.audio)) continue
            val collection = if (type == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val result = queryCollection(type, collection, null, null)
            found += result.found; added += result.added
        }
        return Result(found, added)
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun scanGenerations(): Result {
        // Version/generation are per concrete volume. On unsupported providers, fall back to a full scan.
        val snapshots = try {
            MediaStore.getExternalVolumeNames(context).sorted().map { volume ->
                VolumeSnapshot(volume, MediaStore.getVersion(context, volume), MediaStore.getGeneration(context, volume))
            }.also { if (it.isEmpty() || it.any { snapshot -> snapshot.generation < 0 }) throw IllegalStateException("invalid generation") }
        } catch (e: SecurityException) { throw e }
        catch (_: Exception) {
            checkpoints.edit().clear().commit()
            return scanFull(access())
        }
        var found = 0
        var added = 0
        var usedDelta = false
        for (snapshot in snapshots) {
            val previous = checkpointGeneration(
                checkpoints.getString("${snapshot.name}.version", null),
                checkpoints.getLong("${snapshot.name}.generation", -1),
                snapshot.version, snapshot.generation
            )
            for (type in listOf("video", "audio")) {
                val collection = if (type == "video") MediaStore.Video.Media.getContentUri(snapshot.name) else MediaStore.Audio.Media.getContentUri(snapshot.name)
                val selection = if (previous == null) null else "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} <= ?"
                val args = if (previous == null) null else arrayOf(previous.toString(), snapshot.generation.toString())
                val result = queryCollection(type, collection, selection, args)
                found += result.found; added += result.added
            }
            if (previous != null) usedDelta = true
        }
        val finalAccess = access()
        if (!finalAccess.video || !finalAccess.audio || finalAccess.limitedVideo) {
            checkpoints.edit().clear().commit()
            throw SecurityException("Media permissions changed during scan")
        }
        // Do not advance a checkpoint if volumes or the MediaStore database changed during scanning.
        val volumes = MediaStore.getExternalVolumeNames(context)
        if (volumes != snapshots.map { it.name }.toSet() || snapshots.any { MediaStore.getVersion(context, it.name) != it.version }) {
            checkpoints.edit().clear().commit()
            throw IllegalStateException("MediaStore changed during scan")
        }
        val edit = checkpoints.edit().clear()
        snapshots.forEach { edit.putString("${it.name}.version", it.version).putLong("${it.name}.generation", it.generation) }
        if (!edit.commit()) throw IllegalStateException("could not persist scan checkpoints")
        return Result(found, added, usedDelta)
    }

    private data class VolumeSnapshot(val name: String, val version: String, val generation: Long)

    private suspend fun queryCollection(type: String, collection: android.net.Uri, selection: String?, args: Array<String>?): Result {
        val canonical = if (type == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, "duration")
        val cursor = context.contentResolver.query(collection, columns, selection, args, null)
            ?: throw IllegalStateException("MediaStore query returned no cursor")
        var found = 0
        var added = 0
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durationIndex = it.getColumnIndexOrThrow("duration")
            val batch = ArrayList<MediaEntry>(100)
            suspend fun flush() {
                if (batch.isNotEmpty()) { added += dao.mergeScanned(batch); batch.clear() }
            }
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val uri = ContentUris.withAppendedId(canonical, it.getLong(idIndex)).toString()
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
        return Result(found, added)
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

/** A changed store version or backwards generation invalidates an incremental checkpoint. */
fun checkpointGeneration(storedVersion: String?, storedGeneration: Long, currentVersion: String, currentGeneration: Long): Long? =
    storedGeneration.takeIf { storedVersion == currentVersion && it >= 0 && currentGeneration >= it }
