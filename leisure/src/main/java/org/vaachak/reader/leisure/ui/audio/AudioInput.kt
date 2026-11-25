package org.vaachak.reader.leisure.ui.audio

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AudioInput(val name: String, val open: () -> InputStream)

internal suspend fun copyAudioInput(source: AudioInput, file: File, limit: Long): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    source.open().use { input ->
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                check(count > 0 && total <= limit - count) { "Audio file exceeds import size limit" }
                total += count
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }
    require(total > 0) { "Empty audio file" }
    return total to digest.digest().joinToString("") { "%02x".format(it) }
}
