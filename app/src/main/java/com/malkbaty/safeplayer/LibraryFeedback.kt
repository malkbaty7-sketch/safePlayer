package com.malkbaty.safeplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

internal enum class MessageTone { INFO, SUCCESS, ERROR }

internal data class AppMessage(
    val text: String,
    val tone: MessageTone = MessageTone.INFO,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

@Composable
internal fun LibraryLoadingPanel() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp)
            .semantics(mergeDescendants = true) { contentDescription = "جارٍ تحميل المكتبة" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text("جارٍ تحميل المكتبة…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LibraryErrorPanel(onRetry: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "تعذر تحميل المكتبة",
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "لم نتمكن من قراءة مكتبتك المحلية. تحقق من مساحة التخزين ثم أعد المحاولة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) { Text("إعادة المحاولة") }
        }
    }
}

@Composable
internal fun LibraryEmptyPanel(hasFilters: Boolean, onClearFilters: () -> Unit, onAddFiles: () -> Unit) {
    val title = if (hasFilters) "لا توجد ملفات مطابقة" else "مكتبتك فارغة"
    val detail = if (hasFilters) "جرّب كلمات بحث أخرى أو أزل الفلاتر للعودة إلى كل الملفات."
    else "اختر ملفاتك أو افحص وسائط الجهاز للبدء. لا يُحذف الملف الأصلي عند إزالته من المكتبة."
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "♫",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = if (hasFilters) "لا نتائج" else "مكتبة فارغة" }
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.semantics { heading() }
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = if (hasFilters) onClearFilters else onAddFiles) {
                Text(if (hasFilters) "مسح البحث والفلاتر" else "اختر ملفات")
            }
        }
    }
}

@Composable
internal fun AppMessageBar(message: AppMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val containerColor = when (message.tone) {
        MessageTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        MessageTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        MessageTone.INFO -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (message.tone) {
        MessageTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        MessageTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        MessageTone.INFO -> MaterialTheme.colorScheme.onSurface
    }
    Snackbar(
        modifier = modifier.padding(16.dp),
        action = {
            TextButton(onClick = {
                message.onAction?.invoke()
                onDismiss()
            }) { Text(message.actionLabel ?: "إغلاق", color = contentColor) }
        },
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Text(message.text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
    }
}
