package com.malkbaty.safeplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.room.Room
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val database by lazy { Room.databaseBuilder(applicationContext, MediaDatabase::class.java, "safeplayer.db").build() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SafePlayerApp(database.mediaDao())
                }
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) database.close()
    }

    @Composable
    private fun SafePlayerApp(dao: MediaDao) {
        val scope = rememberCoroutineScope()
        val entries by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
        var query by rememberSaveable { mutableStateOf("") }
        var type by rememberSaveable { mutableStateOf("all") }
        var favorites by rememberSaveable { mutableStateOf(false) }
        var playing by remember { mutableStateOf<MediaEntry?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var pendingDelete by remember { mutableStateOf<MediaEntry?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            scope.launch {
                var failed = 0
                uris.forEach { uri ->
                    try {
                        val mime = contentResolver.getType(uri).orEmpty()
                        val kind = when { mime.startsWith("video/") -> "video"; mime.startsWith("audio/") -> "audio"; else -> null }
                        if (kind == null) { failed++; return@forEach }
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val title = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: uri.lastPathSegment ?: "ملف وسائط"
                        dao.insert(MediaEntry(uri.toString(), title, kind, System.currentTimeMillis()))
                    } catch (_: Exception) { failed++ }
                }
                message = if (failed > 0) "تعذرت إضافة $failed من الملفات. تحقق من الوصول إليها وحاول مجدداً." else if (uris.isNotEmpty()) "تمت إضافة الملفات إلى المكتبة." else null
            }
        }
        val current = playing
        if (current != null) {
            BackHandler { playing = null }
            PlayerScreen(current, onBack = { playing = null }, onPosition = { position ->
                // Activity lifecycle scope survives disposal of the player composable.
                lifecycleScope.launch { dao.savePosition(current.uri, position) }
            })
        } else {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("SafePlayer", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("مكتبتك الخاصة، على جهازك", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("video/*", "audio/*")) }) { Text("إضافة ملفات") }
                    OutlinedButton(onClick = { favorites = !favorites }) { Text(if (favorites) "كل المكتبة" else "المفضلة") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("البحث في المكتبة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "الكل", "video" to "فيديو", "audio" to "صوتيات").forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary); TextButton(onClick = { message = null }) { Text("إغلاق الرسالة") } }
                val filtered = filterMedia(entries, query, type, favorites)
                if (filtered.isEmpty()) {
                    Spacer(Modifier.height(32.dp))
                    Text(if (entries.isEmpty()) "مكتبتك فارغة. أضف ملفات من جهازك للبدء." else "لا توجد ملفات مطابقة. غيّر البحث أو الفلتر.")
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(filtered, key = { it.uri }) { entry ->
                        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(if (entry.type == "video") "فيديو" else "صوت", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { playing = entry }) { Text("تشغيل") }
                                    TextButton(onClick = { scope.launch { try { dao.setFavorite(entry.uri, !entry.favorite) } catch (_: Exception) { message = "تعذر حفظ المفضلة. حاول مجدداً." } } }) { Text(if (entry.favorite) "إزالة المفضلة" else "إضافة للمفضلة") }
                                    TextButton(onClick = { pendingDelete = entry }) { Text("حذف") }
                                }
                            }
                        }
                    }
                }
            }
        }
        pendingDelete?.let { entry ->
            AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("إزالة من المكتبة؟") }, text = { Text("لن يُحذف الملف الأصلي من جهازك.") }, confirmButton = { TextButton(onClick = { pendingDelete = null; scope.launch { try { dao.delete(entry.uri); try { contentResolver.releasePersistableUriPermission(Uri.parse(entry.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {} } catch (_: Exception) { message = "تعذرت إزالة الملف. حاول مجدداً." } } }) { Text("إزالة") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("إلغاء") } })
        }
    }

    @Composable
    private fun PlayerScreen(entry: MediaEntry, onBack: () -> Unit, onPosition: (Long) -> Unit) {
        var error by remember(entry.uri) { mutableStateOf<String?>(null) }
        var attempt by remember(entry.uri) { mutableIntStateOf(0) }
        val player = remember(entry.uri, attempt) {
            ExoPlayer.Builder(this).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(entry.uri)))
                seekTo(entry.positionMs)
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlayerError(playbackError: PlaybackException) { error = "تعذر تشغيل الملف. تحقق من إمكانية الوصول إليه ودعم صيغته." }
            }
            player.addListener(listener)
            onDispose {
                val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
                onPosition(position)
                player.removeListener(listener)
                player.release()
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = onBack) { Text("← رجوع إلى المكتبة") }
            Text(entry.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AndroidView(factory = { context -> PlayerView(context).apply { this.player = player } }, update = { it.player = player }, modifier = Modifier.fillMaxWidth().weight(1f))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); Button(onClick = { error = null; attempt++ }) { Text("إعادة المحاولة") } }
        }
    }
}
