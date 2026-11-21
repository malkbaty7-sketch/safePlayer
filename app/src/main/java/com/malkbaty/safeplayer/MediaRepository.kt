package com.malkbaty.safeplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Shared access to the existing audio and video library; does not delete original files. */
class MediaRepository(private val context: Context, private val dao: MediaDao) {
    val media: Flow<List<MediaEntry>> = dao.observeAll()
    val scanner = MediaScanner(context, dao)

    data class ImportResult(val added: Int, val failed: Int)

    suspend fun importDocuments(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var failed = 0
        uris.forEach { uri ->
            try {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri).orEmpty()
                val kind = when {
                    mime.startsWith("video/") -> "video"
                    mime.startsWith("audio/") -> "audio"
                    else -> null
                }
                if (kind == null) { failed++; return@forEach }
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                var size: Long? = null
                val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) size = cursor.getLong(index).takeIf { it >= 0 }
                        val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (name >= 0) cursor.getString(name) else null
                    } else null
                } ?: uri.lastPathSegment ?: "ملف وسائط"
                val duration = try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                    } finally { retriever.release() }
                } catch (_: Exception) { null }
                dao.mergeScanned(listOf(MediaEntry(uri.toString(), title, kind, System.currentTimeMillis(), durationMs = duration, sizeBytes = size)))
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }
        if (uris.isNotEmpty()) ThumbnailLoader.clearIfCreated()
        ImportResult(added, failed)
    }

    suspend fun markPlayed(uri: String, time: Long) = dao.markPlayed(uri, time)
    suspend fun setFavorite(uri: String, favorite: Boolean) = dao.setFavorite(uri, favorite)
    suspend fun setWatchLater(uri: String, value: Boolean) = dao.setWatchLater(uri, value)
    suspend fun savePosition(uri: String, position: Long) = dao.savePosition(uri, position.coerceAtLeast(0))
    suspend fun remove(uri: String) {
        dao.delete(uri)
        ThumbnailLoader.clearIfCreated()
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // MediaStore entries have no persisted document grant.
        }
    }
}

enum class MediaSort(val label: String) { NEWEST("الأحدث"), OLDEST("الأقدم"), NAME("الاسم"), DURATION("المدة"), SIZE("الحجم"), LAST_PLAYED("آخر تشغيل") }

data class LibraryQuery(val text: String = "", val type: String = "all", val favoritesOnly: Boolean = false, val sort: MediaSort = MediaSort.NEWEST, val watchLaterOnly: Boolean = false, val historyOnly: Boolean = false)

/** Search works on titles, not content: it cannot classify or assess video safety. */
fun searchLibrary(items: List<MediaEntry>, query: LibraryQuery): List<MediaEntry> {
    val filtered = items.filter { it.title.contains(query.text.trim(), ignoreCase = true) &&
        (query.type == "all" || it.type == query.type) && (!query.favoritesOnly || it.favorite) && (!query.watchLaterOnly || it.watchLater) && (!query.historyOnly || it.lastPlayedAt > 0) }
    return when (query.sort) {
        MediaSort.NEWEST -> filtered.sortedWith(compareByDescending<MediaEntry> { it.addedAt }.thenBy { it.uri })
        MediaSort.OLDEST -> filtered.sortedWith(compareBy<MediaEntry> { it.addedAt }.thenBy { it.uri })
        MediaSort.NAME -> filtered.sortedWith(compareBy<MediaEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.uri })
        MediaSort.DURATION -> filtered.sortedWith(compareBy<MediaEntry> { it.durationMs == null }.thenBy { it.durationMs }.thenBy { it.uri })
        MediaSort.LAST_PLAYED -> filtered.sortedWith(compareByDescending<MediaEntry> { it.lastPlayedAt }.thenBy { it.uri })
        MediaSort.SIZE -> filtered.sortedWith(compareBy<MediaEntry> { it.sizeBytes == null }.thenBy { it.sizeBytes }.thenBy { it.uri })
    }
}

/** History uses last actual playback time, not the largest resume offset. */
fun lastWatched(items: List<MediaEntry>): MediaEntry? = items
    .filter { it.positionMs > 0 && it.lastPlayedAt > 0 }
    .maxWithOrNull(compareBy<MediaEntry> { it.lastPlayedAt }.thenBy { it.uri })

/** Compact home shortcuts, independent of the current search and sort. */
data class LibraryHighlights(val recent: List<MediaEntry>, val favorites: List<MediaEntry>)
fun libraryHighlights(items: List<MediaEntry>, limit: Int = 5): LibraryHighlights {
    require(limit >= 0)
    return LibraryHighlights(
        items.sortedWith(compareByDescending<MediaEntry> { it.addedAt }.thenBy { it.uri }).take(limit),
        items.filter { it.favorite }.sortedWith(compareByDescending<MediaEntry> { it.addedAt }.thenBy { it.uri }).take(limit)
    )
}
