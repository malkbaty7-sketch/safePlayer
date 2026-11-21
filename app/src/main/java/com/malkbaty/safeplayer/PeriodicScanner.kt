package com.malkbaty.safeplayer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** The job is explicitly enabled by the user; permission is rechecked for every run. */
class MediaScannerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val scanner = MediaScanner(applicationContext, MediaDatabase.get(applicationContext).mediaDao())
        val status = applicationContext.getSharedPreferences("periodic_scan_status", Context.MODE_PRIVATE)
        val access = scanner.access()
        if (!access.video && !access.audio) {
            status.edit().putString("last", "تعذر الفحص التلقائي: أذونات الوسائط غير متاحة. امنح الإذن ثم حاول الفحص يدوياً.").apply()
            return Result.failure()
        }
        return try {
            val result = scanner.scan(access, incremental = true)
            val note = if (!access.video || !access.audio || access.limitedVideo) " (الصلاحيات جزئية)" else ""
            status.edit().putString("last", "آخر فحص تلقائي${if (result.incremental) " تزايدي" else " كامل"}: فُحص ${result.found} ملف، أُضيف ${result.added}.$note").apply()
            ScanNotifications(applicationContext).show(result)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            status.edit().putString("last", "تعذر الفحص التلقائي بسبب تغير أذونات الوسائط. تحقق من إعدادات الجهاز.").apply()
            Result.failure()
        } catch (_: Exception) {
            status.edit().putString("last", "تعذر إكمال الفحص التلقائي؛ قد تكون أُضيفت بعض الملفات. ستتم إعادة المحاولة.").apply()
            Result.retry()
        }
    }
}

class PeriodicScanner(context: Context) {
    private val appContext = context.applicationContext
    private val manager = WorkManager.getInstance(appContext)
    val work = manager.getWorkInfosForUniqueWorkFlow(WORK_NAME)
    fun enable() = manager.enqueueUniquePeriodicWork(
        WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<MediaScannerWorker>(24, TimeUnit.HOURS).build()
    )
    fun disable() = manager.cancelUniqueWork(WORK_NAME)
    fun lastStatus(): String? = appContext.getSharedPreferences("periodic_scan_status", Context.MODE_PRIVATE).getString("last", null)
    companion object { const val WORK_NAME = "periodic_media_scan" }
}
