package com.malkbaty.safeplayer

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

fun subtitleMime(name: String): String? = when {
    name.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    name.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(activity: MainActivity, entry: MediaEntry, inPip: Boolean, onBack: () -> Unit, onChooseAnother: () -> Unit, onStarted: () -> Unit, onPosition: (Long) -> Unit) {
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }
    var fileError by remember(entry.uri) { mutableStateOf(false) }
    var subtitle by remember(entry.uri) { mutableStateOf<Pair<Uri, String>?>(null) }
    var showSpeed by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var chapters by remember(entry.uri) { mutableStateOf<List<PlaybackChapter>>(emptyList()) }
    var externalChapters by remember(entry.uri) { mutableStateOf<List<PlaybackChapter>?>(null) }
    var chapterSelection by remember(entry.uri) { mutableStateOf<Pair<Uri, Int>?>(null) }
    var chapterLoading by remember(entry.uri) { mutableStateOf(false) }
    var matroskaChapters by remember(entry.uri) { mutableStateOf<List<PlaybackChapter>>(emptyList()) }
    var matroskaLoading by remember(entry.uri) { mutableStateOf(false) }
    var matroskaError by remember(entry.uri) { mutableStateOf(false) }
    var matroskaTried by remember(entry.uri) { mutableStateOf(false) }
    var matroskaRetry by remember(entry.uri) { mutableIntStateOf(0) }
    var chapterError by remember(entry.uri) { mutableStateOf<String?>(null) }
    var showTracks by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var audioSessionId by remember(entry.uri) { mutableIntStateOf(C.AUDIO_SESSION_ID_UNSET) }
    var locked by remember(entry.uri) { mutableStateOf(false) }
    var covered by remember(entry.uri) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember(entry.uri) { mutableStateOf(false) }
    var startedOnce by remember(entry.uri) { mutableStateOf(false) }
    var gestureMessage by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var positionMs by remember(entry.uri) { mutableLongStateOf(entry.positionMs) }
    var durationMs by remember(entry.uri) { mutableLongStateOf(0L) }
    var scrub by remember(entry.uri) { mutableStateOf<Float?>(null) }
    val player = remember(entry.uri) {
        ExoPlayer.Builder(activity).build().apply {
            val builder = MediaItem.Builder().setUri(Uri.parse(entry.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
            streamMimeType(entry.uri)?.let { builder.setMimeType(it) }
            setMediaItem(builder.build())
            if (entry.positionMs > 0) seekTo(entry.positionMs)
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
        streamMimeType(entry.uri)?.let { builder.setMimeType(it) }
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
            if (mime == null) { fileError = false; error = "اختر ملف ترجمة بصيغة SRT أو VTT." }
            else { error = null; applySubtitle(uri to mime) }
        }
    }
    val chapterPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            chapterSelection = uri to ((chapterSelection?.second ?: 0) + 1)
            showChapters = true
        }
    }
    LaunchedEffect(chapterSelection, entry.uri) {
        val selected = chapterSelection ?: return@LaunchedEffect
        chapterLoading = true
        chapterError = null
        try {
            val parsed = withContext(Dispatchers.IO) {
                val stream = activity.contentResolver.openInputStream(selected.first) ?: throw java.io.IOException("no stream")
                val bytes = stream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer, 0, minOf(buffer.size, MAX_CHAPTER_FILE_BYTES + 1 - output.size()))
                        if (read < 0) break
                        if (read == 0) throw java.io.IOException("empty read")
                        output.write(buffer, 0, read)
                        if (output.size() > MAX_CHAPTER_FILE_BYTES) throw java.io.IOException("file too large")
                    }
                    output.toByteArray()
                }
                parseWebVttChapters(bytes)
            }
            externalChapters = parsed
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { chapterError = "تعذرت قراءة ملف الفصول. اختر ملف WebVTT صالحاً لا يتجاوز 512 كيلوبايت." }
        finally { chapterLoading = false }
    }
    LaunchedEffect(showChapters, entry.uri, matroskaRetry) {
        val uri = Uri.parse(entry.uri)
        val possibleMatroska = listOf(".mkv", ".mka", ".webm").any { entry.title.endsWith(it, ignoreCase = true) }
        if (!showChapters || matroskaTried || !possibleMatroska || uri.scheme !in listOf("content", "file")) return@LaunchedEffect
        matroskaTried = true
        matroskaLoading = true
        matroskaError = false
        try {
            matroskaChapters = withContext(Dispatchers.IO) {
                val asset = activity.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw java.io.IOException("no descriptor")
                asset.use { descriptor ->
                    descriptor.createInputStream().use { stream ->
                        val channel = stream.channel
                        val offset = descriptor.startOffset
                        val length = descriptor.declaredLength.takeIf { it >= 0 } ?: (channel.size() - offset)
                        readMatroskaChapters(channel, offset, length)
                    }
                }
            }
        } catch (e: CancellationException) {
            matroskaTried = false
            throw e
        } catch (_: Exception) { matroskaError = true }
        finally { matroskaLoading = false }
    }
    val latestOnPosition by rememberUpdatedState(onPosition)
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.isPlaying) latestOnPosition(player.currentPosition.coerceAtLeast(0))
        }
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(player, owner, inPip, locked, isPlaying) {
        if (!inPip && !locked && isPlaying) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                positionMs = player.currentPosition.coerceAtLeast(0)
                durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                delay(1000)
            }
        }
    }
    val latestOnStarted by rememberUpdatedState(onStarted)
    DisposableEffect(player, owner) {
        val initialBrightness = activity.window.attributes.screenBrightness
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                positionMs = player.currentPosition.coerceAtLeast(0)
                durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; if (playing) { startedOnce = true; latestOnStarted() } }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) latestOnPosition(0)
            }
            override fun onAudioSessionIdChanged(value: Int) { audioSessionId = value }
            override fun onTracksChanged(value: Tracks) {
                tracks = value
                val found = value.groups.flatMap { group ->
                    (0 until group.length).flatMap { i -> group.getTrackFormat(i).metadata?.let(::id3Chapters).orEmpty() }
                }
                if (found.isNotEmpty()) chapters = found.distinctBy { it.id to it.startMs }.sortedBy { it.startMs }.take(200)
            }
            override fun onMetadata(metadata: Metadata) {
                val found = id3Chapters(metadata)
                if (found.isNotEmpty()) chapters = (chapters + found).distinctBy { it.id to it.startMs }.sortedBy { it.startMs }.take(200)
            }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) { speed = parameters.speed }
            override fun onPlayerError(playbackError: PlaybackException) {
                fileError = playbackError.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
                    playbackError.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                error = if (fileError) "تعذر الوصول إلى الملف؛ ربما نُقل أو فُقد الإذن. أعد اختياره أو تحقق من وجوده."
                    else "تعذر تشغيل الوسائط أو الترجمة. تحقق من الصيغة والاتصال ثم حاول مجدداً."
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !activity.isInPictureInPictureMode) player.pause()
        }
        player.addListener(listener)
        audioSessionId = player.audioSessionId
        tracks = player.currentTracks
        isPlaying = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
        if (player.isPlaying) { startedOnce = true; latestOnStarted() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else if (!startedOnce && player.playerError != null) entry.positionMs else player.currentPosition.coerceAtLeast(0)
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
            if (covered) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
                    Text("الفيديو مخفي يدوياً", color = androidx.compose.ui.graphics.Color.White)
                }
            } else {
                AndroidView(factory = { context -> GesturePlayerView(context).apply { this.player = player; keepScreenOn = true } }, update = {
                    it.player = player
                    it.useController = !inPip && !locked
                    it.gesturesEnabled = !inPip && !locked
                    it.onGestureMessage = { message -> gestureMessage = message }
                }, modifier = Modifier.fillMaxSize())
            }
            if (buffering && !covered) CircularProgressIndicator(Modifier.align(Alignment.Center))
            gestureMessage?.let { Text(it, modifier = Modifier.align(Alignment.Center), color = androidx.compose.ui.graphics.Color.White) }
        }
        if (!inPip) {
            if (!locked) {
                val shownPosition = scrub?.let { (it * durationMs).toLong() } ?: positionMs
                Text("${formatPlaybackTime(shownPosition)} / ${if (durationMs > 0) formatPlaybackTime(durationMs) else "--:--"}", style = MaterialTheme.typography.bodySmall)
                Slider(value = scrub ?: if (durationMs > 0) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f,
                    onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { player.seekTo((it * durationMs).toLong()) }; scrub = null },
                    enabled = durationMs > 0 && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "موضع التشغيل" })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!locked) {
                    TextButton(onClick = { player.seekBack() }) { Text("رجوع ١٠ ث") }
                    TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Text(if (isPlaying) "إيقاف مؤقت" else "تشغيل") }
                    TextButton(onClick = { player.seekForward() }) { Text("تقديم ١٠ ث") }
                }
                TextButton(onClick = { locked = !locked; gestureMessage = null; showMore = false }) { Text(if (locked) "فتح القفل" else "قفل التحكم") }
                if (!locked) Box {
                    TextButton(onClick = { showMore = true }) { Text("المزيد") }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        if (entry.type == "video") DropdownMenuItem(text = { Text(if (covered) "إظهار الفيديو" else "إخفاء الفيديو") }, onClick = { covered = !covered; gestureMessage = null; showMore = false })
                        DropdownMenuItem(text = { Text("السرعة ${speed}×") }, onClick = { showMore = false; showSpeed = true })
                        DropdownMenuItem(text = { Text("الفصول") }, onClick = { showMore = false; showChapters = true })
                        DropdownMenuItem(text = { Text("معادل الصوت") }, onClick = { showMore = false; showEqualizer = true })
                        if (entry.type == "video") {
                            DropdownMenuItem(text = { Text("إضافة ترجمة") }, onClick = { showMore = false; picker.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text("مسارات الترجمة") }, onClick = { showMore = false; showTracks = true })
                        }
                        if (entry.type == "video" && !covered && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                            DropdownMenuItem(text = { Text("نافذة مصغرة") }, onClick = {
                                showMore = false
                                try {
                                    val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                                    if (!activity.enterPictureInPictureMode(params)) error = "تعذر فتح صورة داخل صورة على هذا الجهاز."
                                } catch (_: RuntimeException) { error = "تعذر فتح صورة داخل صورة على هذا الجهاز." }
                            })
                        }
                    }
                }
            }
            if (!locked && subtitle != null) TextButton(onClick = { applySubtitle(null) }) { Text("إزالة الترجمة") }
            if (covered) Text("إخفاء مؤقت للصورة فقط؛ الصوت مستمر، ولا يحلل المحتوى أو يوفر رقابة أبوية.", style = MaterialTheme.typography.bodySmall)
            if (!covered && !locked && entry.type == "video") Text("عند إخفاء أدوات التحكم: اسحب أفقياً للتقديم، وعمودياً يميناً للصوت ويساراً للإضاءة.", style = MaterialTheme.typography.bodySmall)
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = { error = null; fileError = false; player.prepare(); player.playWhenReady = true }) { Text("إعادة المحاولة") }
                    if (fileError) TextButton(onClick = onChooseAnother) { Text("إعادة اختيار ملف") }
                    if (subtitle != null) TextButton(onClick = { error = null; applySubtitle(null) }) { Text("إزالة الترجمة") }
                }
            }
        }
    }
    if (showChapters && !locked && !inPip) {
        val displayedChapters = externalChapters ?: matroskaChapters.ifEmpty { chapters }
        AlertDialog(onDismissRequest = { showChapters = false }, title = { Text("الفصول") }, text = {
            Column {
                Text(if (externalChapters != null) "فصول WebVTT مختارة لهذه الجلسة" else "الفصول المضمّنة المتاحة؛ يمكن اختيار ملف WebVTT بدلاً منها.")
                if (matroskaLoading) Text("جارٍ قراءة فصول الملف…")
                if (matroskaError) {
                    Text("تعذرت قراءة فصول Matroska؛ قد لا يدعم مصدر الملف القراءة العشوائية.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { matroskaTried = false; matroskaRetry++ }) { Text("إعادة محاولة قراءة الفصول") }
                }
                TextButton(enabled = !chapterLoading, onClick = { showChapters = false; chapterPicker.launch(arrayOf("*/*")) }) { Text("اختيار ملف فصول WebVTT") }
                if (externalChapters != null) TextButton(enabled = !chapterLoading, onClick = { externalChapters = null; chapterError = null }) { Text("إزالة ملف الفصول") }
                if (chapterLoading) { CircularProgressIndicator(); Text("جارٍ قراءة الفصول…") }
                chapterError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (displayedChapters.isEmpty() && !chapterLoading) Text("لا توجد فصول متاحة. فصول M4V المضمّنة غير مدعومة حالياً.")
                else androidx.compose.foundation.lazy.LazyColumn {
                    items(displayedChapters.size) { index ->
                        val chapter = displayedChapters[index]
                        TextButton(enabled = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && (durationMs <= 0 || chapter.startMs < durationMs), onClick = {
                            player.seekTo(chapter.startMs); showChapters = false
                        }) { Text("${chapter.title} — ${formatPlaybackTime(chapter.startMs)}") }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showChapters = false }) { Text("إغلاق") } })
    }
    if (showSpeed && !locked && !inPip) {
        AlertDialog(onDismissRequest = { showSpeed = false }, title = { Text("سرعة التشغيل") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                    TextButton(onClick = { player.setPlaybackSpeed(value); showSpeed = false }) { Text("${value}×${if (speed == value) " ✓" else ""}") }
                }
            }
        }, confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("إغلاق") } })
    }
    AudioEqualizer(audioSessionId, showEqualizer && !locked && !inPip) { showEqualizer = false }
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

fun formatPlaybackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = (seconds / 60) % 60
    return if (hours > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds % 60)
    else "%02d:%02d".format(java.util.Locale.ROOT, minutes, seconds % 60)
}
