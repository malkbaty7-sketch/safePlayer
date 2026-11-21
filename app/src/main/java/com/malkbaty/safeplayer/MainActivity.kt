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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val database by lazy { MediaDatabase.get(applicationContext) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { SettingsRepository(applicationContext) }
            val repository = remember { MediaRepository(applicationContext, database.mediaDao()) }
            val appearance = remember { settings.appearance() }
            var palette by remember { mutableStateOf(appearance.palette) }
            var dark by remember { mutableStateOf(appearance.dark) }
            var showThumbnails by remember { mutableStateOf(settings.showThumbnails()) }
            SafePlayerTheme(palette, dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SafePlayerApp(repository, palette, dark, showThumbnails, onThumbnails = { show ->
                        showThumbnails = show
                        settings.setShowThumbnails(show)
                    }) { selected, isDark ->
                        palette = selected
                        dark = isDark
                        settings.saveAppearance(Appearance(selected, isDark))
                    }
                }
                }
            }
        }
    }
    @Composable
    private fun SafePlayerApp(repository: MediaRepository, palette: Palette, dark: Boolean, showThumbnails: Boolean, onThumbnails: (Boolean) -> Unit, onTheme: (Palette, Boolean) -> Unit) {
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
        var gridView by rememberSaveable { mutableStateOf(true) }
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
        val periodic = remember { PeriodicScanner(applicationContext) }
        val jobs by periodic.work.collectAsStateWithLifecycle(initialValue = emptyList())
        val periodicEnabled = jobs.any { !it.state.isFinished }
        var scheduleChanging by remember { mutableStateOf(false) }
        fun changeSchedule(enable: Boolean) {
            scope.launch {
                scheduleChanging = true
                try {
                    val operation = if (enable) periodic.enable() else periodic.disable()
                    withContext(Dispatchers.IO) { operation.result.get() }
                    message = if (enable) "تمت جدولة الفحص التلقائي؛ يحدد النظام وقت التنفيذ." else "تم إيقاف الفحص التلقائي."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "تعذر تحديث جدول الفحص. حاول مجدداً." }
                finally { scheduleChanging = false }
            }
        }
        val periodicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val access = scanner.access()
            if (access.video || access.audio) changeSchedule(true)
            else message = "لم يُمنح إذن الوسائط؛ لم تُفعّل الفهرسة التلقائية."
        }
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
            val filtered = searchLibrary(entries, LibraryQuery(query, type, favorites, sort))
            LazyVerticalGrid(
                columns = if (gridView) GridCells.Adaptive(230.dp) else GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("SafePlayer", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        Text("مكتبتك الخاصة، على جهازك", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
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
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("البحث في المكتبة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("all" to "الكل", "video" to "فيديو", "audio" to "صوتيات").forEach { (value, label) ->
                                FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                            }
                        }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.primary); TextButton(onClick = { message = null }) { Text("إغلاق الرسالة") } }
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box {
                                TextButton(onClick = { showSort = true }) { Text("الترتيب: ${sort.label}") }
                                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                                    MediaSort.entries.forEach { option ->
                                        DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; showSort = false })
                                    }
                                }
                            }
                            FilterChip(selected = gridView, onClick = { gridView = true }, label = { Text("شبكة") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(selected = !gridView, onClick = { gridView = false }, label = { Text("قائمة") })
                        }
                    }
                }
                when {
                    library is LibraryState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Column { CircularProgressIndicator(); Text("جارٍ تحميل المكتبة…") } }
                    library is LibraryState.Error -> item(span = { GridItemSpan(maxLineSpan) }) { Column { Text("تعذر تحميل المكتبة. حاول مجدداً.", color = MaterialTheme.colorScheme.error); Button(onClick = { retry++ }) { Text("إعادة المحاولة") } } }
                    filtered.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Text(if (entries.isEmpty()) "مكتبتك فارغة. أضف ملفات من جهازك للبدء." else "لا توجد ملفات مطابقة. غيّر البحث أو الفلتر.") }
                    else -> items(filtered, key = { it.uri }) { entry ->
                        MediaCard(entry = entry, uploading = uploading, showThumbnails = showThumbnails, onPlay = { playing = entry },
                            onFavorite = { scope.launch { try { repository.setFavorite(entry.uri, !entry.favorite); Toast.makeText(applicationContext, if (entry.favorite) "أُزيل من المفضلة" else "أُضيف إلى المفضلة", Toast.LENGTH_SHORT).show() } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "تعذر حفظ المفضلة. حاول مجدداً." } } },
                            onDelete = { pendingDelete = entry }, onCloud = { pendingUpload = entry })
                    }
                }
            }
        }
        if (showSettings) {
            AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("الإعدادات") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                        Checkbox(checked = showThumbnails, onCheckedChange = onThumbnails)
                        Text("عرض صور الفيديو المصغّرة")
                    }
                    Text("قد تعرض الصور محتوى الفيديو دون تصفية.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = notificationsEnabled, onCheckedChange = { enable ->
                            if (!enable) { notifications.enabled = false; notificationsEnabled = false }
                            else if (Build.VERSION.SDK_INT >= 33 && !notifications.allowed()) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else if (notifications.allowed()) { notifications.enabled = true; notificationsEnabled = true }
                            else message = "فعّل الإشعارات من إعدادات الجهاز."
                        })
                        Text("إشعار عند اكتمال الفحص")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = periodicEnabled, enabled = !scheduleChanging, onCheckedChange = { enable ->
                            if (enable) periodicPermission.launch(scanner.permissions()) else changeSchedule(false)
                        })
                        Text("فحص تلقائي كل ٢٤ ساعة تقريباً")
                    }
                    Text("يعتمد التوقيت على النظام، والفحص يشمل الوسائط المسموح بها فقط.", style = MaterialTheme.typography.bodySmall)
                    if (jobs.any { it.state == WorkInfo.State.RUNNING }) Text("جارٍ الفحص التلقائي…", style = MaterialTheme.typography.bodySmall)
                    else periodic.lastStatus()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
