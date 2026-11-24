package org.vaachak.reader.leisure.ui.catalog

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.opds.Feed
import org.readium.r2.shared.opds.ParseData
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vaachak.reader.core.data.remote.dto.GutendexBook
import org.vaachak.reader.core.data.remote.dto.GutendexResponse
import org.vaachak.reader.core.data.repository.GutendexRepository
import org.vaachak.reader.core.data.repository.LibraryRepository
import org.vaachak.reader.core.data.repository.OpdsCatalogSession
import org.vaachak.reader.core.data.repository.OpdsRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.domain.model.OpdsEntity
import org.vaachak.reader.leisure.testutil.MainDispatcherRule
import java.io.File

/** Real Readium URI/manifest fixtures under the already accepted Robolectric configuration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelSessionCoverageTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val root = "https://catalog.example/opds"
    private val bookUrl = "https://catalog.example/book.epub"
    private val coverUrl = "https://catalog.example/cover.jpg"
    private val gutUrl = "https://gutendex.com/books"
    private val first = OpdsEntity(id = 1, title = "First", url = root, username = "reader-a", password = "fixture-a")
    private val second = first.copy(id = 2, title = "Second", username = "reader-b", password = "fixture-b")
    private val publicFeed = OpdsEntity(id = 3, title = "Public", url = gutUrl)
    private val repository = mockk<OpdsRepository>()
    private val gutendex = mockk<GutendexRepository>()
    private val settings = mockk<SettingsRepository>()
    private val library = mockk<LibraryRepository>()

    private fun viewModel(): CatalogViewModel {
        every { repository.catalogs } returns flowOf(listOf(first, second, publicFeed))
        every { repository.allFeeds } returns flowOf(listOf(first, second, publicFeed))
        every { settings.isEinkEnabled } returns flowOf(false)
        every { settings.isOfflineModeEnabled } returns flowOf(false)
        coEvery { library.getLocalBookMap() } returns emptyMap()
        coEvery { library.isBookDuplicate(any()) } returns false
        coEvery { library.addDownloadedBook(any(), any(), any(), any()) } returns Result.success("fixture-uri")
        val application = mockk<Application>()
        every { application.filesDir } returns temporaryFolder.root
        return CatalogViewModel(repository, gutendex, settings, library, application)
    }

    private fun data(name: String, publications: List<Publication> = emptyList()): ParseData {
        val link = Link(href = requireNotNull(Url("$root/$name")), title = name)
        val feed = mockk<Feed>()
        every { feed.navigation } returns if (publications.isEmpty()) listOf(link) else emptyList()
        every { feed.publications } returns publications
        return mockk<ParseData>().also { every { it.feed } returns feed }
    }

    private fun publication(): Publication = Publication(manifest = Manifest(
        metadata = Metadata(localizedTitle = LocalizedString("Fixture Book")),
        links = listOf(
            Link(href = requireNotNull(Url(bookUrl)), mediaType = requireNotNull(MediaType("application/epub+zip"))),
            Link(href = requireNotNull(Url(coverUrl)), mediaType = requireNotNull(MediaType("image/jpeg")))
        )
    ))

    @Test fun inFlightBookAndCoverKeepOriginalSessionAfterCatalogSwitch() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val release = CompletableDeferred<Unit>()
        val sessions = mutableListOf<OpdsCatalogSession>()
        val downloads = mutableListOf<Pair<String, OpdsCatalogSession>>()
        coEvery { repository.parseFeed(any(), any()) } coAnswers {
            val session = secondArg<OpdsCatalogSession>()
            sessions.add(session)
            Try.Success(if (session.catalogId == first.id) data("first", listOf(publication())) else data("second-folder"))
        }
        coEvery { repository.downloadPublication(any(), any(), any(), any()) } coAnswers {
            val url = firstArg<String>()
            val session = arg<OpdsCatalogSession>(3)
            downloads.add(url to session)
            if (url == bookUrl) release.await()
            secondArg<File>().writeText("synthetic fixture")
            true
        }
        val model = viewModel()
        val events = mutableListOf<CatalogUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiEvent.collect { events.add(it) } }
        advanceUntilIdle()
        model.openCatalog(first)
        advanceUntilIdle()
        val book = model.feedItems.value.single() as CatalogItem.Book
        model.handleItemClick(book)
        advanceUntilIdle()
        check(downloads.size == 1 && downloads.single().first == bookUrl)
        model.openCatalog(second)
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()
        check(downloads.map { it.first } == listOf(bookUrl, coverUrl))
        check(downloads.all { it.second === sessions.first() } && sessions.map { it.catalogId } == listOf(1L, 2L))
        check((model.feedItems.value.single() as CatalogItem.Folder).title == "second-folder")
        check(!model.isLoading.value && events.any { it is CatalogUiEvent.ShowSnackbar && it.message == "Downloaded 'Fixture Book'" })
        coVerify(exactly = 1) { library.addDownloadedBook(any(), "Fixture Book", "Unknown", match<File> { it.exists() }) }
        model.downloadBook(book.publication)
        advanceUntilIdle()
        check(downloads.size == 2) // A stale book object cannot start another download.
    }

    @Test fun successfulDownloadRefreshesSameCatalogAndFailureRemovesPartialFile() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val sessions = mutableListOf<OpdsCatalogSession>()
        coEvery { repository.parseFeed(any(), any()) } coAnswers {
            sessions.add(secondArg<OpdsCatalogSession>())
            Try.Success(data("first", listOf(publication())))
        }
        var failBook = false
        var failedFile: File? = null
        coEvery { repository.downloadPublication(any(), any(), any(), any()) } coAnswers {
            val destination = secondArg<File>()
            destination.writeText("fixture")
            if (failBook && firstArg<String>() == bookUrl) { failedFile = destination; false } else true
        }
        val model = viewModel()
        val events = mutableListOf<CatalogUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiEvent.collect { events.add(it) } }
        advanceUntilIdle()
        model.openCatalog(first)
        advanceUntilIdle()
        model.handleItemClick(model.feedItems.value.single())
        advanceUntilIdle()
        check(sessions.size == 2 && sessions[0] === sessions[1])
        failBook = true
        model.handleItemClick(model.feedItems.value.single())
        advanceUntilIdle()
        check(failedFile != null && failedFile?.exists() == false && !model.isLoading.value)
        check(events.any { it is CatalogUiEvent.ShowSnackbar && it.message == "Download failed." })
        check(sessions.size == 2)
        coVerify(exactly = 1) { library.addDownloadedBook(any(), "Fixture Book", "Unknown", any()) }
    }

    @Test fun gutendexPaginationAndLateResponseCannotOverwriteNewOpdsCatalog() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val next = "$gutUrl?page=2"
        val late = CompletableDeferred<GutendexResponse>()
        val empty = GutendexResponse(0, next, null, emptyList())
        coEvery { gutendex.fetchBooks(any()) } coAnswers {
            if (firstArg<String>() == next) Result.success(late.await()) else Result.success(empty)
        }
        coEvery { repository.parseFeed(any(), any()) } returns Try.Success(data("opds-folder"))
        val model = viewModel()
        advanceUntilIdle()
        model.openCatalog(publicFeed)
        advanceUntilIdle()
        val folder = model.feedItems.value.first() as CatalogItem.Folder
        model.handleItemClick(folder)
        advanceUntilIdle()
        check((model.feedItems.value.single() as CatalogItem.Pagination).nextUrl == next)
        model.handlePaginationClick("https://wrong.example/page")
        advanceUntilIdle()
        coVerify(exactly = 0) { gutendex.fetchBooks("https://wrong.example/page") }
        model.handlePaginationClick(next)
        advanceUntilIdle()
        coVerify(exactly = 1) { gutendex.fetchBooks(next) }
        model.openCatalog(first)
        advanceUntilIdle()
        late.complete(GutendexResponse(0, null, null, emptyList()))
        advanceUntilIdle()
        check((model.feedItems.value.single() as CatalogItem.Folder).title == "opds-folder" && !model.isLoading.value)
    }

    @Test fun gutendexBookUsesPublicRepositoryAndRefreshesWithoutOpdsCredentials() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val payload = GutendexBook(7, "Public Fixture", emptyList(),
            mapOf("application/epub+zip" to bookUrl), 0)
        coEvery { gutendex.fetchBooks(any()) } returns Result.success(GutendexResponse(1, null, null, listOf(payload)))
        coEvery { gutendex.downloadBook(any(), any()) } coAnswers { secondArg<File>().writeText("fixture"); true }
        val model = viewModel()
        val events = mutableListOf<CatalogUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiEvent.collect { events.add(it) } }
        advanceUntilIdle()
        model.openCatalog(publicFeed)
        advanceUntilIdle()
        val folder = model.feedItems.value.first() as CatalogItem.Folder
        model.handleItemClick(folder)
        advanceUntilIdle()
        model.handleItemClick(model.feedItems.value.single())
        advanceUntilIdle()
        coVerify(exactly = 1) { gutendex.downloadBook(bookUrl, any()) }
        coVerify(exactly = 0) { gutendex.downloadBook(coverUrl, any()) }
        coVerify(exactly = 2) { gutendex.fetchBooks(folder.url) }
        coVerify(exactly = 0) { repository.downloadPublication(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.parseFeed(any(), any()) }
        check(events.any { it is CatalogUiEvent.ShowSnackbar && it.message == "Downloaded 'Public Fixture'" })
        check(!model.isLoading.value)
    }
}
