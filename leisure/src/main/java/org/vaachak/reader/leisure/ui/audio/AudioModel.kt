package org.vaachak.reader.leisure.ui.audio

import java.util.Locale

internal const val MAX_AUDIO_TRACKS = 128
internal const val MAX_AUDIO_BOOKS = 500
internal const val MAX_AUDIO_DURATION = 14L * 24 * 60 * 60 * 1000
internal val AUDIO_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
internal val AUDIO_BACK = listOf(5, 10, 15, 30)
internal val AUDIO_FORWARD = listOf(10, 15, 30, 60)
internal val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "opus", "wav", "mp4")

internal data class AudioPreferences(val speed: Float = 1f, val back: Int = 15, val forward: Int = 30) {
    init {
        require(speed in AUDIO_SPEEDS && back in AUDIO_BACK && forward in AUDIO_FORWARD)
    }
}
internal data class AudioChapter(val title: String, val start: Long)
internal data class AudioTrack(
    val name: String, val file: String, val hash: String, val duration: Long,
    val chapters: List<AudioChapter> = emptyList()
)
internal data class AudioBook(
    val id: String, val title: String, val author: String, val tracks: List<AudioTrack>,
    val position: Long = 0L, val opened: Long = 0L, val finished: Boolean = false,
    val titlePreferences: AudioPreferences? = null
) {
    val duration: Long get() = tracks.sumOf { it.duration }
    val fraction: Float get() = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val continueListening: Boolean get() = !finished && opened > 0 && position > 0
    fun progress(at: Long, now: Long, completed: Boolean): AudioBook = copy(
        position = at.coerceIn(0L, duration), opened = maxOf(now, opened), finished = completed
    )
}
internal data class AudioLibrary(val defaults: AudioPreferences = AudioPreferences(), val books: List<AudioBook> = emptyList()) {
    fun recent(): List<AudioBook> = books.filter { it.continueListening }
        .sortedWith(compareByDescending<AudioBook> { it.opened }.thenBy { it.id })
    fun find(id: String): AudioBook? = books.firstOrNull { it.id == id }
}
internal data class AudioLocation(val index: Int, val offset: Long)
internal class AudioTimeline(private val tracks: List<AudioTrack>) {
    init {
        require(tracks.isNotEmpty() && tracks.size <= MAX_AUDIO_TRACKS)
        require(tracks.all { it.duration in 1..MAX_AUDIO_DURATION })
        require(tracks.sumOf { it.duration } <= MAX_AUDIO_DURATION)
    }
    private val starts = tracks.runningFold(0L) { sum, track -> sum + track.duration }
    val duration = starts.last()
    val chapters: List<AudioChapter> = tracks.flatMapIndexed { index, track ->
        val markers = track.chapters.filter { it.start in 0 until track.duration }.distinctBy { it.start }.sortedBy { it.start }
        val valid = if (markers.firstOrNull()?.start == 0L) markers else listOf(AudioChapter(track.name, 0L)) + markers
        valid.map { it.copy(start = starts[index] + it.start) }
    }
    fun locate(position: Long): AudioLocation {
        val safe = position.coerceIn(0L, duration)
        val index = tracks.indices.last { starts[it] <= safe }
        return AudioLocation(index, safe - starts[index])
    }
    fun global(index: Int, offset: Long): Long {
        val safe = index.coerceIn(tracks.indices)
        return starts[safe] + offset.coerceIn(0L, tracks[safe].duration)
    }
    fun previous(position: Long): Long = chapters.lastOrNull { it.start < position - 3000 }?.start ?: 0L
    fun next(position: Long): Long = chapters.firstOrNull { it.start > position }?.start ?: duration
    fun chapter(position: Long): AudioChapter = chapters.lastOrNull { it.start <= position } ?: chapters.first()
}
internal fun audioClock(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
/** Numeric chunks compare by digit count and lexicographic value, not integer conversion. */
internal val audioNaturalOrder = Comparator<String> { a, b ->
    val regex = Regex("[0-9]+|[^0-9]+")
    val left = regex.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
    val right = regex.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
    var result = 0
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]; val y = right[i]
        result = if (x.first().isDigit() && y.first().isDigit()) {
            val xn = x.trimStart('0'); val yn = y.trimStart('0')
            xn.length.compareTo(yn.length).takeIf { it != 0 } ?: xn.compareTo(yn)
        } else x.compareTo(y)
        if (result != 0) break
    }
    if (result == 0) result = left.size.compareTo(right.size)
    if (result == 0) result = a.compareTo(b)
    result
}
internal fun audioMatches(book: AudioBook, query: String): Boolean =
    query.trim().split(Regex("\\s+")).all { term -> "${book.title} ${book.author}".contains(term, ignoreCase = true) }
