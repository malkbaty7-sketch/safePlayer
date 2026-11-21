package com.malkbaty.safeplayer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardActionsHaveLabelsAndInvokeHandlers() {
        var played = false
        var favorited = false
        compose.setContent {
            SafePlayerTheme(Palette.FOREST, false) {
                MediaCard(MediaEntry("test", "Music.mp3", "audio", 0), false,
                    onPlay = { played = true }, onFavorite = { favorited = true }, onDelete = {}, onCloud = {})
            }
        }
        compose.onNodeWithContentDescription("تشغيل Music.mp3").performClick()
        compose.onNodeWithContentDescription("إضافة إلى المفضلة: Music.mp3").performClick()
        compose.runOnIdle { assertTrue(played); assertTrue(favorited) }
    }
}
