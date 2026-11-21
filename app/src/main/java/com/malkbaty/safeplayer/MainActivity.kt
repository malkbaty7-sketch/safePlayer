package com.malkbaty.safeplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
            val settings = remember { SettingsRepository(applicationContext) }
            val repository = remember { MediaRepository(applicationContext, database.mediaDao()) }
            val appearance = remember { settings.appearance() }
            var palette by remember { mutableStateOf(appearance.palette) }
            var dark by remember { mutableStateOf(appearance.dark) }
            SafePlayerTheme(palette, dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SafePlayerApp(repository, palette, dark) { selected, isDark ->
                        palette = selected
                        dark = isDark
                        settings.saveAppearance(Appearance(selected, isDark))
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
    private fun SafePlayerApp(repository: MediaRepository, palette: Palette, dark: Boolean, onTheme: (Palette, Boolean) -> Unit) {
        val scope = rememberCoroutineScope()
        var retry by remember { mutableIntStateOf(0) }
        val libraryFlow = remember(repository, retry) {
            repository.media.map<List<MediaEntry>, LibraryState> { LibraryState.Ready(it) }
                .catch { emit(LibraryState.Error) }
        }
        val library by libraryFlow.collectAsStateWithLifecycle(initialValue = LibraryState.Loading)
        val entries = (library as? LibraryState.Ready)?.entries.orEmpty()
        var showSettings by remember { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var type by rememberSaveable { mutableStateOf("all") }
        var favorites by rememberSaveable { mutableStateOf(false) }
        var sort by rememberSaveable { mutableStateOf(MediaSort.NEWEST) }
        var showSort by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf<MediaEntry?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var pendingDelete by remember { mutableStateOf<MediaEntry?>(null) }
        var scanning by remember { mutableStateOf(false) }
        val notifications = remember { ScanNotifications(applicationContext) }
        var notificationsEnabled by remember { mutableStateOf(notifications.enabled && notifications.allowed()) }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifications.enabled = granted && notifications.allowed()
            notificationsEnabled = notifications.enabled
            message = if (notificationsEnabled) "تم تفعيل إشعارات الفحص." else "لم يُمنح إذن الإشعارات. يمكن تفعيله من إعدادات الجهاز."
        }
        val cloud = remember { CloudRepository(applicationContext) }
        var showCloud by remember { mutableStateOf(false) }
        var pendingUpload by remember { mutableStateOf<MediaEntry?>(null) }
        var uploading by remember { mutableStateOf(false) }
        val scanner = repository.scanner
        fun startScan() {
            val access = scanner.access()
            if (!access.video && !access.audio) {
                message = "لم يُمنح إذن الوصول للوسائط. يمكنك منح الإذن من إعدادات التطبيق أو إضافة ملفات يدوياً."
                return
            }
            scope.launch {
                scanning = true
                message = null
                try {
                    val result = scanner.scan(access)
                    notifications.show(result)
                    val scopeNote = if (!access.video || !access.audio || access.limitedVideo) " الفحص يشمل الوسائط المسموح بها فقط." else ""
                    message = "اكتمل الفحص: ${result.found} ملف، أُضيف منها ${result.added}.$scopeNote"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = "تعذر إكمال فحص الوسائط؛ قد تكون بعض الملفات أُضيفت. تحقق من الصلاحيات وحاول مجدداً."
                } finally { scanning = false }
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startScan() }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            scope.launch {
                val result = repository.importDocuments(uris)
                message = if (result.failed > 0) "تعذرت إضافة ${result.failed} من الملفات. تحقق من الوصول إليها وحاول مجدداً." else if (result.added > 0) "تمت إضافة الملفات إلى المكتبة." else null
            }
        }
        val current = playing
        if (current != null) {
            BackHandler { playing = null }
            PlayerScreen(this, current, inPip, onBack = { playing = null }, onPosition = { position ->
                // Activity lifecycle scope survives disposal of the player composable.
                lifecycleScope.launch { try { repository.savePosition(current.uri, position) } catch (_: Exception) { message = "تعذر حفظ موضع التشغيل." } }
            })
        } else {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("SafePlayer", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("مكتبتك الخاصة، على جهازك", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("video/*", "audio/*")) }) { Text("إضافة ملفات") }
                    OutlinedButton(onClick = { favorites = !favorites }) { Text(if (favorites) "كل المكتبة" else "المفضلة") }
                    TextButton(onClick = { showSettings = true }) { Text("الإعدادات") }
                    TextButton(onClick = { showCloud = true }) { Text("السحابة") }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(enabled = !scanning, onClick = { permissionLauncher.launch(scanner.permissions()) }) { Text("فحص وسائط الجهاز") }
                    if (scanning) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(24.dp)); Text(" جارٍ الفحص…") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("البحث في المكتبة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "الكل", "video" to "فيديو", "audio" to "صوتيات").forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary); TextButton(onClick = { message = null }) { Text("إغلاق الرسالة") } }
                Box {
                    TextButton(onClick = { showSort = true }) { Text("الترتيب: ${sort.label}") }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        MediaSort.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; showSort = false })
                        }
                    }
                }
                val filtered = searchLibrary(entries, LibraryQuery(query, type, favorites, sort))
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
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { playing = entry }) { Text("تشغيل") }
                                    TextButton(onClick = { scope.launch { try { repository.setFavorite(entry.uri, !entry.favorite); Toast.makeText(applicationContext, if (entry.favorite) "أُزيل من المفضلة" else "أُضيف إلى المفضلة", Toast.LENGTH_SHORT).show() } catch (_: Exception) { message = "تعذر حفظ المفضلة. حاول مجدداً." } } }) { Text(if (entry.favorite) "إزالة المفضلة" else "إضافة للمفضلة") }
                                    TextButton(onClick = { pendingDelete = entry }) { Text("حذف") }
                                    if (entry.type == "video") TextButton(enabled = !uploading, onClick = { pendingUpload = entry }) { Text("حفظ سحابي") }
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
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = notificationsEnabled, onCheckedChange = { enable ->
                            if (!enable) { notifications.enabled = false; notificationsEnabled = false }
                            else if (Build.VERSION.SDK_INT >= 33 && !notifications.allowed()) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else if (notifications.allowed()) { notifications.enabled = true; notificationsEnabled = true }
                            else message = "فعّل الإشعارات من إعدادات الجهاز."
                        })
                        Text("إشعار عند اكتمال الفحص")
                    }
                }
            }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("تم") } })
        }
        if (showCloud) CloudDialog(cloud, onClose = { showCloud = false })
        pendingUpload?.let { entry ->
            AlertDialog(onDismissRequest = { pendingUpload = null }, title = { Text("حفظ بيانات الفيديو سحابياً؟") },
                text = { Text("سيُرسل العنوان وحالة المفضلة إلى Firebase، دون الملف أو مساره. الحساب المجهول مرتبط بهذا التثبيت وقد تفقد الوصول عند مسح بياناته. قد يؤدي التكرار إلى إنشاء نسخ متعددة.") },
                confirmButton = { TextButton(onClick = {
                    pendingUpload = null
                    scope.launch {
                        uploading = true; message = "جارٍ حفظ بيانات الفيديو سحابياً…"
                        try { cloud.save(entry); message = "تم حفظ بيانات الفيديو سحابياً." }
                        catch (_: TimeoutCancellationException) { message = "انتهت مهلة الحفظ وقد يكتمل لاحقاً. تحقق من السحابة قبل إعادة المحاولة." }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = "تعذر الحفظ السحابي. تحقق من إعداد Firebase والاتصال وقواعد الأمان وحاول مجدداً." }
                        finally { uploading = false }
                    }
                }) { Text("إرسال") } }, dismissButton = { TextButton(onClick = { pendingUpload = null }) { Text("إلغاء") } })
        }
        pendingDelete?.let { entry ->
            AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("إزالة من المكتبة؟") }, text = { Text("لن يُحذف الملف الأصلي من جهازك، وقد يظهر مجدداً عند فحص الوسائط.") }, confirmButton = { TextButton(onClick = { pendingDelete = null; scope.launch { try { repository.remove(entry.uri) } catch (_: Exception) { message = "تعذرت إزالة الملف. حاول مجدداً." } } }) { Text("إزالة") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("إلغاء") } })
        }
    }

}
