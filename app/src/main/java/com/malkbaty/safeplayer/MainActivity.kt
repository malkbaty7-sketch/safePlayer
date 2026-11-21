package com.malkbaty.safeplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.res.Configuration
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
import androidx.room.Room
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private sealed interface LibraryState {
    data object Loading : LibraryState
    data object Error : LibraryState
    data class Ready(val entries: List<MediaEntry>) : LibraryState
}

class MainActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }
    private val database by lazy { Room.databaseBuilder(applicationContext, MediaDatabase::class.java, "safeplayer.db").build() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var palette by remember { mutableStateOf(Palette.entries.find { it.name == prefs.getString("palette", "FOREST") } ?: Palette.FOREST) }
            var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
            SafePlayerTheme(palette, dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SafePlayerApp(database.mediaDao(), palette, dark) { selected, isDark ->
                        palette = selected
                        dark = isDark
                        prefs.edit().putString("palette", selected.name).putBoolean("dark", isDark).apply()
                    }
                }
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) database.close()
    }

    @Composable
    private fun SafePlayerApp(dao: MediaDao, palette: Palette, dark: Boolean, onTheme: (Palette, Boolean) -> Unit) {
        val scope = rememberCoroutineScope()
        var retry by remember { mutableIntStateOf(0) }
        val libraryFlow = remember(dao, retry) {
            dao.observeAll().map<List<MediaEntry>, LibraryState> { LibraryState.Ready(it) }
                .catch { emit(LibraryState.Error) }
        }
        val library by libraryFlow.collectAsStateWithLifecycle(initialValue = LibraryState.Loading)
        val entries = (library as? LibraryState.Ready)?.entries.orEmpty()
        var showSettings by remember { mutableStateOf(false) }
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
            PlayerScreen(this, current, inPip, onBack = { playing = null }, onPosition = { position ->
                // Activity lifecycle scope survives disposal of the player composable.
                lifecycleScope.launch { try { dao.savePosition(current.uri, position) } catch (_: Exception) { message = "تعذر حفظ موضع التشغيل." } }
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
                    TextButton(onClick = { showSettings = true }) { Text("المظهر") }
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
                if (library is LibraryState.Loading) {
                    Spacer(Modifier.height(32.dp)); CircularProgressIndicator(); Text("جارٍ تحميل المكتبة…")
                } else if (library is LibraryState.Error) {
                    Spacer(Modifier.height(32.dp)); Text("تعذر تحميل المكتبة. حاول مجدداً.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { retry++ }) { Text("إعادة المحاولة") }
                } else if (filtered.isEmpty()) {
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
        if (showSettings) {
            AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("المظهر") }, text = {
                Column {
                    Text("الألوان")
                    Palette.entries.forEach { item ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = palette == item, onClick = { onTheme(item, dark) })
                            Text(item.label)
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = dark, onCheckedChange = { onTheme(palette, it) })
                        Text("الوضع الداكن")
                    }
                }
            }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("تم") } })
        }
        pendingDelete?.let { entry ->
            AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("إزالة من المكتبة؟") }, text = { Text("لن يُحذف الملف الأصلي من جهازك.") }, confirmButton = { TextButton(onClick = { pendingDelete = null; scope.launch { try { dao.delete(entry.uri); try { contentResolver.releasePersistableUriPermission(Uri.parse(entry.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {} } catch (_: Exception) { message = "تعذرت إزالة الملف. حاول مجدداً." } } }) { Text("إزالة") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("إلغاء") } })
        }
    }

}
