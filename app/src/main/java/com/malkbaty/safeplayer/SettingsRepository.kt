package com.malkbaty.safeplayer

import android.content.Context

data class Appearance(val palette: Palette = Palette.FOREST, val dark: Boolean = false)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun showThumbnails(): Boolean = prefs.getBoolean("show_thumbnails", true)
    fun setShowThumbnails(show: Boolean) { prefs.edit().putBoolean("show_thumbnails", show).apply() }
    fun appearance(): Appearance = Appearance(
        Palette.entries.find { it.name == prefs.getString("palette", "FOREST") } ?: Palette.FOREST,
        prefs.getBoolean("dark", false)
    )
    fun saveAppearance(appearance: Appearance) {
        prefs.edit().putString("palette", appearance.palette.name).putBoolean("dark", appearance.dark).apply()
    }
}
