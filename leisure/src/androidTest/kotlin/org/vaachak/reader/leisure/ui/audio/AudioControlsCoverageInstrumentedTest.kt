package org.vaachak.reader.leisure.ui.audio

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioControlsCoverageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private val track = AudioTrack("First", "000.wav", "a".repeat(64), 60000,
        listOf(AudioChapter("Start",0),AudioChapter("Second",30000)))
    private val book = AudioBook(UUID.randomUUID().toString(),"Fixture", "Narrator",listOf(track),10000,100)

    @Test fun playerControlsSendTransportAndSettingsActions() {
        val commands = mutableListOf<Pair<String,Long>>()
        var settings by mutableStateOf<AudioPreferences?>(null)
        var now by mutableStateOf<AudioNowPlaying?>(AudioNowPlaying("fixture",book.id,10000,60000,true,AudioPreferences()))
        var closed = false
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AudioPlayerPanel(book.copy(titlePreferences=settings),AudioPreferences(),now,
                onClose={closed=true},onCommand={a,v->commands.add(a to v)},onSettings={settings=it})
        } } }
        fun click(label:String) { compose.onNodeWithText(label).performScrollTo().performClick() }
        fun tag(tag:String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
        click("−15s"); click("Pause"); click("+30s"); click("Previous chapter"); click("Next chapter")
        compose.onNodeWithTag("audio_seek").performScrollTo().performTouchInput { swipeRight() }
        tag("audio_sleep"); compose.onNodeWithText("End of chapter").performClick()
        tag("audio_sleep"); compose.onNodeWithText("15 min").performClick()
        tag("audio_sleep"); compose.onNodeWithText("Off").performClick()
        tag("audio_chapters"); tag("audio_chapter_select")
        compose.onNodeWithText("2. Second · 0:30").performClick()
        tag("audio_chapters")
        tag("audio_title_settings")
        tag("audio_title_speed"); compose.onNodeWithText("1.25×").performClick()
        tag("audio_title_back"); compose.onNodeWithText("5s").performClick()
        tag("audio_title_forward"); compose.onNodeWithText("60s").performClick()
        compose.runOnIdle { assertEquals(AudioPreferences(1.25f,5,60),settings) }
        compose.onNodeWithText("This title has its own settings").assertExists()
        tag("audio_reset_title"); compose.onNodeWithText("Using global defaults").assertExists()
        click("Stop playback")
        compose.runOnIdle { assertTrue(closed); now=null; closed=false }
        click("Play"); click("Close player")
        compose.runOnIdle {
            assertTrue(closed)
            assertTrue(commands.map { it.first }.containsAll(listOf("back","toggle","forward","previous","next","seek","sleepChapter","sleep","stop","open")))
            assertTrue(commands.contains("seek" to 30000L))
            assertTrue(commands.contains("sleep" to 15L))
            assertTrue(commands.contains("sleep" to 0L))
            assertNull(settings)
        }
    }
    @Test fun shelfHeaderAndRemoveConfirmationReflectFailureAndBusyStates() {
        var busy by mutableStateOf(false)
        var loaded by mutableStateOf(false)
        var failed by mutableStateOf(false)
        var message by mutableStateOf<String?>(null)
        var playbackError by mutableStateOf<AudioPlaybackError?>(null)
        var dialog by mutableStateOf(false)
        var files=0; var folders=0; var removals=0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AudioShelfHeader(busy,loaded,failed,message,playbackError,{files++},{folders++})
            if(dialog) AudioRemoveDialog({dialog=false},{dialog=false;removals++})
        } } }
        compose.onNodeWithText("Loading audiobooks…").assertExists()
        compose.onNodeWithTag("audio_import_files").assertIsNotEnabled()
        compose.runOnIdle { loaded=true }
        compose.onNodeWithTag("audio_import_files").performClick()
        compose.onNodeWithTag("audio_import_folder").performClick()
        compose.runOnIdle { busy=true; message="Import status"; playbackError=AudioPlaybackError("fixture","Playback failed") }
        compose.onNodeWithTag("audio_import_folder").assertIsNotEnabled()
        compose.onNodeWithText("Importing audio. Keep this screen open until finished.").assertExists()
        compose.onNodeWithTag("audio_message").assertExists()
        compose.onNodeWithText("Playback failed").assertExists()
        compose.runOnIdle { busy=false; failed=true; loaded=false; dialog=true }
        compose.onNodeWithText("Audiobook index unavailable. Existing files have not been cleared.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0,removals); dialog=true }
        compose.onNodeWithTag("audio_confirm_remove").performClick()
        compose.runOnIdle { assertEquals(1,removals); assertEquals(1,files); assertEquals(1,folders) }
    }
    @Test fun defaultsAndFilterControlsPersistAndHandleEmptyShelf() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=AudioStore.get(context)
        val vault="audio-quality-${UUID.randomUUID()}"
        var showDefaults by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if(showDefaults) Column(Modifier.verticalScroll(rememberScrollState())) { AudioDefaultsPanel(vault) }
            else AudioShelfScreen(vault)
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("audio_default_back").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("audio_default_back").performClick(); compose.onNodeWithText("5s").performClick()
        compose.waitUntil(15000) { store.load(vault).defaults.back==5 }
        compose.onNodeWithTag("audio_default_forward").performClick(); compose.onNodeWithText("60s").performClick()
        compose.waitUntil(15000) { store.load(vault).defaults.forward==60 }
        compose.runOnIdle { showDefaults=false }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("audio_filter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("audio_shelf").performScrollToIndex(1)
        compose.onNodeWithTag("audio_filter").performClick(); compose.onNodeWithText("Finished").performClick()
        compose.onNodeWithTag("audio_sort").performClick(); compose.onNodeWithText("Title").performClick()
        compose.onNodeWithTag("audio_search").performTextInput("missing")
        compose.onNodeWithTag("audio_shelf").performScrollToIndex(2)
        compose.onNodeWithText("No audiobooks yet.").assertExists()
    }
    @Test fun pickerImportsRealLocalMediaAndHandlesDuplicateAndRemoval() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val store=AudioStore.get(context)
        val vault="audio-picker-${UUID.randomUUID()}"
        val uri=checkNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,"VaachakFixture-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/VaachakTest")
        }))
        try {
            instrumentation.context.assets.open("audio-quality-fixture.mp3").use { input ->
                checkNotNull(context.contentResolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            var selection=listOf(uri)
            var folderSelection: Uri?=null
            val owner=object:ActivityResultRegistryOwner {
                override val activityResultRegistry=object:ActivityResultRegistry() {
                    override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                        when(contract) {
                            is ActivityResultContracts.OpenMultipleDocuments -> dispatchResult(requestCode,selection)
                            is ActivityResultContracts.OpenDocumentTree -> dispatchResult(requestCode,folderSelection)
                            else -> error("Unexpected picker")
                        }
                    }
                }
            }
            compose.setContent { MaterialTheme { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { AudioShelfScreen(vault) } } }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("audio_filter").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("audio_import_folder").performClick() // cancellation
            compose.runOnIdle { folderSelection=Uri.parse("file:///invalid") }
            compose.onNodeWithTag("audio_import_folder").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Select supported, readable audio files within the import limits.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("audio_import_files").performClick()
            compose.waitUntil(15000) { store.load(vault).books.size==1 }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Audiobook imported.").fetchSemanticsNodes().isNotEmpty() }
            val item=store.load(vault).books.single()
            assertEquals("Synthetic Album",item.title)
            compose.onNodeWithTag("audio_import_files").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("This audiobook is already in your library.").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { selection=emptyList() }
            compose.onNodeWithTag("audio_import_files").performClick()
            assertEquals(1,store.load(vault).books.size)
            compose.onNodeWithTag("audio_shelf").performScrollToIndex(3)
            compose.onNodeWithTag("audio_remove_${item.id}").performClick()
            compose.onNodeWithTag("audio_confirm_remove").performClick()
            compose.waitUntil(15000) { store.load(vault).books.isEmpty() }
            assertTrue(context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 }==true)
        } finally {
            store.load(vault).books.forEach { store.remove(vault,it.id) }
            context.contentResolver.delete(uri,null,null)
        }
    }
}
