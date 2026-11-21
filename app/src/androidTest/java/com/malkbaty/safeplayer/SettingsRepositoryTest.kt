package com.malkbaty.safeplayer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @After fun cleanup() { context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun thumbnailPreferencePersists() {
        val settings = SettingsRepository(context)
        settings.setShowThumbnails(false)
        assertFalse(SettingsRepository(context).showThumbnails())
        settings.setShowThumbnails(true)
        assertTrue(SettingsRepository(context).showThumbnails())
    }
}
