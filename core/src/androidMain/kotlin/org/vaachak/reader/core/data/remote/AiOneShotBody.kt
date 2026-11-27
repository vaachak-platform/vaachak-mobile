package org.vaachak.reader.core.data.remote

import okhttp3.RequestBody
import okio.BufferedSink

/** Disable OkHttp follow-up replay of a quota-bearing inference POST. */
internal fun RequestBody.oneShotAiBody(): RequestBody = object : RequestBody() {
    override fun contentType() = this@oneShotAiBody.contentType()
    override fun contentLength() = this@oneShotAiBody.contentLength()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = this@oneShotAiBody.writeTo(sink)
}
