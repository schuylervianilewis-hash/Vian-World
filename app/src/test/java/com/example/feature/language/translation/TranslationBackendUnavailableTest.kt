package com.example.feature.language.translation

import com.example.feature.language.LanguageFeatureState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBackendUnavailableTest {

    @Test
    fun productionBackendIsUnavailableButRetainsLanguageCompatibility() {
        val backend = UnavailableTranslationBackend()

        assertFalse(backend.isAvailable())
        assertTrue(backend.getSupportedLanguageCodes().contains("en"))
        assertEquals("es", backend.normalizeLanguageTag("es-MX"))
        assertNull(backend.normalizeLanguageTag("xx-ZZ"))
        assertNotNull(backend.getDisplayName("en"))
    }

    @Test
    fun modelAndTranslationOperationsFailWithoutFabricatingResults() = runBlocking {
        val backend = UnavailableTranslationBackend()

        assertTrue(backend.getDownloadedLanguageCodes().isFailure)
        assertTrue(backend.downloadModel("es", requireWifi = true).isFailure)
        assertTrue(backend.deleteModel("es").isFailure)
        assertTrue(backend.identifyLanguageTag("hello").isFailure)
        assertTrue(backend.translate("hello", "en", "es").isFailure)
    }

    @Test
    fun featureSwitchDefaultsKeepDictionaryOnAndTranslationOff() {
        val defaults = LanguageFeatureState()

        assertTrue(defaults.dictionaryEnabled)
        assertFalse(defaults.translationEnabled)
    }
}
