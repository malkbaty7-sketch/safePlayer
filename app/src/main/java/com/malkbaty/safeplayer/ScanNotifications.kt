package com.malkbaty.safeplayer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class ScanNotifications(private val context: Context) {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("scan", false)
        set(value) { prefs.edit().putBoolean("scan", value).apply() }
    fun allowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun show(result: MediaScanner.Result) {
        if (!enabled || !allowed()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("scan", "نتائج الفحص", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, "scan")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("اكتمل فحص المكتبة")
            .setContentText("تم العثور على ${result.found} ملف؛ أُضيف ${result.added}.")
            .setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(1, notification) } catch (_: SecurityException) { }
    }
}
