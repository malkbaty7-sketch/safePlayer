package com.malkbaty.safeplayer

import java.net.URI
import java.util.Locale

/** Only explicit HTTPS streams; never store credentials embedded in a URL. */
fun validateStreamUrl(input: String): String? {
    val value = input.trim()
    if (value.length > 4096) return null
    return try {
        val uri = URI(value)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.port !in -1..65535 || uri.port == 0 || uri.fragment != null) null else value
    } catch (_: Exception) { null }
}

/** Query strings do not affect the media type; other paths use Media3's normal detection. */
fun streamMimeType(input: String): String? = try {
    when {
        URI(input).path.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8") -> "application/x-mpegURL"
        URI(input).path.orEmpty().lowercase(Locale.ROOT).endsWith(".mpd") -> "application/dash+xml"
        else -> null
    }
} catch (_: Exception) { null }
