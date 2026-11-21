package com.malkbaty.safeplayer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ThemeContrastTest {
    @get:Rule val compose = createComposeRule()

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    @Test fun primaryAndSurfaceTextMeetNormalTextContrast() {
        val results = mutableListOf<Triple<String, Float, Float>>()
        compose.setContent {
            for (palette in Palette.entries) for (dark in listOf(false, true)) {
                SafePlayerTheme(palette, dark) {
                    val colors = MaterialTheme.colorScheme
                    results.add(Triple("${palette.name} dark=$dark", contrast(colors.primary, colors.onPrimary), contrast(colors.surface, colors.onSurface)))
                }
            }
        }
        compose.runOnIdle {
            assertTrue("all palettes checked", results.size >= Palette.entries.size * 2)
            results.forEach { (name, primary, surface) ->
                assertTrue("$name primary: $primary", primary >= 4.5f)
                assertTrue("$name surface: $surface", surface >= 4.5f)
            }
        }
    }
}
