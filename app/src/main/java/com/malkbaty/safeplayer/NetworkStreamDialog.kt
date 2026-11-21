package com.malkbaty.safeplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

@Composable
fun NetworkStreamDialog(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("تشغيل عبر الشبكة") }, text = {
        Column {
            Text("استخدم رابط HTTPS لمصدر موثوق. سيتصل جهازك بالخادم؛ لا يُحفظ الرابط في المكتبة.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = value, onValueChange = { value = it; error = null }, label = { Text("رابط البث") },
                placeholder = { Text("https://example.com/video.mp4") }, singleLine = true, isError = error != null,
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Text("HTTPS فقط؛ يدعم MP4 وHLS وDASH وفق صيغة المصدر والجهاز.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val valid = validateStreamUrl(value)
            if (valid == null) error = "أدخل رابط HTTPS صالحاً دون اسم مستخدم أو كلمة مرور أو جزء URL."
            else onPlay(valid)
        }) { Text("تشغيل") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}
