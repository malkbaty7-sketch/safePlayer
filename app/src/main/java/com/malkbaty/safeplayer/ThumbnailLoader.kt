package com.malkbaty.safeplayer

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class ThumbnailKey(val uri: String, val size: Long?, val duration: Long?)

/** Byte-bounded LRU; never recycles bitmaps that could still be displayed by Compose. */
internal class ThumbnailCache(maxBytes: Int) : LruCache<ThumbnailKey, Bitmap>(maxBytes) {
    override fun sizeOf(key: ThumbnailKey, value: Bitmap): Int = value.allocationByteCount
}

/** Process-wide, memory-only thumbnail loader, limited to two concurrent requests. */
class ThumbnailLoader private constructor(private val context: Context) : ComponentCallbacks2 {
    private val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val maxBytes = if (lowRam) 2 * 1024 * 1024 else minOf(8 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 64).toInt().coerceAtLeast(1024 * 1024))
    private val cache = ThumbnailCache(maxBytes)
    private var generation = 0L
    private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32)).apply { allowCoreThreadTimeOut(true) }

    init { context.registerComponentCallbacks(this) }

    suspend fun load(entry: MediaEntry): Bitmap? {
        if (Build.VERSION.SDK_INT < 29 || entry.type != "video") return null
        val key = ThumbnailKey(entry.uri, entry.sizeBytes, entry.durationMs)
        val startGeneration: Long
        synchronized(this) {
            cache.get(key)?.let { return it }
            startGeneration = generation
        }
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val task = FutureTask {
                if (continuation.isActive) {
                    val bitmap = try { context.contentResolver.loadThumbnail(Uri.parse(entry.uri), Size(320, 180), signal) }
                    catch (_: Exception) { null }
                    if (bitmap != null && continuation.isActive) synchronized(this) {
                        if (generation == startGeneration) cache.put(key, bitmap)
                    }
                    if (continuation.isActive) continuation.resume(bitmap)
                }
            }
            continuation.invokeOnCancellation { signal.cancel(); task.cancel(true); executor.remove(task) }
            try { executor.execute(task) }
            catch (_: RejectedExecutionException) { if (continuation.isActive) continuation.resume(null) }
        }
    }

    @Synchronized fun clear() { generation++; cache.evictAll() }
    override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) clear() }
    override fun onLowMemory() = clear()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    companion object {
        @Volatile private var instance: ThumbnailLoader? = null
        fun get(context: Context): ThumbnailLoader = instance ?: synchronized(this) {
            instance ?: ThumbnailLoader(context.applicationContext).also { instance = it }
        }
        fun clearIfCreated() { instance?.clear() }
    }
}
