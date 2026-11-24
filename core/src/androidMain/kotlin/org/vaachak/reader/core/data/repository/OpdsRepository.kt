package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Credentials
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
import org.vaachak.reader.core.utils.OpdsUrlUtils
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpdsRepository internal constructor(
    private val opdsDao: OpdsDao,
    private val lifecycle: OpdsCredentialLifecycle
) {
    @Inject internal constructor(opdsDao: OpdsDao, owner: AndroidOpdsCredentialLifecycle) :
        this(opdsDao, owner.lifecycle)
    private val uaChrome =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val uaCalibre = "Stanza/3.2 iPhone/5.1"

    val catalogs: Flow<List<OpdsEntity>> = opdsDao.getAllFeeds().onStart {
        try { lifecycle.prepareCatalogs() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Timber.w("Catalog recovery unavailable") }
    }

    val allFeeds: Flow<List<OpdsEntity>> = catalogs

    private var client: OkHttpClient = createClient()
    private val scopedClients = WeakHashMap<OpdsCatalogSession, OkHttpClient>()

    /** Each browser opening owns a separate, origin-bound cookie jar. */
    private fun clientForSession(session: OpdsCatalogSession?): OkHttpClient = synchronized(scopedClients) {
        if (session == null) client else scopedClients.getOrPut(session) {
            val originUrl = session.catalogUrl // Do not capture the weak map key in the value.
            client.newBuilder()
                .cookieJar(OpdsSessionCookies(originUrl))
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    if (!request.url.isHttps) throw IOException("OPDS requires HTTPS")
                    val bounded = if (OpdsAuthOrigin.sameOrigin(originUrl, request.url.toString())) request
                    else request.newBuilder().removeHeader("Authorization").removeHeader("Cookie").build()
                    chain.proceed(bounded)
                }
                .build()
        }
    }
    private var absoluteUrlResolver: (String) -> AbsoluteUrl? = { AbsoluteUrl(it) }

    internal fun setClientForTest(client: OkHttpClient) {
        synchronized(scopedClients) {
            this.client = client
            scopedClients.clear()
        }
    }

    internal fun setAbsoluteUrlResolverForTest(resolver: (String) -> AbsoluteUrl?) {
        absoluteUrlResolver = resolver
    }

    private fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .addNetworkInterceptor(Interceptor { chain ->
                val original = chain.request()
                val host = original.url.host.lowercase()

                val looksLikeSelfHosted =
                    host.contains("calibre") ||
                            host.contains("abs") ||
                            host.endsWith(".local") ||
                            host.endsWith(".home.arpa")

                val userAgent = if (looksLikeSelfHosted) uaCalibre else uaChrome

                val request = original.newBuilder()
                    .header("User-Agent", userAgent)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,application/opds+json,*/*;q=0.8"
                    )
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                chain.proceed(request)
            })
            .build()
    }

    private fun normalizeHttpsUrl(urlStr: String): String {
        val normalized = OpdsUrlUtils.normalizeHttpsUrl(urlStr)
        // Check raw and canonical URL so user-info cannot be stripped by canonicalization.
        httpsOrigin(urlStr.trim())
        httpsOrigin(normalized)
        return normalized
    }

    private suspend fun executeRequest(url: String, session: OpdsCatalogSession?): Response {
        val secureUrl = normalizeHttpsUrl(url)
        val request = Request.Builder().url(secureUrl)
        if (session == null) {
            // Compatibility path is anonymous only. No URL credential lookup or shared cookies.
            return client.newCall(request.build()).execute()
        }
        return lifecycle.dispatch(session, secureUrl) { pair ->
            val sameOrigin = OpdsAuthOrigin.sameOrigin(session.catalogUrl, secureUrl)
            if (pair != null && sameOrigin) {
                request.header("Authorization", Credentials.basic(pair.username, pair.password))
            }
            // An initially foreign request cannot re-enter the catalog with cached auth cookies.
            clientForSession(if (sameOrigin) session else null).newCall(request.build()).execute()
        }
    }

    suspend fun parseFeed(rawUrl: String, session: OpdsCatalogSession? = null): Try<ParseData, Exception> =
        withContext(Dispatchers.IO) {
        try {
            val cleanUrl = normalizeHttpsUrl(rawUrl)
            val absoluteUrl = absoluteUrlResolver(cleanUrl)
                ?: return@withContext Failure(Exception("Invalid URL"))

            val response = try {
                executeRequest(cleanUrl, session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext Failure(Exception("Catalog unavailable; unlock the device, migrate saved credentials if needed, or reopen the catalog"))
            }

            if (!response.isSuccessful) {
                val message = "HTTP ${response.code}"
                Timber.e(message)
                response.close()
                return@withContext Failure(Exception(message))
            }

            val bodyBytes = response.use { it.body?.bytes() }

            val nonNullBodyBytes = bodyBytes
                ?: return@withContext Failure(Exception("Empty response"))

            if (nonNullBodyBytes.isEmpty()) {
                return@withContext Failure(Exception("Empty response"))
            }

            val contentStr = nonNullBodyBytes.toString(Charsets.UTF_8).trim()
            if (
                contentStr.contains("<!DOCTYPE html", ignoreCase = true) ||
                contentStr.contains("<html", ignoreCase = true)
            ) {
                return@withContext Failure(
                    Exception("Server returned HTML (login page or block page).")
                )
            }

            val safeBytes = sanitizeXml(nonNullBodyBytes)

            try {
                val opds2 = OPDS2Parser.parse(safeBytes, absoluteUrl)
                return@withContext Success(opds2)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Fall through to OPDS 1.x parsing.
            }

            try {
                val cleanBytes = if (
                    safeBytes.size >= 3 &&
                    safeBytes[0] == 0xEF.toByte() &&
                    safeBytes[1] == 0xBB.toByte() &&
                    safeBytes[2] == 0xBF.toByte()
                ) {
                    safeBytes.copyOfRange(3, safeBytes.size)
                } else {
                    safeBytes
                }

                val opds1 = OPDS1Parser.parse(cleanBytes, absoluteUrl)
                return@withContext Success(opds1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext Failure(Exception("Invalid OPDS response"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return@withContext Failure(Exception(if (e is IllegalArgumentException) "Invalid URL or non-HTTPS OPDS endpoint" else "Catalog unavailable; unlock the device or reopen the catalog"))
        }
    }

    suspend fun downloadPublication(
        downloadUrl: String,
        destinationFile: File,
        originalFeedUrl: String?,
        session: OpdsCatalogSession? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val secureDownloadUrl = normalizeHttpsUrl(downloadUrl)
            if (session == null && originalFeedUrl != null) normalizeHttpsUrl(originalFeedUrl)
            val response = executeRequest(secureDownloadUrl, session)

            response.use {
                val body = it.body
                if (it.isSuccessful && body != null) {
                    body.byteStream().use { input ->
                        FileOutputStream(destinationFile).use { output -> input.copyTo(output) }
                    }
                    return@withContext true
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Timber.w("OPDS download unavailable")
        }

        return@withContext false
    }

    private fun sanitizeXml(rawXml: ByteArray): ByteArray {
        return try {
            val xmlString = rawXml.toString(Charsets.UTF_8)

            val cleanXml = xmlString.replace(
                Regex(
                    "<title[^>]*>\\s*<div[^>]*>(.*?)</div>\\s*</title>",
                    RegexOption.DOT_MATCHES_ALL
                )
            ) {
                "<title>${it.groupValues[1].trim()}</title>"
            }

            cleanXml.toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            rawXml
        }
    }

    suspend fun saveCatalog(
        existing: OpdsEntity?, title: String, url: String, action: CatalogCredentialAction,
        username: String? = null, password: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = normalizeHttpsUrl(url)
            val edit = when (action) {
                CatalogCredentialAction.KEEP -> OpdsCredentialEdit.Keep
                CatalogCredentialAction.REMOVE -> OpdsCredentialEdit.Remove
                CatalogCredentialAction.REPLACE -> OpdsCredentialEdit.Replace(OpdsCredentialPair(
                    requireNotNull(username), requireNotNull(password)))
            }
            if (existing == null) lifecycle.create(title, normalizedUrl, edit)
            else lifecycle.update(OpdsCatalogSession(existing), title, normalizedUrl, edit)
            Result.success("Catalog has been saved")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(OpdsLifecycleException())
        }
    }

    suspend fun deleteFeed(feed: OpdsEntity): Result<String> = withContext(Dispatchers.IO) {
        try {
            lifecycle.delete(OpdsCatalogSession(feed))
            Result.success("Catalog has been deleted")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(OpdsLifecycleException())
        }
    }

    suspend fun migrateLegacyCredentials(): Result<OpdsMigrationReport> = withContext(Dispatchers.IO) {
        try { Result.success(lifecycle.migrateAll()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure(OpdsLifecycleException()) }
    }
}
