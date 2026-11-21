package com.malkbaty.safeplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
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
                val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "ملف وسائط"
                dao.insert(MediaEntry(uri.toString(), title, kind, System.currentTimeMillis()))
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }
        ImportResult(added, failed)
    }

    suspend fun setFavorite(uri: String, favorite: Boolean) = dao.setFavorite(uri, favorite)
    suspend fun savePosition(uri: String, position: Long) = dao.savePosition(uri, position.coerceAtLeast(0))
    suspend fun remove(uri: String) {
        dao.delete(uri)
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // MediaStore entries have no persisted document grant.
        }
    }
}

enum class MediaSort(val label: String) { NEWEST("الأحدث"), OLDEST("الأقدم"), NAME("الاسم") }

data class LibraryQuery(val text: String = "", val type: String = "all", val favoritesOnly: Boolean = false, val sort: MediaSort = MediaSort.NEWEST)

/** Search works on titles, not content: it cannot classify or assess video safety. */
fun searchLibrary(items: List<MediaEntry>, query: LibraryQuery): List<MediaEntry> {
    val filtered = items.filter { it.title.contains(query.text.trim(), ignoreCase = true) &&
        (query.type == "all" || it.type == query.type) && (!query.favoritesOnly || it.favorite) }
    return when (query.sort) {
        MediaSort.NEWEST -> filtered.sortedWith(compareByDescending<MediaEntry> { it.addedAt }.thenBy { it.uri })
        MediaSort.OLDEST -> filtered.sortedWith(compareBy<MediaEntry> { it.addedAt }.thenBy { it.uri })
        MediaSort.NAME -> filtered.sortedWith(compareBy<MediaEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.uri })
    }
}
