package com.malkbaty.safeplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Mp4ChaptersTest {
    @Test fun readsAndSeeksRealM4v() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "chapters-test.m4v")
        instrumentation.context.assets.open("chapters.m4v").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            val chapters = RandomAccessFile(file, "r").use { readMp4Chapters(it.channel) }
            assertEquals(listOf(0L, 1000L), chapters.map { it.startMs })
            assertEquals(listOf("مقدمة", "Second"), chapters.map { it.title })
            val ready = CountDownLatch(1)
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build()
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
            }
            try {
                assertTrue("player did not prepare fixture", ready.await(15, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    player.seekTo(chapters[1].startMs)
                    assertEquals(1000L, player.currentPosition)
                }
            } finally { instrumentation.runOnMainSync { player.release() } }
        } finally { file.delete() }
    }
    @Test fun malformedBoxIsRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "invalid-chapters.m4v")
        file.writeBytes(byteArrayOf(0, 0, 0, 40, 109, 111, 111, 118))
        try {
            var failed = false
            try { RandomAccessFile(file, "r").use { readMp4Chapters(it.channel) } } catch (_: java.io.IOException) { failed = true }
            assertTrue(failed)
        } finally { file.delete() }
    }
}
