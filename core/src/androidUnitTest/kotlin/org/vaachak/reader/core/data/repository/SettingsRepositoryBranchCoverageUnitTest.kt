package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.domain.model.ComicPageFit
import org.vaachak.reader.core.domain.model.ComicPreferences
import org.vaachak.reader.core.domain.model.ComicReadingDirection
import org.vaachak.reader.core.domain.model.ComicReadingMode
import org.vaachak.reader.core.domain.model.ReaderPreferences

/** Covers nullable reader overrides and saved preference decoder branches. */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryBranchCoverageUnitTest {
    private val activeVaultId = MutableStateFlow("branches_${UUID.randomUUID()}")
    private val vault = mockk<VaultRepository>()
    private val secrets = mockk<PrivateGatewaySecrets>()

    /**
     * Simulates encrypted per-vault storage rather than a no-op mock.
     * SettingsRepository now verifies that a newly written secret can be read
     * back before removing its legacy plaintext copy.
     */
    private val encryptedSecrets = mutableMapOf<String, String>()
    private val repository: SettingsRepository

    private val ai = AiCredentialRuntimeFixture()

    init {
        every { vault.activeVaultId } returns activeVaultId
        every { secrets.read(any()) } answers {
            encryptedSecrets[firstArg<String>()].orEmpty()
        }
        every { secrets.write(any(), any()) } answers {
            encryptedSecrets[firstArg<String>()] = secondArg<String>()
            Unit
        }
        every { secrets.clear(any()) } answers {
            encryptedSecrets.remove(firstArg<String>())
            Unit
        }
        configureAiVault(vault, activeVaultId)
        configureLegacyAiStorage(secrets, encryptedSecrets)
        repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets, ai.runtime)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun preferences(): DataStore<Preferences> {
        repository.geminiKey.first() // creates this vault's DataStore
        val field = SettingsRepository::class.java.getDeclaredField("activeVaults")
        field.isAccessible = true
        val stores = field.get(repository) as MutableMap<String, DataStore<Preferences>>
        return stores.getValue(activeVaultId.value)
    }

    @Test fun everyOptionalReaderOverrideCanBeStoredAndThenCleared() = runBlocking {
        val full = ReaderPreferences(
            publisherStyles = false,
            fontFamily = "test-serif", textAlign = "justify", lineHeight = 1.4,
            letterSpacing = 0.2, paragraphSpacing = 0.3, pageMargins = 24.0,
            wordSpacing = 0.1, paragraphIndent = 1.2, hyphens = true,
            ligatures = false, marginTop = 18.0, marginBottom = 22.0
        )
        repository.saveReaderPreferences(full)
        assertEquals(full, repository.readerPreferences.first())

        // Publisher styles remain OFF: all nullable fields must take the
        // remove branch rather than the store branch.
        val cleared = full.copy(
            fontFamily = null, textAlign = null, lineHeight = null,
            letterSpacing = null, paragraphSpacing = null, pageMargins = null,
            wordSpacing = null, paragraphIndent = null, hyphens = null,
            ligatures = null, marginTop = null, marginBottom = null
        )
        repository.saveReaderPreferences(cleared)
        assertEquals(cleared, repository.readerPreferences.first())
        val raw = preferences().data.first()
        assertNull(raw[SettingsRepository.READER_FONT_FAMILY])
        assertNull(raw[SettingsRepository.READER_WORD_SPACING])
        assertNull(raw[SettingsRepository.READER_PARAGRAPH_INDENT])
        assertNull(raw[SettingsRepository.READER_MARGIN_BOTTOM])
    }

    @Test fun comicEnumsHandleMissingCorruptAndSavedValues() = runBlocking {
        assertEquals(ComicPreferences(), repository.comicPreferences.first())
        val datastore = preferences()
        datastore.edit { p ->
            p[SettingsRepository.COMIC_READING_DIRECTION] = "obsolete-direction"
            p[SettingsRepository.COMIC_READING_MODE] = "obsolete-mode"
            p[SettingsRepository.COMIC_PAGE_FIT] = "obsolete-fit"
        }
        assertEquals(ComicPreferences(), repository.comicPreferences.first())

        val other = ComicPreferences(
            readingDirection = ComicReadingDirection.RIGHT_TO_LEFT,
            readingMode = ComicReadingMode.VERTICAL_SCROLL,
            pageFit = ComicPageFit.FIT_WIDTH,
            tapToTurn = false, useVolumeKeys = true,
            preloadNextPage = false, reduceMotion = false
        )
        repository.saveComicPreferences(other)
        assertEquals(other, repository.comicPreferences.first())
        activeVaultId.value = "branches_${UUID.randomUUID()}"
        assertEquals(ComicPreferences(), repository.comicPreferences.first())
    }

    @Test fun syncProfileTrimsIdentifiersButPreservesPasswordBytes() = runBlocking {
        val secret = "  secret with spaces  "
        repository.updateSyncProfile("  reader  ", secret, "  BOOX Reader  ")
        assertEquals("reader", repository.syncUsername.first())
        assertEquals(secret, repository.syncPassword.first())
        assertEquals("BOOX Reader", repository.deviceName.first())
        // The production repository must no longer write the plaintext DataStore key.
        assertNull(preferences().data.first()[SettingsRepository.SYNC_PASSWORD])
        assertTrue(encryptedSecrets.values.contains(secret))

        repository.updateSyncProfile("", "", "")
        assertEquals("", repository.syncUsername.first())
        assertEquals("", repository.syncPassword.first())
        assertTrue(repository.deviceName.first().isNotBlank())
        assertTrue(encryptedSecrets.values.none { it == secret })
    }
}
