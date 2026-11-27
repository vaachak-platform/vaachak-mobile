package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.vaachak.reader.core.domain.model.AiTextProvider
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Non-secret context binding for callers that already own book/highlight data. */
class AiVaultScope(val vaultId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiVaultScope>
}

internal enum class AiRequestKind { TEXT, IMAGE }
internal class AiOfflineException : IllegalStateException("Offline Mode is enabled.")

/** Deliberately not a data class: never render or log credential fields. */
internal class AiRequestConfig(
    val provider: AiTextProvider,
    val url: String,
    val model: String,
    val credential: String,
    val invalidated: Flow<Boolean>,
    val dispatchAllowed: () -> Boolean = { true }
)

internal suspend fun <T> AiRequestConfig.runGuarded(action: suspend () -> T): T = coroutineScope {
    if (invalidated.first()) throw CancellationException("AI request context changed")
    val work = async(start = CoroutineStart.LAZY) {
        if (!dispatchAllowed() || invalidated.first()) throw CancellationException("AI request context changed")
        action()
    }
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        if (invalidated.firstOrNull { it } == true) {
            work.cancel(CancellationException("AI request context changed"))
        }
    }
    try {
        val result = work.await()
        if (!dispatchAllowed() || invalidated.first()) throw CancellationException("AI request context changed")
        result
    } finally {
        watcher.cancel()
    }
}
