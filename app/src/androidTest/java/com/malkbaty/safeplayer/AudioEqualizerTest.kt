package com.malkbaty.safeplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioEqualizerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableSessionCanBeTurnedOffAndRetried() {
        compose.setContent { AudioEqualizer(C.AUDIO_SESSION_ID_UNSET, true) {} }
        compose.onNodeWithText("تشغيل المعادل").performClick()
        compose.onNodeWithText("جلسة الصوت غير متاحة بعد. حاول مجدداً أثناء التشغيل.").assertIsDisplayed()
        compose.onNodeWithText("إيقاف المعادل").performClick()
        compose.onNodeWithText("تشغيل المعادل").assertIsDisplayed()
        compose.onNodeWithText("جلسة الصوت غير متاحة بعد. حاول مجدداً أثناء التشغيل.").assertDoesNotExist()
    }
}
