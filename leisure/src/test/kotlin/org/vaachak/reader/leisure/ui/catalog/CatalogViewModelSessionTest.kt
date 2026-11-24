package org.vaachak.reader.leisure.ui.catalog

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.opds.Feed
import org.readium.r2.shared.opds.ParseData
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.vaachak.reader.core.data.repository.GutendexRepository
import org.vaachak.reader.core.data.repository.LibraryRepository
import org.vaachak.reader.core.data.repository.OpdsCatalogSession
import org.vaachak.reader.core.data.repository.OpdsRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.domain.model.OpdsEntity
import org.vaachak.reader.leisure.testutil.MainDispatcherRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelSessionTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val root = "https://catalog.example/opds"
    private val first = OpdsEntity(id = 1, title = "First", url = root, username = "reader-a", password = "fixture-a")
    private val second = first.copy(id = 2, title = "Second", username = "reader-b", password = "fixture-b")
    private val repository = mockk<OpdsRepository>()
    private val settings = mockk<SettingsRepository>()
    private val library = mockk<LibraryRepository>(relaxed = true)

    private fun viewModel(): CatalogViewModel {
        every { repository.catalogs } returns flowOf(listOf(first, second))
        every { repository.allFeeds } returns flowOf(listOf(first, second))
        every { settings.isEinkEnabled } returns flowOf(false)
        every { settings.isOfflineModeEnabled } returns flowOf(false)
        coEvery { library.getLocalBookMap() } returns emptyMap()
        return CatalogViewModel(repository, mockk<GutendexRepository>(relaxed = true), settings,
            library, mockk<Application>(relaxed = true))
    }

    private fun data(name: String): ParseData {
        val link = Link(href = requireNotNull(Url("$root/$name")), title = name)
        val feed = mockk<Feed>()
        every { feed.navigation } returns listOf(link)
        every { feed.publications } returns emptyList()
        return mockk<ParseData>().also { every { it.feed } returns feed }
    }

    @Test fun duplicateUrlSelectionsAndLateResponseKeepCorrectCatalog() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val late = CompletableDeferred<ParseData>()
            val sessions = mutableListOf<OpdsCatalogSession>()
            coEvery { repository.parseFeed(any(), any()) } coAnswers {
                val session = secondArg<OpdsCatalogSession>()
                sessions.add(session)
                Try.Success(if (session.catalogId == 1L) late.await() else data("second-folder"))
            }
            val model = viewModel()
            advanceUntilIdle()
            model.openCatalog(first)
            advanceUntilIdle()
            model.openCatalog(second)
            advanceUntilIdle()
            check((model.feedItems.value.single() as CatalogItem.Folder).title == "second-folder")
            late.complete(data("old-folder"))
            advanceUntilIdle()
            check((model.feedItems.value.single() as CatalogItem.Folder).title == "second-folder")
            check(sessions.map { it.catalogId } == listOf(1L, 2L))
            check(sessions[0] !== sessions[1])
        }

    @Test fun staleItemClickCannotUseAnotherSelectionAndFolderKeepsSession() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val sessions = mutableListOf<OpdsCatalogSession>()
            coEvery { repository.parseFeed(any(), any()) } coAnswers {
                val session = secondArg<OpdsCatalogSession>()
                sessions.add(session)
                Try.Success(data(if (session.catalogId == 1L) "old-folder" else "new-folder"))
            }
            val model = viewModel()
            advanceUntilIdle()
            model.openCatalog(first)
            advanceUntilIdle()
            val stale = model.feedItems.value.single()
            model.openCatalog(second)
            advanceUntilIdle()
            model.handleItemClick(stale)
            advanceUntilIdle()
            check(sessions.size == 2)
            model.handleItemClick(model.feedItems.value.single())
            advanceUntilIdle()
            check(sessions.size == 3 && sessions[1] === sessions[2])
            coVerify(exactly = 1) { repository.parseFeed("$root/new-folder", sessions[1]) }
        }
}
