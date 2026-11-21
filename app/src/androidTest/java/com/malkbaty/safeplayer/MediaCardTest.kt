package com.malkbaty.safeplayer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardActionsHaveLabelsAndInvokeHandlers() {
        var played = false
        var favorited = false
        var savedForLater = false
        compose.setContent {
            SafePlayerTheme(Palette.FOREST, false) {
                MediaCard(MediaEntry("test", "Music.mp3", "audio", 0), false,
                    onPlay = { played = true }, onFavorite = { favorited = true }, onWatchLater = { savedForLater = true }, onDelete = {}, onCloud = {})
            }
        }
        compose.onNodeWithContentDescription("تشغيل Music.mp3").performClick()
        compose.onNodeWithContentDescription("إضافة إلى المفضلة: Music.mp3").performClick()
        compose.onNodeWithContentDescription("إضافة إلى المشاهدة لاحقاً: Music.mp3").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(played); assertTrue(favorited); assertTrue(savedForLater) }
    }
}
