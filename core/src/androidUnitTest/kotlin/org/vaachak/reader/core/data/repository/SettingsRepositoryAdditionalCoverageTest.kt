package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.domain.model.CoverAspectRatio
import org.vaachak.reader.core.domain.model.DitheringMode
import org.vaachak.reader.core.domain.model.ReaderPreferences
import org.vaachak.reader.core.domain.model.ThemeMode
import java.util.UUID

/** Exercises preference defaults, malformed saved values and both sides of optional setters. */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryAdditionalCoverageTest {
    private val vaultId = MutableStateFlow("repo_extended_${UUID.randomUUID()}")
    private val vault = mockk<VaultRepository>()
    private val secrets = mockk<PrivateGatewaySecrets>()
    private val repository: SettingsRepository

    private val ai = AiCredentialRuntimeFixture()

    init {
        every { vault.activeVaultId } returns vaultId
        every { secrets.read(any()) } returns ""
        every { secrets.write(any(), any()) } returns Unit
        every { secrets.clear(any()) } returns Unit
        configureAiVault(vault, vaultId)
        configureLegacyAiStorage(secrets, mutableMapOf<String, String>())
        repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets, ai.runtime)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun preferences(): DataStore<Preferences> {
        repository.geminiKey.first()
        val field = SettingsRepository::class.java.getDeclaredField("activeVaults")
        field.isAccessible = true
        return (field.get(repository) as MutableMap<String, DataStore<Preferences>>).getValue(vaultId.value)
    }

    @Test fun globalDefaultsAndUpdatesAreScopedToActiveVault() = runBlocking {
        assertTrue(repository.isOfflineModeEnabled.first())
        assertFalse(repository.isAiEnabled.first())
        assertEquals(ThemeMode.E_INK, repository.themeMode.first())
        assertTrue(repository.isAutoSaveRecapsEnabled.first())
        repository.setOfflineMode(false)
        repository.setAiEnabled(true)
        repository.setAutoSaveRecaps(false)
        repository.setThemeMode(ThemeMode.LIGHT)
        repository.setContrast(0.8f)
        repository.setDictionaryFolder("content://test/dictionary")
        repository.setUseEmbeddedDictionary(true)
        assertFalse(repository.isOfflineModeEnabled.first())
        assertTrue(repository.isAiEnabled.first())
        assertFalse(repository.isAutoSaveRecapsEnabled.first())
        assertEquals(ThemeMode.LIGHT, repository.themeMode.first())
        assertEquals(0.8f, repository.einkContrast.first(), 0.0001f)
        assertEquals("content://test/dictionary", repository.getDictionaryFolder().first())
        assertTrue(repository.getUseEmbeddedDictionary().first())
        vaultId.value = "repo_extended_${UUID.randomUUID()}"
        assertTrue(repository.isOfflineModeEnabled.first())
        assertEquals(ThemeMode.E_INK, repository.themeMode.first())
    }

    @Test fun malformedSavedEnumsFallBackToStableReaderDefaults() = runBlocking {
        val store = preferences()
        store.edit {
            it[SettingsRepository.THEME_KEY] = "deleted-theme"
            it[SettingsRepository.DITHERING_MODE_KEY] = "deleted-dithering"
            it[SettingsRepository.COVER_ASPECT_RATIO_KEY] = "deleted-aspect"
        }
        assertEquals(ThemeMode.E_INK, repository.themeMode.first())
        assertEquals(DitheringMode.AUTO, repository.bookshelfPreferences.first().ditheringMode)
        assertEquals(CoverAspectRatio.UNIFORM, repository.bookshelfPreferences.first().coverAspectRatio)
        repository.setDitheringMode(DitheringMode.OFF)
        repository.setCoverAspectRatio(CoverAspectRatio.ORIGINAL)
        repository.setGroupBySeries(false)
        repository.setCoverStyleElements(false, false, false, false)
        assertEquals(DitheringMode.OFF, repository.bookshelfPreferences.first().ditheringMode)
        assertEquals(CoverAspectRatio.ORIGINAL, repository.bookshelfPreferences.first().coverAspectRatio)
        assertFalse(repository.bookshelfPreferences.first().groupBySeries)
        assertFalse(repository.bookshelfPreferences.first().showFormatBadge)
        assertFalse(repository.bookshelfPreferences.first().showFavoriteIcon)
        assertFalse(repository.bookshelfPreferences.first().showProgressBadge)
        assertFalse(repository.bookshelfPreferences.first().showSyncStatus)
    }

    @Test fun syncSettingsNormalizeUrlsAndOnlyReplaceNonblankDeviceNames() = runBlocking {
        val originalDeviceName = repository.deviceName.first()
        repository.saveSyncSettings(" https://sync.test/ ", " http://local.test/ ", true, " ")
        assertEquals("https://sync.test", repository.syncCloudUrl.first())
        assertEquals("http://local.test", repository.localServerUrl.first())
        assertTrue(repository.useLocalServer.first())
        assertEquals(originalDeviceName, repository.deviceName.first())
        repository.saveSyncSettings("https://other.test/", "", false, " E-reader ")
        assertEquals("E-reader", repository.deviceName.first())
        assertFalse(repository.useLocalServer.first())
        repository.setSyncEnabled(true)
        repository.setSyncCloudUrl("https://direct.test")
        repository.setLocalServerUrl("http://local2.test")
        repository.setUseLocalServer(true)
        repository.setLastSyncTimestamp(1234L)
        assertTrue(repository.isSyncEnabled.first())
        assertEquals("https://direct.test", repository.syncCloudUrl.first())
        assertEquals("http://local2.test", repository.localServerUrl.first())
        assertEquals(1234L, repository.getLastSyncTimestamp())
        val deviceId = repository.ensureDeviceId()
        assertEquals(deviceId, repository.ensureDeviceId())
        assertTrue(deviceId.startsWith("android-"))
    }

    @Test fun ttsDefaultValuesCanBeOverriddenIndependently() = runBlocking {
        assertEquals(1.0f, repository.ttsSettings.first().defaultSpeed, 0.0001f)
        repository.setTtsDefaultSpeed(1.25f)
        repository.setTtsAutoPageTurn(false)
        repository.setTtsVisualStyle("highlight")
        repository.setTtsLanguage("en")
        repository.setTtsPitch(0.9f)
        repository.setTtsSleepTimer(15)
        val tts = repository.ttsSettings.first()
        assertEquals(1.25f, tts.defaultSpeed, 0.0001f)
        assertFalse(tts.isAutoPageTurnEnabled)
        assertEquals("highlight", tts.visualStyle)
        assertEquals("en", tts.language)
        assertEquals(0.9f, tts.pitch, 0.0001f)
        assertEquals(15, tts.sleepTimerMinutes)
    }

    @Test fun readerPublisherStylesRemoveOverridesAndResetClearsLayoutFields() = runBlocking {
        val custom = ReaderPreferences(
            theme = "dark", fontSize = 1.15, publisherStyles = false,
            fontFamily = "serif", textAlign = "justify", lineHeight = 1.4,
            pageMargins = 24.0, marginTop = 10.0, hyphens = true
        )
        repository.saveReaderPreferences(custom)
        val store = preferences()
        assertEquals("serif", store.data.first()[SettingsRepository.READER_FONT_FAMILY])
        assertEquals(true, store.data.first()[SettingsRepository.READER_HYPHENS])
        repository.saveReaderPreferences(custom.copy(publisherStyles = true))
        assertEquals(null, store.data.first()[SettingsRepository.READER_FONT_FAMILY])
        assertEquals(null, store.data.first()[SettingsRepository.READER_HYPHENS])
        repository.saveReaderPreferences(custom)
        repository.resetLayoutPreferences()
        assertEquals(null, store.data.first()[SettingsRepository.READER_LINE_HEIGHT])
        assertEquals(null, store.data.first()[SettingsRepository.READER_MARGIN_SIDE])
        assertFalse(store.data.first()[SettingsRepository.READER_PUBLISHER_STYLES] ?: true)
    }

    @Test fun emptyAndInvalidDictionaryFoldersAreRejectedWithoutCrashing() = runBlocking {
        assertFalse(repository.validateStarDictFolder(""))
        assertFalse(repository.validateStarDictFolder("not-a-document-tree"))
        val first = repository.ensureDeviceId()
        vaultId.value = "repo_extended_${UUID.randomUUID()}"
        assertNotEquals(first, repository.ensureDeviceId())
    }
}
