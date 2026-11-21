package com.malkbaty.safeplayer

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Reads simple chapter atoms from the first Matroska edition. The caller owns the channel. */
fun readMatroskaChapters(channel: SeekableByteChannel, offset: Long = 0, length: Long = channel.size() - offset): List<PlaybackChapter> {
    require(offset >= 0 && length >= 0 && offset <= channel.size() && length <= channel.size() - offset)
    val reader = EbmlReader(channel)
    val end = offset + length
    channel.position(offset)
    val header = reader.element(end, false)
    if (header.id != 0x1A45DFA3L) throw IOException("Not an EBML file")
    channel.position(header.end)
    var segment: EbmlReader.Element? = null
    while (channel.position() < end) {
        val el = reader.element(end, true)
        if (el.id == 0x18538067L) { segment = el; break }
        if (el.unknown) throw IOException("Unknown top-level length")
        channel.position(el.end)
    }
    val root = segment ?: return emptyList()
    while (channel.position() < root.end) {
        val el = reader.element(root.end, false)
        if (el.id == 0x1043A770L) {
            if (el.end - channel.position() > 2 * 1024 * 1024) throw IOException("Chapters too large")
            return readEdition(reader, channel, el.end)
        }
        channel.position(el.end)
    }
    return emptyList()
}

private fun readEdition(reader: EbmlReader, channel: SeekableByteChannel, end: Long): List<PlaybackChapter> {
    while (channel.position() < end) {
        val el = reader.element(end, false)
        if (el.id == 0x45B9L) {
            val chapters = mutableListOf<PlaybackChapter>()
            var ordered = false
            while (channel.position() < el.end) {
                val child = reader.element(el.end, false)
                if (child.id == 0xB6L) readAtom(reader, channel, child.end, chapters, 0)
                if (child.id == 0x45DDL) ordered = reader.uint(child.end) != 0L
                channel.position(child.end)
            }
            if (ordered) throw IOException("Ordered editions are not supported")
            return chapters.sortedBy { it.startMs }
        }
        channel.position(el.end)
    }
    return emptyList()
}

private fun readAtom(reader: EbmlReader, channel: SeekableByteChannel, end: Long, chapters: MutableList<PlaybackChapter>, depth: Int) {
    if (depth >= 16 || chapters.size >= 500) throw IOException("Chapter limit exceeded")
    var start: Long? = null
    var title: String? = null
    var enabled = true
    val nested = mutableListOf<PlaybackChapter>()
    while (channel.position() < end) {
        val child = reader.element(end, false)
        when (child.id) {
            0x6E67L -> throw IOException("Linked chapter segments are not supported")
            0x91L -> start = reader.uint(child.end)?.div(1_000_000)
            0x4598L -> enabled = reader.uint(child.end) != 0L // ChapterFlagEnabled
            0x80L -> {
                while (channel.position() < child.end) {
                    val field = reader.element(child.end, false)
                    if (field.id == 0x85L && title == null) title = reader.text(field.end)
                    channel.position(field.end)
                }
            }
            0xB6L -> readAtom(reader, channel, child.end, nested, depth + 1)
        }
        channel.position(child.end)
    }
    if (enabled && start != null) chapters.add(PlaybackChapter("mkv-${chapters.size}-$start", title?.takeIf { it.isNotBlank() } ?: "فصل", start))
    if (enabled) chapters.addAll(nested)
    if (chapters.size > 500) throw IOException("Chapter limit exceeded")
}

private class EbmlReader(private val channel: SeekableByteChannel) {
    data class Element(val id: Long, val end: Long, val unknown: Boolean)
    private var elements = 0
    private fun byte(): Int {
        val b = ByteBuffer.allocate(1)
        if (channel.read(b) != 1) throw EOFException()
        return b.array()[0].toInt() and 0xff
    }
    private fun vint(maxBytes: Int, keepMarker: Boolean): Pair<Long, Int> {
        val first = byte()
        var mask = 0x80
        var count = 1
        while (mask != 0 && first and mask == 0) { mask = mask shr 1; count++ }
        if (mask == 0 || count > maxBytes) throw IOException("Invalid EBML integer")
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
        repeat(count - 1) { value = (value shl 8) or byte().toLong() }
        return value to count
    }
    fun element(parentEnd: Long, allowUnknown: Boolean): Element {
        if (++elements > 100_000 || channel.position() >= parentEnd) throw IOException("Invalid EBML element")
        val id = vint(4, true).first
        val (size, count) = vint(8, false)
        val unknown = size == (1L shl (7 * count)) - 1
        val remaining = parentEnd - channel.position()
        if (remaining < 0 || (!unknown && size > remaining) || (unknown && !allowUnknown)) throw IOException("Invalid EBML size")
        return Element(id, if (unknown) parentEnd else channel.position() + size, unknown)
    }
    fun uint(end: Long): Long? {
        val size = end - channel.position()
        if (size !in 1..8) throw IOException("Invalid unsigned integer")
        var value = 0L
        repeat(size.toInt()) { value = (value shl 8) or byte().toLong() }
        return value.takeIf { it >= 0 }
    }
    fun text(end: Long): String {
        val size = end - channel.position()
        if (size !in 0..4096) throw IOException("Chapter title too long")
        val buffer = ByteBuffer.allocate(size.toInt())
        while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) throw EOFException()
        return String(buffer.array(), Charsets.UTF_8).trimEnd('\u0000').trim()
    }
}
