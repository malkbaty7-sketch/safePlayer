package com.malkbaty.safeplayer

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.delay

fun subtitleMime(name: String): String? = when {
    name.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    name.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(activity: MainActivity, entry: MediaEntry, inPip: Boolean, onBack: () -> Unit, onPosition: (Long) -> Unit) {
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }
    var subtitle by remember(entry.uri) { mutableStateOf<Pair<Uri, String>?>(null) }
    var showSpeed by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var locked by remember(entry.uri) { mutableStateOf(false) }
    var gestureMessage by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    val player = remember(entry.uri) {
        ExoPlayer.Builder(activity).build().apply {
            setMediaItem(MediaItem.Builder().setUri(Uri.parse(entry.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build()).build())
            seekTo(entry.positionMs)
            prepare()
            playWhenReady = true
        }
    }
    val session = remember(player) { MediaSession.Builder(activity, player).build() }
    fun applySubtitle(value: Pair<Uri, String>?) {
        val position = player.currentPosition.coerceAtLeast(0)
        val playing = player.playWhenReady
        val builder = MediaItem.Builder().setUri(Uri.parse(entry.uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
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
        val initialBrightness = activity.window.attributes.screenBrightness
        val listener = object : Player.Listener {
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) { speed = parameters.speed }
            override fun onPlayerError(playbackError: PlaybackException) { error = "تعذر تشغيل الملف أو الترجمة. تحقق من الوصول والصيغة ثم حاول مجدداً." }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !activity.isInPictureInPictureMode) player.pause()
        }
        player.addListener(listener)
        tracks = player.currentTracks
        owner.lifecycle.addObserver(observer)
        onDispose {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
            onPosition(position)
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            session.release()
            player.release()
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = initialBrightness }
        }
    }
    Column(Modifier.fillMaxSize().padding(if (inPip) 0.dp else 16.dp)) {
        if (!inPip) {
            TextButton(onClick = onBack) { Text("← رجوع إلى المكتبة") }
            Text(entry.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { context -> GesturePlayerView(context).apply { this.player = player; keepScreenOn = true } }, update = {
                it.player = player
                it.useController = !inPip && !locked
                it.gesturesEnabled = !inPip && !locked
                it.onGestureMessage = { message -> gestureMessage = message }
            }, modifier = Modifier.fillMaxSize())
            gestureMessage?.let { Text(it, modifier = Modifier.align(Alignment.Center), color = androidx.compose.ui.graphics.Color.White) }
        }
        if (!inPip) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { locked = !locked; gestureMessage = null }) { Text(if (locked) "فتح القفل" else "قفل التحكم") }
                if (!locked) {
                Box {
                    TextButton(onClick = { showSpeed = true }) { Text("السرعة ${speed}×") }
                    DropdownMenu(expanded = showSpeed, onDismissRequest = { showSpeed = false }) {
                        listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            DropdownMenuItem(text = { Text("${speed}×") }, onClick = { player.setPlaybackSpeed(speed); showSpeed = false })
                        }
                    }
                }
                if (entry.type == "video") {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("إضافة ترجمة") }
                    TextButton(onClick = { showTracks = true }) { Text("مسارات الترجمة") }
                }
                if (entry.type == "video" && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    TextButton(onClick = {
                        try {
                            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                            if (!activity.enterPictureInPictureMode(params)) error = "تعذر فتح صورة داخل صورة على هذا الجهاز."
                        } catch (_: RuntimeException) { error = "تعذر فتح صورة داخل صورة على هذا الجهاز." }
                    }) { Text("نافذة مصغرة") }
                }
                }
            }
            if (!locked && subtitle != null) TextButton(onClick = { applySubtitle(null) }) { Text("إزالة الترجمة") }
            if (!locked && entry.type == "video") Text("عند إخفاء أدوات التحكم: اسحب أفقياً للتقديم، وعمودياً يميناً للصوت ويساراً للإضاءة.", style = MaterialTheme.typography.bodySmall)
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = { error = null; player.prepare(); player.playWhenReady = true }) { Text("إعادة المحاولة") }
                    if (subtitle != null) TextButton(onClick = { error = null; applySubtitle(null) }) { Text("إزالة الترجمة") }
                }
            }
        }
    }
    if (showTracks && !locked && !inPip) {
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        AlertDialog(onDismissRequest = { showTracks = false }, title = { Text("مسارات الترجمة") }, text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    TextButton(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
                        showTracks = false
                    }) { Text("إيقاف الترجمة") }
                }
                if (textGroups.isEmpty()) item { Text("لا توجد مسارات ترجمة متاحة.") }
                textGroups.forEachIndexed { groupIndex, group ->
                    items(group.length) { index ->
                        val format = group.getTrackFormat(index)
                        val label = format.label ?: format.language ?: "مسار ${groupIndex + 1}-${index + 1}"
                        TextButton(enabled = group.isTrackSupported(index), onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                            showTracks = false
                        }) { Text(if (group.isTrackSelected(index)) "$label ✓" else label) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showTracks = false }) { Text("إغلاق") } })
    }

}
