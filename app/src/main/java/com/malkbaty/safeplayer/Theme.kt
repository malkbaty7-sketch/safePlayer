package com.malkbaty.safeplayer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Persist stable keys rather than localized labels.
enum class Palette(val label: String, val light: Color, val dark: Color) {
    FOREST("الغابة", Color(0xFF245E45), Color(0xFFA7D4B3)),
    OCEAN("المحيط", Color(0xFF006779), Color(0xFF80D4E9)),
    SUNSET("الغروب", Color(0xFF99452C), Color(0xFFFFB5A0)),
    ROYAL("البنفسجي", Color(0xFF704184), Color(0xFFE4B7F2))
}

@Composable
fun SafePlayerTheme(palette: Palette, dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(primary = palette.dark) else lightColorScheme(primary = palette.light)
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(medium = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 8.dp, bottomEnd = 8.dp)),
        content = content
    )
}
