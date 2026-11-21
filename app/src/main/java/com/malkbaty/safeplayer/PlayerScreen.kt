package com.malkbaty.safeplayer

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

fun subtitleMime(name: String): String? = when {
    name.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    name.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    else -> null
}

@Composable
fun PlayerScreen(activity: MainActivity, entry: MediaEntry, inPip: Boolean, onBack: () -> Unit, onPosition: (Long) -> Unit) {
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }
    var subtitle by remember(entry.uri) { mutableStateOf<Pair<Uri, String>?>(null) }
    var showSpeed by remember { mutableStateOf(false) }
    val player = remember(entry.uri) {
        ExoPlayer.Builder(activity).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(entry.uri)))
            seekTo(entry.positionMs)
            prepare()
            playWhenReady = true
        }
    }
    fun applySubtitle(value: Pair<Uri, String>?) {
        val position = player.currentPosition.coerceAtLeast(0)
        val playing = player.playWhenReady
        val builder = MediaItem.Builder().setUri(Uri.parse(entry.uri))
        value?.let { (uri, mime) ->
            builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).build()))
        }
        player.setMediaItem(builder.build(), position)
        player.prepare()
        player.playWhenReady = playing
        subtitle = value
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = try {
                activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (_: Exception) { null }
            val mime = subtitleMime(name ?: "")
            if (mime == null) error = "اختر ملف ترجمة بصيغة SRT أو VTT."
            else { error = null; applySubtitle(uri to mime) }
        }
    }
    val latestOnPosition by rememberUpdatedState(onPosition)
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.isPlaying) latestOnPosition(player.currentPosition.coerceAtLeast(0))
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) { error = "تعذر تشغيل الملف أو الترجمة. تحقق من الوصول والصيغة ثم حاول مجدداً." }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !activity.isInPictureInPictureMode) player.pause()
        }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
            onPosition(position)
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    Column(Modifier.fillMaxSize().padding(if (inPip) 0.dp else 16.dp)) {
        if (!inPip) {
            TextButton(onClick = onBack) { Text("← رجوع إلى المكتبة") }
            Text(entry.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
        }
        AndroidView(factory = { context -> PlayerView(context).apply { this.player = player } }, update = { it.player = player; it.useController = !inPip }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (!inPip) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    TextButton(onClick = { showSpeed = true }) { Text("السرعة ${player.playbackParameters.speed}×") }
                    DropdownMenu(expanded = showSpeed, onDismissRequest = { showSpeed = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            DropdownMenuItem(text = { Text("${speed}×") }, onClick = { player.setPlaybackSpeed(speed); showSpeed = false })
                        }
                    }
                }
                if (entry.type == "video") TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("ترجمة") }
                if (entry.type == "video" && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    TextButton(onClick = {
                        try {
                            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                            if (!activity.enterPictureInPictureMode(params)) error = "تعذر فتح صورة داخل صورة على هذا الجهاز."
                        } catch (_: RuntimeException) { error = "تعذر فتح صورة داخل صورة على هذا الجهاز." }
                    }) { Text("نافذة مصغرة") }
                }
            }
            if (subtitle != null) TextButton(onClick = { applySubtitle(null) }) { Text("إزالة الترجمة") }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = { error = null; player.prepare(); player.playWhenReady = true }) { Text("إعادة المحاولة") }
                    if (subtitle != null) TextButton(onClick = { error = null; applySubtitle(null) }) { Text("إزالة الترجمة") }
                }
            }
        }
    }
}
