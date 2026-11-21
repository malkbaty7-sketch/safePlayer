package com.malkbaty.safeplayer

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Thumbnails are best effort and local-only; SAF providers and older devices may not supply them. */
@Composable
fun MediaCard(entry: MediaEntry, uploading: Boolean, showThumbnails: Boolean = true, onPlay: () -> Unit, onFavorite: () -> Unit, onWatchLater: () -> Unit, onDelete: () -> Unit, onCloud: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(null, entry.uri, entry.type, entry.sizeBytes, entry.durationMs, showThumbnails) {
        value = null
        if (showThumbnails && entry.type == "video" && Build.VERSION.SDK_INT >= 29) {
            value = ThumbnailLoader.get(context).load(entry)
        }
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            val bitmap = if (showThumbnails) thumbnail else null
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(if (entry.type == "video") "▶" else "♫", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content))
            Text(if (entry.type == "video") "فيديو" else "صوت", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onPlay, modifier = Modifier.semantics { contentDescription = "تشغيل ${entry.title}" }) { Text("تشغيل") }
                TextButton(onClick = onFavorite, modifier = Modifier.semantics { contentDescription = "${if (entry.favorite) "إزالة من المفضلة" else "إضافة إلى المفضلة"}: ${entry.title}" }) { Text(if (entry.favorite) "إزالة المفضلة" else "مفضلة") }
                TextButton(onClick = onWatchLater, modifier = Modifier.semantics { contentDescription = "${if (entry.watchLater) "إزالة من المشاهدة لاحقاً" else "إضافة إلى المشاهدة لاحقاً"}: ${entry.title}" }) { Text(if (entry.watchLater) "إزالة من لاحقاً" else "شاهد لاحقاً") }
                TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "إزالة ${entry.title} من المكتبة" }) { Text("حذف") }
                if (entry.type == "video") TextButton(enabled = !uploading, onClick = onCloud, modifier = Modifier.semantics { contentDescription = "حفظ بيانات ${entry.title} سحابياً" }) { Text("حفظ سحابي") }
            }
        }
    }
}
