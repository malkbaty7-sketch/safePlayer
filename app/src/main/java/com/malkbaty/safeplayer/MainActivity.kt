package com.malkbaty.safeplayer

import android.Manifest
import android.net.Uri
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
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
            SideEffect {
                if (Build.VERSION.SDK_INT < 35) {
                    val barColor = if (dark) android.graphics.Color.rgb(20, 18, 24) else android.graphics.Color.rgb(248, 250, 246)
                    @Suppress("DEPRECATION")
                    window.statusBarColor = barColor
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = barColor
                }
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            SafePlayerTheme(palette, dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Box(Modifier.fillMaxSize().then(if (inPip) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
                            SafePlayerApp(repository, palette, dark, showThumbnails, onThumbnails = { show ->
                                showThumbnails = show
                                settings.setShowThumbnails(show)
                        if (!show) ThumbnailLoader.clearIfCreated()
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
        val settings = remember { SettingsRepository(applicationContext) }
        var showWelcome by remember { mutableStateOf(settings.showWelcome()) }
        var showSettings by remember { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var type by rememberSaveable { mutableStateOf("all") }
        var favorites by rememberSaveable { mutableStateOf(false) }
        var watchLater by rememberSaveable { mutableStateOf(false) }
        var history by rememberSaveable { mutableStateOf(false) }
        var sort by rememberSaveable { mutableStateOf(MediaSort.NEWEST) }
        var showSort by remember { mutableStateOf(false) }
        var gridView by rememberSaveable { mutableStateOf(true) }
        var playing by remember { mutableStateOf<MediaEntry?>(null) }
        var isNetworkStream by remember { mutableStateOf(false) }
        var showStreamDialog by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<AppMessage?>(null) }
        var pendingDelete by remember { mutableStateOf<MediaEntry?>(null) }
        var scanning by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf(false) }
        var checkingMissing by remember { mutableStateOf(false) }
        var missingEntries by remember { mutableStateOf<List<MediaEntry>?>(null) }
        val notifications = remember { ScanNotifications(applicationContext) }
        var notificationsEnabled by remember { mutableStateOf(notifications.enabled && notifications.allowed()) }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifications.enabled = granted && notifications.allowed()
            notificationsEnabled = notifications.enabled
            message = if (notificationsEnabled) AppMessage("تم تفعيل إشعارات الفحص.", MessageTone.SUCCESS) else AppMessage("لم يُمنح إذن الإشعارات. يمكن تفعيله من إعدادات الجهاز.", MessageTone.ERROR)
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
                    message = if (enable) AppMessage("تمت جدولة الفحص التلقائي؛ يحدد النظام وقت التنفيذ.", MessageTone.SUCCESS) else AppMessage("تم إيقاف الفحص التلقائي.")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = AppMessage("تعذر تحديث جدول الفحص. حاول مجدداً.", MessageTone.ERROR) }
                finally { scheduleChanging = false }
            }
        }
        val periodicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val access = scanner.access()
            if (access.video || access.audio) changeSchedule(true)
            else message = AppMessage("لم يُمنح إذن الوسائط؛ لم تُفعّل الفهرسة التلقائية.", MessageTone.ERROR)
        }
        fun startScan() {
            val access = scanner.access()
            if (!access.video && !access.audio) {
                message = AppMessage("لم يُمنح إذن الوصول للوسائط. يمكنك منح الإذن من إعدادات التطبيق أو إضافة ملفات يدوياً.", MessageTone.ERROR)
                return
            }
            scope.launch {
                scanning = true
                message = null
                try {
                    val result = scanner.scan(access)
                    notifications.show(result)
                    val scopeNote = if (!access.video || !access.audio || access.limitedVideo) " الفحص يشمل الوسائط المسموح بها فقط." else ""
                    message = AppMessage("اكتمل الفحص: ${result.found} ملف، أُضيف منها ${result.added}.$scopeNote", MessageTone.SUCCESS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = AppMessage("تعذر إكمال فحص الوسائط؛ قد تكون بعض الملفات أُضيفت. تحقق من الصلاحيات وحاول مجدداً.", MessageTone.ERROR)
                } finally { scanning = false }
            }
        }
        fun checkMissing() {
            if (library !is LibraryState.Ready) { message = AppMessage("انتظر تحميل المكتبة ثم حاول مجدداً.", MessageTone.ERROR); return }
            scope.launch {
                checkingMissing = true
                try { missingEntries = scanner.findUnavailable(entries) }
                catch (e: CancellationException) { throw e }
                catch (_: MediaScanner.FullAccessRequired) { message = AppMessage("تتطلب المراجعة إذن الوصول الكامل للفيديو والصوت. افتح إعدادات الجهاز واسمح بهما ثم حاول مجدداً.", MessageTone.ERROR) }
                catch (_: Exception) { message = AppMessage("تعذرت مراجعة MediaStore؛ لم تُزل أي عناصر. تحقق من الوصول وحاول مجدداً.", MessageTone.ERROR) }
                finally { checkingMissing = false }
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startScan() }
        fun importFiles(uris: List<Uri>) {
            if (uris.isEmpty()) return
            if (importing) { message = AppMessage("جارٍ إضافة ملفات أخرى، انتظر حتى تنتهي."); return }
            importing = true
            scope.launch {
                try {
                    val result = repository.importDocuments(uris)
                    message = if (result.failed > 0) AppMessage(
                        text = importResultMessage(result.added, result.failed),
                        tone = MessageTone.ERROR,
                        actionLabel = "إعادة المحاولة",
                        onAction = { importFiles(result.failedUris) }
                    ) else AppMessage(importResultMessage(result.added, 0), MessageTone.SUCCESS)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    message = AppMessage("تعذرت إضافة الملفات. تحقق من الصلاحيات ومساحة التخزين ثم حاول مجدداً.", MessageTone.ERROR, "إعادة المحاولة", onAction = { importFiles(uris) })
                } finally { importing = false }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importFiles(uris) }
        val current = playing
        Box(Modifier.fillMaxSize()) {
        if (current != null) {
            val stream = isNetworkStream
            BackHandler { playing = null }
            PlayerScreen(this, current, inPip, onBack = { playing = null }, onChooseAnother = { playing = null; picker.launch(arrayOf("video/*", "audio/*")) }, onStarted = {
                if (!stream) lifecycleScope.launch { try { repository.markPlayed(current.uri, System.currentTimeMillis()) } catch (e: CancellationException) { throw e } catch (_: Exception) { message = AppMessage("تعذر حفظ سجل التشغيل.", MessageTone.ERROR) } }
            }, onPosition = { position ->
                // Activity lifecycle scope survives disposal of the player composable.
                if (!stream) lifecycleScope.launch { try { repository.savePosition(current.uri, position) } catch (_: Exception) { message = AppMessage("تعذر حفظ موضع التشغيل.", MessageTone.ERROR) } }
            })
        } else {
            val filtered = remember(entries, query, type, favorites, sort, watchLater, history) { searchLibrary(entries, LibraryQuery(query, type, favorites, sort, watchLater, history)) }
            val recent = remember(entries) { lastWatched(entries) }
            val highlights = remember(entries) { libraryHighlights(entries) }
            val filtersActive = query.isNotBlank() || type != "all" || favorites || watchLater || history
            LazyVerticalGrid(
                columns = if (gridView) GridCells.Adaptive(230.dp) else GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("SafePlayer", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        Text("مكتبتك الخاصة، على جهازك", style = MaterialTheme.typography.bodyMedium)
                        if (showWelcome) {
                            ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("مرحباً بك في SafePlayer", style = MaterialTheme.typography.titleMedium)
                                    Text("أضف ملفات تختارها بنفسك، أو افحص وسائط الجهاز بعد منح الإذن. يمكنك رفض الإذن واستخدام إضافة الملفات بدلاً منه.")
                                    TextButton(onClick = { showWelcome = false; settings.dismissWelcome() }) { Text("تخطي المقدمة") }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !importing, onClick = { picker.launch(arrayOf("video/*", "audio/*")) }) {
                                if (importing) { CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                                Text(if (importing) "جارٍ الإضافة…" else "إضافة ملفات")
                            }
                            OutlinedButton(onClick = { favorites = !favorites }) { Text(if (favorites) "كل المكتبة" else "المفضلة") }
                            OutlinedButton(onClick = { watchLater = !watchLater }) { Text(if (watchLater) "إلغاء لاحقاً" else "المشاهدة لاحقاً") }
                            OutlinedButton(onClick = { history = !history; if (history) sort = MediaSort.LAST_PLAYED }) { Text(if (history) "إلغاء السجل" else "سجل التشغيل") }
                            TextButton(onClick = { showStreamDialog = true }) { Text("بث الشبكة") }
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
                        if (filtersActive) {
                            Text("الفلاتر النشطة: ${if (query.isNotBlank()) "بحث · " else ""}${if (type != "all") (if (type == "video") "فيديو · " else "صوتيات · ") else ""}${if (favorites) "المفضلة · " else ""}${if (watchLater) "المشاهدة لاحقاً · " else ""}${if (history) "سجل التشغيل" else ""}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { query = ""; type = "all"; favorites = false; watchLater = false; history = false }) { Text("مسح البحث والفلاتر") }
                        }
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
                if (library is LibraryState.Ready && !filtersActive) {
                    recent?.let { recent ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("استكمال المشاهدة", style = MaterialTheme.typography.titleMedium)
                                    Text(recent.title, maxLines = 1)
                                    TextButton(onClick = { isNetworkStream = false; playing = recent }) { Text("استكمال من ${formatPlaybackTime(recent.positionMs)}") }
                                }
                            }
                        }
                    }
                    if (highlights.recent.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryShortcutSection("أُضيف مؤخراً", highlights.recent) { isNetworkStream = false; playing = it }
                    }
                    if (highlights.favorites.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryShortcutSection("المفضلة", highlights.favorites) { isNetworkStream = false; playing = it }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Text("المكتبة", style = MaterialTheme.typography.titleLarge) }
                when {
                    library is LibraryState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { LibraryLoadingPanel() }
                    library is LibraryState.Error -> item(span = { GridItemSpan(maxLineSpan) }) { LibraryErrorPanel(onRetry = { retry++ }) }
                    filtered.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryEmptyPanel(hasFilters = filtersActive, onClearFilters = {
                            query = ""; type = "all"; favorites = false; watchLater = false; history = false
                        }, onAddFiles = { picker.launch(arrayOf("video/*", "audio/*")) })
                    }
                    else -> items(filtered, key = { it.uri }) { entry ->
                        MediaCard(entry = entry, uploading = uploading, showThumbnails = showThumbnails, onPlay = { isNetworkStream = false; playing = entry },
                            onFavorite = { scope.launch { try { repository.setFavorite(entry.uri, !entry.favorite); Toast.makeText(applicationContext, if (entry.favorite) "أُزيل من المفضلة" else "أُضيف إلى المفضلة", Toast.LENGTH_SHORT).show() } catch (e: CancellationException) { throw e } catch (_: Exception) { message = AppMessage("تعذر حفظ المفضلة. حاول مجدداً.", MessageTone.ERROR) } } },
                            onWatchLater = { scope.launch { try { repository.setWatchLater(entry.uri, !entry.watchLater); message = AppMessage(if (entry.watchLater) "أُزيل من المشاهدة لاحقاً." else "أُضيف إلى المشاهدة لاحقاً.", MessageTone.SUCCESS) } catch (e: CancellationException) { throw e } catch (_: Exception) { message = AppMessage("تعذر تحديث قائمة المشاهدة لاحقاً. حاول مجدداً.", MessageTone.ERROR) } } },
                            onDelete = { pendingDelete = entry }, onCloud = { pendingUpload = entry })
                    }
                }
            }
        }
        message?.let { notice ->
            AppMessageBar(notice, onDismiss = { message = null }, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
        }
        if (showStreamDialog) NetworkStreamDialog(onDismiss = { showStreamDialog = false }, onPlay = { url ->
            showStreamDialog = false
            isNetworkStream = true
            playing = MediaEntry(uri = url, title = "بث عبر الشبكة", type = "video", addedAt = 0)
        })
        if (showSettings) {
            AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("الإعدادات") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("إدارة المكتبة", style = MaterialTheme.typography.titleMedium)
                    Text("راجع عناصر MediaStore غير الظاهرة حالياً. لا تُزال الملفات تلقائياً.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !checkingMissing, onClick = { showSettings = false; checkMissing() }) { Text("مراجعة العناصر غير المتاحة") }
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
                            else message = AppMessage("فعّل الإشعارات من إعدادات الجهاز.", MessageTone.ERROR)
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
        missingEntries?.let { missing ->
            AlertDialog(onDismissRequest = { if (!checkingMissing) missingEntries = null },
                title = { Text("عناصر MediaStore غير الظاهرة") },
                text = {
                    Column {
                        Text("قد تكون الملفات محذوفة أو على وحدة تخزين غير متصلة؛ لا يمكن الجزم بسبب غيابها. إزالة سجل تحذف مفضلته وسجله وموضعه من المكتبة فقط، ولا تحذف الملف الأصلي. تقتصر المراجعة على عناوين MediaStore القياسية، ولا تشمل عناوين مستندات SAF.")
                        if (checkingMissing) { CircularProgressIndicator(); Text("جارٍ التحقق…") }
                        else if (missing.isEmpty()) Text("لا توجد عناصر غير ظاهرة في النطاق المفحوص.")
                        else androidx.compose.foundation.lazy.LazyColumn {
                            items(missing.size, key = { missing[it].uri }) { index ->
                                val entry = missing[index]
                                Column {
                                    Text(entry.title, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                                    TextButton(onClick = {
                                        scope.launch {
                                            checkingMissing = true
                                            try {
                                                val stillMissing = scanner.findUnavailable(listOf(entry)).any { it.uri == entry.uri }
                                                if (stillMissing) {
                                                    repository.remove(entry.uri)
                                                    missingEntries = missingEntries?.filterNot { it.uri == entry.uri }
                                                    message = AppMessage("أُزيل السجل من المكتبة دون حذف الملف الأصلي.", MessageTone.SUCCESS)
                                                } else {
                                                    missingEntries = missingEntries?.filterNot { it.uri == entry.uri }
                                                    message = AppMessage("أصبح العنصر ظاهراً مجدداً؛ لم تتم إزالته.")
                                                }
                                            } catch (e: CancellationException) { throw e }
                                            catch (_: MediaScanner.FullAccessRequired) { message = AppMessage("تغيّرت أذونات الوسائط؛ لم يُزل السجل.", MessageTone.ERROR); missingEntries = null }
                                            catch (_: Exception) { message = AppMessage("تعذر التحقق أو إزالة السجل. حاول مجدداً.", MessageTone.ERROR); missingEntries = null }
                                            finally { checkingMissing = false }
                                        }
                                    }) { Text("إزالة هذا السجل من المكتبة") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(enabled = !checkingMissing, onClick = { missingEntries = null }) { Text("إغلاق") } })
        }
        if (showCloud) CloudDialog(cloud, onClose = { showCloud = false })
        pendingUpload?.let { entry ->
            AlertDialog(onDismissRequest = { pendingUpload = null }, title = { Text("حفظ بيانات الفيديو سحابياً؟") },
                text = { Text("سيُرسل العنوان وحالة المفضلة إلى Firebase، دون الملف أو مساره. الحساب المجهول مرتبط بهذا التثبيت وقد تفقد الوصول عند مسح بياناته. قد يؤدي التكرار إلى إنشاء نسخ متعددة.") },
                confirmButton = { TextButton(onClick = {
                    pendingUpload = null
                    scope.launch {
                        uploading = true; message = AppMessage("جارٍ حفظ بيانات الفيديو سحابياً…")
                        try { cloud.save(entry); message = AppMessage("تم حفظ بيانات الفيديو سحابياً.", MessageTone.SUCCESS) }
                        catch (_: TimeoutCancellationException) { message = AppMessage("انتهت مهلة الحفظ وقد يكتمل لاحقاً. تحقق من السحابة قبل إعادة المحاولة.", MessageTone.ERROR) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = AppMessage("تعذر الحفظ السحابي. تحقق من إعداد Firebase والاتصال وقواعد الأمان وحاول مجدداً.", MessageTone.ERROR) }
                        finally { uploading = false }
                    }
                }) { Text("إرسال") } }, dismissButton = { TextButton(onClick = { pendingUpload = null }) { Text("إلغاء") } })
        }
        pendingDelete?.let { entry ->
            AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("إزالة من المكتبة؟") }, text = { Text("لن يُحذف الملف الأصلي من جهازك، وقد يظهر مجدداً عند فحص الوسائط.") }, confirmButton = { TextButton(onClick = { pendingDelete = null; scope.launch { try { repository.remove(entry.uri) } catch (_: Exception) { message = AppMessage("تعذرت إزالة الملف. حاول مجدداً.", MessageTone.ERROR) } } }) { Text("إزالة") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("إلغاء") } })
        }
    }

}

@Composable
private fun LibraryShortcutSection(title: String, entries: List<MediaEntry>, onPlay: (MediaEntry) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEach { entry ->
                OutlinedButton(onClick = { onPlay(entry) }, modifier = Modifier.widthIn(max = 220.dp)) {
                    Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content))
                }
            }
        }
    }
}
