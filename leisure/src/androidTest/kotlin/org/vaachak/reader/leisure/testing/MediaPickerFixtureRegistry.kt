package org.vaachak.reader.leisure.testing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat

/** Delivers actual contract results without opening an external picker; only used by tests. */
internal class MediaPickerFixtureRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry get() = this
    private var pending: Pair<Int, String>? = null

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
        options: ActivityOptionsCompat?) {
        check(pending == null)
        pending = requestCode to contract.javaClass.simpleName
    }

    fun complete(expectedContract: String, uri: Uri?) {
        val (code, contract) = checkNotNull(pending)
        check(contract == expectedContract) { "Unexpected picker contract: $contract" }
        pending = null
        check(dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(it) }))
    }
}

/** Publish only test-owned synthetic media; the caller deletes the returned URI in finally. */
internal fun publishPickerFixture(context: android.content.Context, name: String, mime: String,
    bytes: ByteArray): Uri {
    check(android.os.Build.VERSION.SDK_INT >= 29) { "Picker fixture needs API 29+" }
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/VaachakTest")
        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
    try {
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        val ready = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }
        check(resolver.update(uri, ready, null, null) == 1)
        return uri
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
