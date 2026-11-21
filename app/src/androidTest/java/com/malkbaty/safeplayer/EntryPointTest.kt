package com.malkbaty.safeplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EntryPointTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun dismissWelcomeIfShown() {
        if (compose.onAllNodes(androidx.compose.ui.test.hasText("تخطي المقدمة")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("تخطي المقدمة").performScrollTo().performClick()
        }
    }

    @Test fun librarySettingsAndDismissReturnToLibrary() {
        compose.onNodeWithText("SafePlayer").assertIsDisplayed()
        compose.onNodeWithText("الإعدادات").performScrollTo().performClick()
        compose.onNodeWithText("الوضع الداكن").assertIsDisplayed()
        compose.onNodeWithText("تم").performClick()
        compose.onNodeWithText("البحث في المكتبة").assertIsDisplayed()
    }

    @Test fun searchCanBeCleared() {
        compose.onNodeWithText("البحث في المكتبة").performScrollTo().performTextInput("no-match-987654321")
        compose.onNodeWithText("مسح البحث والفلاتر").performScrollTo().performClick()
        compose.onNodeWithText("مسح البحث والفلاتر").assertDoesNotExist()
    }

    @Test fun streamDialogRejectsInsecureUrlAndCanBeDismissed() {
        compose.onNodeWithText("بث الشبكة").performScrollTo().performClick()
        compose.onNodeWithText("رابط البث").performTextInput("http://example.com/video.mp4")
        compose.onNodeWithText("تشغيل").performClick()
        compose.onNodeWithText("أدخل رابط HTTPS صالحاً دون اسم مستخدم أو كلمة مرور أو جزء URL.").assertIsDisplayed()
        compose.onNodeWithText("إلغاء").performClick()
        compose.onNodeWithText("البحث في المكتبة").assertIsDisplayed()
    }
}
