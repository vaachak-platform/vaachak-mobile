package org.vaachak.reader.leisure.ui.catalog

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import org.junit.Rule
import org.junit.Test
import org.vaachak.reader.core.data.repository.CatalogCredentialAction
import org.vaachak.reader.core.data.repository.GutendexRepository
import org.vaachak.reader.core.data.repository.LibraryRepository
import org.vaachak.reader.core.data.repository.OpdsRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.repository.OpdsMigrationReport
import org.vaachak.reader.core.domain.model.OpdsEntity
import org.vaachak.reader.leisure.testutil.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelUrlHardeningTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val repository = mockk<OpdsRepository>()
    private val settings = mockk<SettingsRepository>()
    private val root = "https://catalog.example/opds"
    private val existing = OpdsEntity(id = 7, title = "Existing", url = root, isPredefined = true,
        credentialGeneration = "00000000-0000-0000-0000-000000000001")

    private fun model(): CatalogViewModel {
        every { repository.catalogs } returns flowOf(emptyList())
        every { repository.allFeeds } returns flowOf(emptyList())
        every { settings.isEinkEnabled } returns flowOf(false)
        every { settings.isOfflineModeEnabled } returns flowOf(false)
        coEvery { repository.saveCatalog(any(), any(), any(), any(), any(), any()) } returns Result.success("Saved")
        return CatalogViewModel(repository, mockk<GutendexRepository>(relaxed = true), settings,
            mockk<LibraryRepository>(relaxed = true), mockk<Application>(relaxed = true))
    }

    @Test fun addCatalog_defaultsBlankTitleAndForcesHttpsOpdsUrl() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        check(model().saveCatalog(null, "", "mac-mini.tail1f3687.ts.net/calibre", CatalogCredentialAction.REMOVE, null, null).await().isSuccess)
        coVerify(exactly = 1) { repository.saveCatalog(null, "Library", "https://mac-mini.tail1f3687.ts.net/calibre/opds", CatalogCredentialAction.REMOVE, null, null) }
    }

    @Test fun addCatalog_keepsGutendexBooksUrlWithoutAppendingOpds() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        check(model().saveCatalog(null, "Gutendex", "https://gutendex.com/books", CatalogCredentialAction.REMOVE, null, null).await().isSuccess)
        coVerify(exactly = 1) { repository.saveCatalog(null, "Gutendex", "https://gutendex.com/books", CatalogCredentialAction.REMOVE, null, null) }
    }

    @Test fun updateCatalog_upgradesHttpToHttpsAndPreservesOriginalIdentity() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        check(model().saveCatalog(existing, "Renamed", "http://catalog.example/opds", CatalogCredentialAction.KEEP, null, null).await().isSuccess)
        coVerify(exactly = 1) { repository.saveCatalog(existing, "Renamed", root, CatalogCredentialAction.KEEP, null, null) }
    }

    @Test fun addCatalog_keepsXmlFeedUrlAndUpgradesToHttps() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        check(model().saveCatalog(null, "Custom", "http://example.com/catalog.xml", CatalogCredentialAction.REMOVE, null, null).await().isSuccess)
        coVerify(exactly = 1) { repository.saveCatalog(null, "Custom", "https://example.com/catalog.xml", CatalogCredentialAction.REMOVE, null, null) }
    }

    @Test fun updateCatalog_forwardsReplacementIntentAndPairOnlyToSecureRepository() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        check(model().saveCatalog(existing, "New", "https://other.example/opds", CatalogCredentialAction.REPLACE, "reader", "fixture").await().isSuccess)
        coVerify(exactly = 1) { repository.saveCatalog(existing, "New", "https://other.example/opds", CatalogCredentialAction.REPLACE, "reader", "fixture") }
    }

    @Test fun updateCatalog_distinguishesKeepFromExplicitRemove() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val model = model()
        check(model.saveCatalog(existing, "New", root, CatalogCredentialAction.KEEP, null, null).await().isSuccess)
        check(model.saveCatalog(existing, "New", root, CatalogCredentialAction.REMOVE, null, null).await().isSuccess)
        coVerify(exactly = 1) { repository.saveCatalog(existing, "New", root, CatalogCredentialAction.KEEP, null, null) }
        coVerify(exactly = 1) { repository.saveCatalog(existing, "New", root, CatalogCredentialAction.REMOVE, null, null) }
    }

    @Test fun failedSaveDeleteAndMigrationResultsAreReturnedToEditor() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val model = model()
        coEvery { repository.saveCatalog(any(), any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("Fixture failure"))
        coEvery { repository.deleteFeed(existing) } returns Result.failure(IllegalStateException("Fixture failure"))
        coEvery { repository.migrateLegacyCredentials() } returns Result.success(OpdsMigrationReport(1, 2))
        check(model.saveCatalog(existing, "New", root, CatalogCredentialAction.KEEP, null, null).await().isFailure)
        check(model.deleteCatalog(existing).await().isFailure)
        check(model.migrateLegacyCredentials().await().getOrThrow() == OpdsMigrationReport(1, 2))
    }

    @Test fun onlyExactAnonymousGutendexOriginUsesPublicRepository() {
        val public = OpdsEntity(id = 8, title = "Public", url = "https://gutendex.com/books")
        check(isPublicGutendex(public))
        check(isPublicGutendex(public.copy(url = "https://gutendex.com:443/books/")))
        for (url in listOf("https://gutendex.com.evil.example/books", "https://other.example/books", "https://gutendex.com:8443/books", "http://gutendex.com/books", "https://user:pass@gutendex.com/books", "https://gutendex.com/opds", "not a url")) {
            check(!isPublicGutendex(public.copy(url = url)))
        }
        check(!isPublicGutendex(public.copy(username = "legacy")))
        check(!isPublicGutendex(public.copy(credentialGeneration = existing.credentialGeneration)))
    }

    @Test fun cancellingPendingEditorOperationCancelsRepositoryWork() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val model = model()
        var entered = false
        var cancelled = false
        coEvery { repository.saveCatalog(any(), any(), any(), any(), any(), any()) } coAnswers {
            entered = true
            try { awaitCancellation() } finally { cancelled = true }
        }
        val pending = model.saveCatalog(existing, "Rename", root, CatalogCredentialAction.KEEP, null, null)
        runCurrent()
        check(entered && !pending.isCompleted)
        pending.cancelAndJoin()
        check(cancelled && pending.isCancelled)
    }

}
