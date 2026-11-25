package org.vaachak.reader.leisure.ui.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.vaachak.reader.core.data.repository.VaultRepository
import org.vaachak.reader.leisure.MainActivity
import java.util.concurrent.Executors
import javax.inject.Inject

internal data class AudioNowPlaying(
    val vault: String, val id: String, val position: Long, val duration: Long,
    val playing: Boolean, val preferences: AudioPreferences, val sleep: String = "Off",
    val ended: Boolean = false
)
internal data class AudioPlaybackError(val vault: String, val message: String)
internal object AudioPlayback {
    private val mutable = MutableStateFlow<AudioNowPlaying?>(null)
    val state = mutable.asStateFlow()
    private val mutableError = MutableStateFlow<AudioPlaybackError?>(null)
    val error = mutableError.asStateFlow()
    internal fun show(value: AudioNowPlaying?) { mutable.value = value }
    internal fun error(vault: String, message: String) { mutableError.value = AudioPlaybackError(vault, message) }
    internal fun clearError() { mutableError.value = null }
    fun send(context: Context, vault: String, action: String, id: String = "", value: Long = 0L) {
        val intent = Intent(context, AudioPlaybackService::class.java).setAction("vaachak.audio.$action")
            .putExtra("vault", vault).putExtra("book", id).putExtra("value", value)
        try { context.startService(intent) }
        catch (_: IllegalStateException) { error(vault, "Open the app to control playback.") }
        catch (_: SecurityException) { error(vault, "Playback service unavailable.") }
    }
    fun stopForProfilePicker(context: Context) {
        val intent = Intent(context, AudioPlaybackService::class.java).setAction("vaachak.audio.profileExit")
        try { context.startService(intent) }
        catch (_: IllegalStateException) { context.stopService(intent) }
        catch (_: SecurityException) { context.stopService(intent) }
    }
}

