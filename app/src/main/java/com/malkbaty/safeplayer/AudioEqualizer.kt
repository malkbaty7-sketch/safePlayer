package com.malkbaty.safeplayer

import android.media.audiofx.Equalizer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.C

private data class EqualizerBand(val index: Short, val frequencyHz: Int, val level: Short)

/** The effect belongs to the playback session, not to the device's global audio output. */
@Composable
fun AudioEqualizer(audioSessionId: Int, visible: Boolean, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(false) }
    var effect by remember { mutableStateOf<Equalizer?>(null) }
    var bands by remember { mutableStateOf<List<EqualizerBand>>(emptyList()) }
    var range by remember { mutableStateOf(0.toShort() to 0.toShort()) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(audioSessionId, enabled) {
        var created: Equalizer? = null
        effect = null
        bands = emptyList()
        error = null
        if (enabled) {
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) {
                error = "جلسة الصوت غير متاحة بعد. حاول مجدداً أثناء التشغيل."
            } else {
                try {
                    val eq = Equalizer(0, audioSessionId)
                    created = eq
                    val limits = eq.bandLevelRange
                    require(limits.size >= 2 && limits[0] < limits[1])
                    val loaded = (0 until eq.numberOfBands.toInt()).map { i ->
                        val index = i.toShort()
                        EqualizerBand(index, eq.getCenterFreq(index) / 1000, eq.getBandLevel(index))
                    }
                    require(loaded.isNotEmpty())
                    eq.enabled = true
                    range = limits[0] to limits[1]
                    bands = loaded
                    effect = eq
                } catch (_: Exception) {
                    try { created?.release() } catch (_: RuntimeException) { }
                    created = null
                    error = "المعادل غير متاح على هذا الجهاز أو لجلسة الصوت الحالية."
                }
            }
        }
        onDispose {
            effect = null
            try { created?.release() } catch (_: RuntimeException) { }
        }
    }

    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("معادل الصوت") },
            text = {
                Column {
                    Text("الضبط مؤقت لهذه الجلسة وقد يختلف حسب الجهاز. لا يغيّر مستوى صوت الجهاز.")
                    TextButton(onClick = { enabled = !enabled }) { Text(if (enabled) "إيقاف المعادل" else "تشغيل المعادل") }
                    error?.let { Text(it) }
                    if (error != null && enabled) {
                        Text("أوقف المعادل ثم أعد تشغيله للمحاولة مجدداً.")
                    }
                    LazyColumn {
                        items(bands.size) { i ->
                            val band = bands[i]
                            Text("${band.frequencyHz} Hz — ${band.level / 100f} dB")
                            Slider(
                                value = band.level.toFloat().coerceIn(range.first.toFloat(), range.second.toFloat()),
                                valueRange = range.first.toFloat()..range.second.toFloat(),
                                onValueChange = { value ->
                                    try {
                                        val eq = effect ?: return@Slider
                                        eq.setBandLevel(band.index, value.toInt().toShort())
                                        bands = bands.map { if (it.index == band.index) it.copy(level = eq.getBandLevel(band.index)) else it }
                                    } catch (_: RuntimeException) {
                                        error = "تعذر ضبط المعادل. أوقفه وحاول مجدداً."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
        )
    }
}
