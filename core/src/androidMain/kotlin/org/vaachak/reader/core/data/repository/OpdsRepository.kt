/*
 *  Copyright (c) 2026 Piyush Daiya
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all
 *  * copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  * SOFTWARE.
 */

package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.readium.r2.opds.OPDS1Parser
import org.readium.r2.opds.OPDS2Parser
import org.readium.r2.shared.opds.ParseData
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Try.Failure
import org.readium.r2.shared.util.Try.Success
import org.vaachak.reader.core.data.local.OpdsDao
import org.vaachak.reader.core.domain.model.OpdsEntity
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Repository for handling OPDS (Open Publication Distribution System) feeds.
 *
 * This class manages all network interactions with OPDS servers, including fetching and parsing
 * catalogs, handling authentication, managing cookies, and downloading publications. It features
 * robust error handling, support for both OPDS 1.x (XML) and 2.0 (JSON) formats, and the
 * ability to connect to servers with self-signed or invalid SSL certificates.
 *
 * @param opdsDao The Data Access Object for storing and retrieving OPDS feed configurations.
 */
@Singleton
class OpdsRepository @Inject constructor(
    private val opdsDao: OpdsDao
) {
    // User Agents
    private val UA_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val UA_CALIBRE = "Stanza/3.2 iPhone/5.1"

    // In-Memory Cookie Store
    private val cookieStore = HashMap<String, List<Cookie>>()

    // --- CLIENTS ---
    private val safeClient: OkHttpClient = createClient(unsafe = false)
    private val unsafeClient: OkHttpClient = createClient(unsafe = true)

    // We observe the catalogs as a Flow
    val catalogs: Flow<List<OpdsEntity>> = opdsDao.getAllFeeds()
        .onStart {
            // This runs the moment the UI starts observing the catalog list
            val count = opdsDao.getFeedsCount()
            if (count == 0) {
                val newFeed = OpdsEntity(title = "Project Gutenberg", url = "https://gutendex.com/books")
                val newFeed2 = OpdsEntity(title = "ManyBooks", url = "https://manybooks.net/opds")
                opdsDao.insertFeed(newFeed)
                opdsDao.insertFeed(newFeed2)
            }

        }


    /**
     * Creates and configures an [OkHttpClient] for network requests.
     *
     * This function sets up timeouts, redirection policies, a persistent cookie jar, and a
     * network interceptor that adds browser-like headers to every request. It can also
     * configure the client to trust all SSL certificates, which is useful for connecting
     * to local servers (like Calibre) with self-signed certificates.
     *
     * @param unsafe If `true`, the client will be configured to bypass SSL certificate verification.
     * @return A configured [OkHttpClient] instance.
     */
    private fun createClient(unsafe: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val existing = cookieStore[url.host] ?: emptyList()
                    val updated = ArrayList(existing)
                    updated.addAll(cookies)
                    cookieStore[url.host] = updated
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            // Browser Headers Interceptor
            .addNetworkInterceptor(Interceptor { chain ->
                val original = chain.request()
                val url = original.url.toString()

                // Calibre Detection (Local IP)
                val isLocal = url.contains("192.168.") || url.contains("10.") || url.contains(".local")
                val userAgent = if (isLocal) UA_CALIBRE else UA_CHROME

                val requestBuilder = original.newBuilder()
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,application/opds+json,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")

                chain.proceed(requestBuilder.build())
            })

        if (unsafe) {
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return builder.build()
    }

    /**
     * Finds the most relevant stored [OpdsEntity] for a given URL.
     *
     * This "smart" lookup tries to match the URL exactly, with/without a trailing slash,
     * and also checks for a base `/opds` path. It automatically assumes local network
     * addresses can use insecure connections. This allows a single stored feed configuration
     * to apply to multiple sub-pages of that feed.
     *
     * @param urlStr The URL of the feed to look up.
     * @return The matching [OpdsEntity] from the database, or a temporary entity for local
     *         networks, or `null` if no match is found.
     */
    private suspend fun findEffectiveFeed(urlStr: String): OpdsEntity? {
        val cleanUrl = urlStr.trim()
        var feed = opdsDao.getFeedByUrl(cleanUrl)
            ?: opdsDao.getFeedByUrl(cleanUrl.removeSuffix("/"))
            ?: opdsDao.getFeedByUrl("$cleanUrl/")
        if (feed != null) return feed

        if (cleanUrl.contains("/opds")) {
            val base = cleanUrl.substringBefore("/opds") + "/opds"
            feed = opdsDao.getFeedByUrl(base) ?: opdsDao.getFeedByUrl("$base/")
            if (feed != null) return feed
        }

        try {
            val host = URL(cleanUrl).host
            if (host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local")) {
                return OpdsEntity(title = "Local", url = cleanUrl, allowInsecure = true)
            }
        } catch (e: Exception) {}
        return null
    }

    /**
     * Executes an HTTP GET request for a given URL.
     *
     * It uses [findEffectiveFeed] to determine the correct client (safe vs. unsafe) and to
     * attach any necessary authentication credentials to the request.
     *
     * @param url The URL to fetch.
     * @return The OkHttp [Response] object.
     */
    private suspend fun executeRequest(url: String): Response {
        val storedFeed = findEffectiveFeed(url)
        val allowInsecure = storedFeed?.allowInsecure ?: false
        val client = if (allowInsecure) unsafeClient else safeClient

        val requestBuilder = Request.Builder().url(url)

        if (storedFeed?.username != null && storedFeed.password != null) {
            requestBuilder.header("Authorization", Credentials.basic(storedFeed.username, storedFeed.password))
        }

        return withContext(Dispatchers.IO) {
            client.newCall(requestBuilder.build()).execute()
        }
    }

    /**
     * Fetches and parses an OPDS feed from the given URL.
     *
     * This function handles the entire process of fetching, validating, and parsing an OPDS feed.
     * It automatically detects the feed type (OPDS 2.0/JSON or OPDS 1.x/XML) and uses the
     * appropriate parser. It also includes workarounds for common issues like server-side
     * login pages (returning HTML) and malformed XML from certain providers.
     *
     * @param rawUrl The URL of the OPDS feed to parse.
     * @return A [Try] object which is a [Success] containing the parsed [ParseData]
     *         or a [Failure] containing an [Exception].
     */
    suspend fun parseFeed(rawUrl: String): Try<ParseData, Exception> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = rawUrl.trim()
            val absoluteUrl = AbsoluteUrl(cleanUrl)
                ?: return@withContext Failure<ParseData, Exception>(Exception("Invalid URL"))

            val response = try {
                executeRequest(cleanUrl)
            } catch (e: Exception) {
                return@withContext Failure<ParseData, Exception>(e)
            }

            if (!response.isSuccessful) {
                val msg = "HTTP ${response.code}: ${response.message}"
                Timber.e( msg)
                response.close()
                return@withContext Failure<ParseData, Exception>(Exception(msg))
            }

            val bodyBytes = response.body?.bytes()
            response.close()

            if (bodyBytes == null || bodyBytes.isEmpty()) {
                return@withContext Failure<ParseData, Exception>(Exception("Empty Response"))
            }

            val contentStr = String(bodyBytes, Charset.forName("UTF-8")).trim()
            if (contentStr.contains("<!DOCTYPE html", true) || contentStr.contains("<html", true)) {
                return@withContext Failure<ParseData, Exception>(Exception("Server returned HTML (Login/Block Page)."))
            }

            // Apply ManyBooks Fix
            val safeBytes = sanitizeXml(bodyBytes)

            // 1. Try OPDS 2.0 (JSON)
            try {
                val opds2 = OPDS2Parser.parse(safeBytes, absoluteUrl)
                if (opds2 is ParseData) return@withContext Success(opds2)
                if (opds2 is Success<*, *>) return@withContext Success(opds2.value as ParseData)
            } catch (_: Exception) {}

            // 2. Try OPDS 1.x (XML)
            try {
                // BOM Stripping
                val cleanBytes = if (safeBytes.size >= 3 && safeBytes[0] == 0xEF.toByte() && safeBytes[1] == 0xBB.toByte() && safeBytes[2] == 0xBF.toByte()) {
                    safeBytes.copyOfRange(3, safeBytes.size)
                } else safeBytes

                val result = OPDS1Parser.parse(cleanBytes, absoluteUrl)

                return@withContext when (result) {
                    is ParseData -> Success(result)
                    is Success<*, *> -> Success(result.value as ParseData)
                    is Failure<*, *> -> Failure(Exception("XML Parser Failed: ${result.value}"))
                    else -> {
                        if (result is ParseData) Success(result)
                        else Failure(Exception("Unknown Parser State"))
                    }
                }
            } catch (e: Exception) {
                return@withContext Failure<ParseData, Exception>(Exception("XML Error: ${e.message}"))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Failure<ParseData, Exception>(e)
        }
    }

    /**
     * Downloads a publication file from a given URL and saves it to a local file.
     *
     * This method handles the download process, using the appropriate client and authentication
     * details based on the feed's configuration.
     *
     * @param downloadUrl The direct URL of the publication to download.
     * @param destinationFile The local [File] where the downloaded content will be saved.
     * @param originalFeedUrl The URL of the OPDS feed from which the download link was obtained.
     *                        This is used to look up the correct authentication and security settings.
     * @return `true` if the download was successful, `false` otherwise.
     */
    suspend fun downloadPublication(downloadUrl: String, destinationFile: File, originalFeedUrl: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val feedUrlForConfig = originalFeedUrl ?: downloadUrl
            val storedFeed = findEffectiveFeed(feedUrlForConfig)
            val allowInsecure = storedFeed?.allowInsecure ?: false
            val client = if (allowInsecure) unsafeClient else safeClient

            val requestBuilder = Request.Builder().url(downloadUrl)

            if (storedFeed?.username != null && storedFeed.password != null) {
                requestBuilder.header("Authorization", Credentials.basic(storedFeed.username, storedFeed.password))
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (response.isSuccessful && response.body != null) {
                val inputStream = response.body!!.byteStream()
                val outputStream = FileOutputStream(destinationFile)
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                response.close()
                return@withContext true
            } else {
                response.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    /**
     * Sanitizes raw XML byte array to fix known formatting issues from specific providers.
     *
     * This helper function currently targets an issue with the ManyBooks feed where titles
     * are wrapped in an unnecessary XHTML `<div>`. Removing this improves parsing reliability.
     *
     * @param rawXml The raw byte array of the XML content.
     * @return A sanitized byte array ready for parsing. Returns the original array if sanitization fails.
     */
    private fun sanitizeXml(rawXml: ByteArray): ByteArray {
        try {
            val xmlString = String(rawXml, Charset.forName("UTF-8"))

            // 1. Remove XHTML Namespace in titles (ManyBooks specific issue)
            // Replaces <title...><div...>Title</div></title> with <title>Title</title>
            var cleanXml = xmlString.replace(Regex("<title[^>]*>\\s*<div[^>]*>(.*?)</div>\\s*</title>", RegexOption.DOT_MATCHES_ALL)) {
                "<title>${it.groupValues[1].trim()}</title>"
            }

            return cleanXml.toByteArray(Charset.forName("UTF-8"))
        } catch (e: Exception) {
            // If text conversion fails, return original bytes
            return rawXml
        }
    }

    //UI rewrite
    // Expose the Flow from DAO
    val allFeeds: Flow<List<OpdsEntity>> = opdsDao.getAllFeeds()

    suspend fun insertFeed(feed: OpdsEntity): Result<String> = withContext(Dispatchers.IO) {
        try {
            opdsDao.insertFeed(feed)
            Result.success("Catalog has been saved")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
    suspend fun updateFeed(feed: OpdsEntity): Result<String> = withContext(Dispatchers.IO) {
        try {
            opdsDao.updateFeed(feed)
            Result.success("Catalog has been updated")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
    suspend fun deleteFeed(feed: OpdsEntity): Result<String> = withContext(Dispatchers.IO) {
        try {
            opdsDao.deleteFeed(feed)
            Result.success("Catalog has been deleted")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}