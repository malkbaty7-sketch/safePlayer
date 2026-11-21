package com.malkbaty.safeplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CloudDialog(repository: CloudRepository, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var videos by remember { mutableStateOf<List<CloudVideo>>(emptyList()) }
    var selected by remember { mutableStateOf<CloudVideo?>(null) }
    fun refresh() {
        scope.launch {
            loading = true; error = null
            try { videos = repository.list() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "تعذر تحميل السحابة. تحقق من إعداد Firebase والإنترنت وقواعد الأمان، ثم أعد المحاولة." }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    AlertDialog(onDismissRequest = onClose, title = { Text("الفيديوهات المحفوظة سحابياً") }, text = {
        Column {
            Text("عناوين ومفضلة فقط؛ لا تُرفع الملفات ولا يمكن تشغيلها من هنا. الحساب المجهول مرتبط بهذا التثبيت.", style = MaterialTheme.typography.bodySmall)
            if (loading) { CircularProgressIndicator(); Text("جارٍ الاتصال…") }
            else if (error != null) { Text(error!!, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh() }) { Text("إعادة المحاولة") } }
            else if (videos.isEmpty()) Text("لا توجد فيديوهات محفوظة في السحابة.")
            else LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(videos, key = { it.id }) { video ->
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(video.title, modifier = Modifier.weight(1f), maxLines = 2)
                        TextButton(onClick = { selected = video }) { Text("حذف") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } })
    selected?.let { video ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("حذف النسخة السحابية؟") }, text = { Text("لن يُحذف الملف من جهازك.") }, confirmButton = {
            TextButton(onClick = {
                selected = null
                scope.launch {
                    loading = true; error = null
                    try { repository.remove(video.id); videos = videos.filterNot { it.id == video.id } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "تعذر الحذف. تحقق من الاتصال وحاول مجدداً." }
                    finally { loading = false }
                }
            }) { Text("حذف") }
        }, dismissButton = { TextButton(onClick = { selected = null }) { Text("إلغاء") } })
    }
}
