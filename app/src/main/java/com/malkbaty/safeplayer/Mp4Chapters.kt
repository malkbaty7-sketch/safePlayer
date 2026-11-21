package com.malkbaty.safeplayer

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Reads Nero chpl chapter lists in moov/udta; QuickTime chapter tracks are not supported. */
fun readMp4Chapters(channel: SeekableByteChannel, offset: Long = 0, length: Long = channel.size() - offset): List<PlaybackChapter> {
    require(offset >= 0 && length >= 0 && offset <= channel.size() && length <= channel.size() - offset)
    val end = offset + length
    channel.position(offset)
    val reader = Mp4BoxReader(channel)
    while (channel.position() < end) {
        val box = reader.box(end)
        if (box.type == "moov") {
            while (channel.position() < box.end) {
                val child = reader.box(box.end)
                if (child.type == "udta") {
                    while (channel.position() < child.end) {
                        val entry = reader.box(child.end)
                        if (entry.type == "chpl") return reader.chapters(entry.end)
                        channel.position(entry.end)
                    }
                }
                channel.position(child.end)
            }
        }
        channel.position(box.end)
    }
    return emptyList()
}

private class Mp4BoxReader(private val channel: SeekableByteChannel) {
    data class Box(val type: String, val end: Long)
    private var boxes = 0
    private fun read(size: Int, end: Long): ByteBuffer {
        if (size < 0 || channel.position() > end || size.toLong() > end - channel.position()) throw EOFException()
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) throw EOFException()
        return buffer.apply { flip() }
    }
    fun box(parentEnd: Long): Box {
        if (++boxes > 100_000) throw IOException("too many MP4 boxes")
        val start = channel.position()
        val header = read(8, parentEnd)
        val shortSize = header.int.toLong() and 0xffffffffL
        val bytes = ByteArray(4); header.get(bytes)
        val type = String(bytes, Charsets.ISO_8859_1)
        val size = when (shortSize) {
            0L -> parentEnd - start
            1L -> read(8, parentEnd).long
            else -> shortSize
        }
        val headerSize = channel.position() - start
        if (size < headerSize || size > parentEnd - start) throw IOException("invalid MP4 box size")
        return Box(type, start + size)
    }
    fun chapters(end: Long): List<PlaybackChapter> {
        if (end - channel.position() > 64 * 1024) throw IOException("chapter box too large")
        val header = read(4, end)
        val version = header.get().toInt() and 0xff
        if (version !in 0..1) throw IOException("unsupported chapter version")
        if (version == 1) read(4, end)
        val count = read(1, end).get().toInt() and 0xff
        val chapters = mutableListOf<PlaybackChapter>()
        repeat(count) { index ->
            val start100ns = read(8, end).long
            if (start100ns < 0) throw IOException("chapter timestamp too large")
            val titleLength = read(1, end).get().toInt() and 0xff
            val titleBytes = ByteArray(titleLength)
            read(titleLength, end).get(titleBytes)
            val title = String(titleBytes, Charsets.UTF_8).trim().ifEmpty { "فصل ${index + 1}" }
            chapters.add(PlaybackChapter("chpl-$index", title, start100ns / 10_000))
        }
        return chapters.sortedBy { it.startMs }
    }
}
