package com.photoconnect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoconnect.utils.AppLocaleManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationTest {

    @Test
    fun testSupportedLanguagesCoverage() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val supportedCount = AppLocaleManager.supportedLanguages.size
        
        // Assert that we have at least 20 languages supported for niche dialects
        assert(supportedCount >= 20) { "Not enough niche local dialects supported!" }
    }
}
