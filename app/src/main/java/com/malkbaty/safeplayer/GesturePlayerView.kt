package com.malkbaty.safeplayer

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Gestures begin only while the standard controller is hidden, so its buttons remain usable. */
@UnstableApi
class GesturePlayerView(context: Context) : PlayerView(context) {
    var gesturesEnabled = true
    var onGestureMessage: (String?) -> Unit = {}
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startPosition = 0L
    private var startVolume = 0
    private var startBrightness = 0.5f
    private var allowGesture = false
    private var doubleTapEligible = false
    private var lastHiddenDown = Long.MIN_VALUE
    private var dragging = false
    private var horizontal = false
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!gesturesEnabled || !doubleTapEligible) return false
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            onGestureMessage(if (player?.isPlaying == true) "تشغيل" else "إيقاف مؤقت")
            return true
        }
    })

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                allowGesture = !isControllerFullyVisible
                doubleTapEligible = allowGesture || (lastHiddenDown != Long.MIN_VALUE && event.eventTime - lastHiddenDown <= ViewConfiguration.getDoubleTapTimeout())
                if (allowGesture) lastHiddenDown = event.eventTime
                dragging = false
                downX = event.x; downY = event.y
                startPosition = player?.currentPosition ?: 0
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val windowBrightness = (context as? MainActivity)?.window?.attributes?.screenBrightness ?: -1f
                startBrightness = if (windowBrightness >= 0f) windowBrightness else try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                } catch (_: Exception) { 0.5f }
            }
            MotionEvent.ACTION_MOVE -> if (allowGesture && width > 0 && height > 0) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && maxOf(abs(dx), abs(dy)) > slop * 2) {
                    dragging = true
                    horizontal = abs(dx) > abs(dy)
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (dragging) {
                    if (horizontal) {
                        val duration = player?.duration ?: C.TIME_UNSET
                        if (duration != C.TIME_UNSET && duration > 0 && player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true) {
                            val position = gestureSeekPosition(startPosition, dx / width, duration)
                            player?.seekTo(position)
                            onGestureMessage("الموضع: ${position / 1000} ث")
                        }
                    } else if (downX > width / 2f) {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val value = (startVolume - dy / height * max).roundToInt().coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                        onGestureMessage("الصوت: ${if (max > 0) value * 100 / max else 0}٪")
                    } else {
                        val value = (startBrightness - dy / height).coerceIn(0.01f, 1f)
                        (context as? MainActivity)?.window?.let { window ->
                            window.attributes = window.attributes.apply { screenBrightness = value }
                        }
                        onGestureMessage("الإضاءة: ${(value * 100).roundToInt()}٪")
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                onGestureMessage(null)
                return true
            }
        }
        detector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }
}

/** A full screen-width drag changes position by one minute, clamped to media duration. */
fun gestureSeekPosition(start: Long, fraction: Float, duration: Long): Long =
    (start + (fraction.coerceIn(-1f, 1f) * 60_000).toLong()).coerceIn(0, duration.coerceAtLeast(0))
