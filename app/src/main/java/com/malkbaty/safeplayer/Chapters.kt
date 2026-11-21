package com.malkbaty.safeplayer

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame

/** ID3 CHAP metadata only. Other container chapter formats are not exposed here. */
data class PlaybackChapter(val id: String, val title: String, val startMs: Long)

@androidx.annotation.OptIn(UnstableApi::class)
fun id3Chapters(metadata: Metadata): List<PlaybackChapter> = buildList {
    for (i in 0 until metadata.length()) {
        val frame = metadata[i] as? ChapterFrame ?: continue
        if (frame.startTimeMs < 0) continue
        val title = (0 until frame.subFrameCount).asSequence()
            .map { frame.getSubFrame(it) }
            .filterIsInstance<TextInformationFrame>()
            .firstOrNull { it.id == "TIT2" }
            ?.values?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: frame.chapterId.takeIf { it.isNotBlank() } ?: "فصل"
        add(PlaybackChapter(frame.chapterId, title, frame.startTimeMs.toLong()))
    }
}.distinctBy { it.id to it.startMs }.sortedWith(compareBy<PlaybackChapter> { it.startMs }.thenBy { it.id })
