package com.malkbaty.safeplayer

import java.nio.charset.CodingErrorAction

const val MAX_CHAPTER_FILE_BYTES = 512 * 1024
private const val MAX_CHAPTERS = 500

/** A limited WebVTT chapter-cue reader, not a subtitle renderer. Reject malformed cues. */
fun parseWebVttChapters(bytes: ByteArray): List<PlaybackChapter> {
    require(bytes.size <= MAX_CHAPTER_FILE_BYTES) { "file too large" }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        .replace("\r\n", "\n").replace('\r', '\n')
    val lines = text.split('\n')
    require(lines.firstOrNull()?.matches(Regex("WEBVTT(?:[ \\t].*)?")) == true) { "missing WEBVTT header" }
    var i = 1
    while (i < lines.size && lines[i].isNotBlank()) i++ // header metadata
    val chapters = mutableListOf<PlaybackChapter>()
    val timing = Regex("^(\\S+)\\s+-->\\s+(\\S+)(?:\\s+.*)?$")
    while (i < lines.size) {
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size) break
        val block = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank()) block.add(lines[i++])
        if (block.first() == "NOTE" || block.first().startsWith("NOTE ") || block.first() == "STYLE" || block.first() == "REGION") continue
        val timingIndex = if (block[0].contains("-->")) 0 else 1
        require(timingIndex < block.size) { "missing cue timing" }
        val match = timing.matchEntire(block[timingIndex].trim()) ?: throw IllegalArgumentException("invalid cue timing")
        val start = vttTimestampMs(match.groupValues[1]) ?: throw IllegalArgumentException("invalid start")
        val end = vttTimestampMs(match.groupValues[2]) ?: throw IllegalArgumentException("invalid end")
        require(end > start) { "invalid cue interval" }
        val title = block.drop(timingIndex + 1).joinToString(" ").trim()
        require(title.isNotEmpty() && title.length <= 500) { "invalid chapter title" }
        require(chapters.size < MAX_CHAPTERS) { "too many chapters" }
        chapters.add(PlaybackChapter("vtt-${chapters.size}", title, start))
    }
    require(chapters.isNotEmpty()) { "no chapter cues" }
    return chapters.sortedBy { it.startMs }
}

private fun vttTimestampMs(value: String): Long? {
    val match = Regex("^(?:(\\d{2,6}):)?([0-5]\\d):([0-5]\\d)\\.(\\d{3})$").matchEntire(value) ?: return null
    val hours = match.groupValues[1].ifEmpty { "0" }.toLong()
    val minutes = match.groupValues[2].toLong()
    val seconds = match.groupValues[3].toLong()
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + match.groupValues[4].toLong()
}