/** One serial worker across service instances preserves queued progress before a later reopen. */
private object AudioDisk {
    val executor = Executors.newSingleThreadExecutor()
}

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class AudioPlaybackService : MediaSessionService() {
    @Inject lateinit var vaultRepository: VaultRepository
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var store: AudioStore
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeVault: String? = null
    private var book: AudioBook? = null
    private var timeline: AudioTimeline? = null
    private var preferences = AudioPreferences()
    private var opening = false
    private var generation = 0L
    private var lastSave = 0L
    private var sleepDeadline = 0L
    private var sleepChapter: Long? = null
    private var destroyed = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!opening) { if (!isPlaying) persist(); publish() }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!opening) { if (playbackState == Player.STATE_ENDED) persist(); publish() }
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!opening) { persist(); publish() }
        }
        override fun onPlayerError(error: PlaybackException) {
            activeVault?.let { AudioPlayback.error(it, "Audio cannot be played on this device.") }
            publish()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            val vault = activeVault
            if (vault != null && vault != vaultRepository.activeVaultId.value) {
                stopPlayback(); return
            }
            if (!opening && book != null) {
                checkSleep()
                if (player.isPlaying && SystemClock.elapsedRealtime() - lastSave >= SAVE_INTERVAL) persist()
                publish()
            }
            handler.postDelayed(this, TICK_INTERVAL)
        }
    }
    override fun onCreate() {
        super.onCreate()
        store = AudioStore.get(this)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(listener)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (!controller.isTrusted && controller.packageName != packageName) return MediaSession.ConnectionResult.reject()
                // Transport only: controllers cannot replace the service-owned local playlist or inject URLs.
                val commands = Player.Commands.Builder().addAllCommands()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM)
                    .remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE)
                    .remove(Player.COMMAND_SET_SPEED_AND_PITCH).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands).build()
            }
        }).build()
        addSession(session)
        scope.launch { vaultRepository.activeVaultId.collect { id ->
            if (activeVault != null && activeVault != id) stopPlayback()
        } }
        handler.post(tick)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf { controllerInfo.isTrusted || controllerInfo.packageName == packageName }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        if (!action.startsWith("vaachak.audio.")) {
            if (intent == null) { stopSelf(); return START_NOT_STICKY }
            super.onStartCommand(intent, flags, startId)
            return START_NOT_STICKY
        }
        if (action == "vaachak.audio.profileExit") { stopPlayback(); return START_NOT_STICKY }
        val vault = intent?.getStringExtra("vault").orEmpty()
        if (vault.isBlank() || vault != vaultRepository.activeVaultId.value) {
            if (book == null) stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("book").orEmpty()
        val value = intent?.getLongExtra("value", 0L) ?: 0L
        val command = action.removePrefix("vaachak.audio.")
        if (command == "open") open(vault, id)
        else if (!opening && activeVault == vault && book?.id == id) transport(command, value)
        else if (book == null && !opening) stopSelf()
        return START_NOT_STICKY
    }
    private fun transport(command: String, value: Long) {
        val line = timeline ?: return
        when (command) {
            "toggle" -> if (player.playWhenReady) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) seek(0L)
                player.play()
            }
            "back" -> seek(position() - preferences.back * 1000L)
            "forward" -> seek(position() + preferences.forward * 1000L)
            "previous" -> seek(line.previous(position()))
            "next" -> seek(line.next(position()))
            "seek" -> seek(value)
            "settings" -> refreshSettings()
            "sleep" -> { sleepChapter = null; sleepDeadline = if (value in 1L..120L) SystemClock.elapsedRealtime() + value * 60000 else 0L }
            "sleepChapter" -> { sleepDeadline = 0L; sleepChapter = line.next(position()) }
            "stop" -> { stopPlayback(); return }
        }
        publish()
    }
    private fun open(vault: String, id: String) {
        AudioPlayback.clearError()
        if (!opening && activeVault == vault && book?.id == id) {
            if (player.playbackState == Player.STATE_ENDED) seek(0L)
            player.play()
            return
        }
        clearActive()
        activeVault = vault
        opening = true
        val token = ++generation
        AudioDisk.executor.execute {
            val result = runCatching {
                val library = store.load(vault)
                val selected = library.find(id) ?: error("No such audiobook")
                val paths = selected.tracks.map { store.mediaFile(vault, selected.id, it.file) }
                check(paths.all { it.isFile && it.length() > 0 }) { "Missing local media" }
                Triple(selected, selected.titlePreferences ?: library.defaults, paths)
            }
            handler.post {
                if (!destroyed && token == generation && vault == vaultRepository.activeVaultId.value) {
                    result.fold(onSuccess = { (selected, defaults, paths) -> activate(vault, selected, defaults, paths) },
                        onFailure = { AudioPlayback.error(vault, "Audiobook unavailable. Remove it and import the original files again."); stopPlayback() })
                }
            }
        }
    }
    private fun activate(vault: String, selected: AudioBook, settings: AudioPreferences, paths: List<java.io.File>) {
        val line = AudioTimeline(selected.tracks)
        try {
            book = selected
            timeline = line
            val items = selected.tracks.mapIndexed { index, track ->
                MediaItem.Builder().setMediaId("${selected.id}:$index").setUri(Uri.fromFile(paths[index]))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(track.name).setAlbumTitle(selected.title)
                        .setArtist(selected.author).build()).build()
            }
            val start = line.locate(if (selected.finished) 0L else selected.position)
            applyPreferences(settings)
            player.setMediaItems(items, start.index, start.offset)
            player.prepare()
            opening = false
            player.play()
            persist()
            publish()
        } catch (_: Exception) {
            opening = true // do not replace saved progress if setup failed
            AudioPlayback.error(vault, "Could not open audiobook.")
            stopPlayback()
        }
    }
    private fun refreshSettings() {
        val vault = activeVault ?: return
        val id = book?.id ?: return
        val token = generation
        AudioDisk.executor.execute {
            val result = runCatching { store.load(vault).let { it.find(id)?.titlePreferences ?: it.defaults } }
            handler.post { if (!destroyed && token == generation) result.onSuccess { applyPreferences(it); publish() } }
        }
    }
    private fun applyPreferences(value: AudioPreferences) {
        preferences = value
        player.setPlaybackSpeed(value.speed)
        player.setSeekBackIncrementMs(value.back * 1000L)
        player.setSeekForwardIncrementMs(value.forward * 1000L)
    }
    private fun position(): Long = timeline?.global(player.currentMediaItemIndex, player.currentPosition) ?: 0L
    private fun seek(position: Long) {
        val target = timeline?.locate(position) ?: return
        player.seekTo(target.index, target.offset)
        persist()
    }
    private fun checkSleep() {
        val deadlineReached = sleepDeadline > 0 && SystemClock.elapsedRealtime() >= sleepDeadline
        val chapterReached = sleepChapter?.let { position() >= it || player.playbackState == Player.STATE_ENDED } == true
        if (deadlineReached || chapterReached) {
            val boundary = sleepChapter
            sleepDeadline = 0L; sleepChapter = null
            player.pause()
            if (chapterReached && boundary != null && player.playbackState != Player.STATE_ENDED) seek(boundary)
            persist()
        }
    }
    private fun publish() {
        val current = book ?: return
        val vault = activeVault ?: return
        if (opening || vault != vaultRepository.activeVaultId.value) return
        val sleep = when {
            sleepChapter != null -> "End of chapter"
            sleepDeadline > 0L -> audioClock(sleepDeadline - SystemClock.elapsedRealtime())
            else -> "Off"
        }
        val ended = player.playbackState == Player.STATE_ENDED
        AudioPlayback.show(AudioNowPlaying(vault, current.id, if (ended) current.duration else position(),
            current.duration, player.isPlaying, preferences, sleep, ended))
    }
    private fun persist() {
        val current = book ?: return
        val vault = activeVault ?: return
        if (opening || player.mediaItemCount == 0) return
        val ended = player.playbackState == Player.STATE_ENDED
        val pos = if (ended) current.duration else position()
        val now = System.currentTimeMillis()
        lastSave = SystemClock.elapsedRealtime()
        AudioDisk.executor.execute {
            try { store.progress(vault, current.id, pos, now, ended) }
            catch (_: Exception) { handler.post { if (!destroyed && activeVault == vault) AudioPlayback.error(vault, "Listening position could not be saved.") } }
        }
    }
    private fun clearActive() {
        persist()
        generation++
        opening = true
        book = null; timeline = null; activeVault = null
        sleepDeadline = 0L; sleepChapter = null
        player.stop(); player.clearMediaItems()
        opening = false
        AudioPlayback.show(null)
    }
    private fun stopPlayback() { clearActive(); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) {
        persist()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }
    override fun onDestroy() {
        persist()
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        player.removeListener(listener)
        session.release()
        player.release()
        AudioPlayback.show(null)
        super.onDestroy()
    }
    private companion object {
        const val SAVE_INTERVAL = 10000L
        const val TICK_INTERVAL = 500L
    }
}
