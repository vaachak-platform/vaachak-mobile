package org.vaachak.reader.core.data.repository

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Actual loopback TLS sockets; generate a temporary fixture certificate, trust only that certificate.
 * This does not modify Android/production trust configuration or use any external network. */
class OpdsTlsRedirectTest {
    private data class Observed(val path: String, val auth: String?, val cookie: String?)

    @Test fun actualTlsRedirectsAndCookieIsolation() = runTest {
        val dir = Files.createTempDirectory("opds-tls-fixture-").toFile()
        val records = Collections.synchronizedList(mutableListOf<Observed>())
        val target = File(dir, "download.epub")
        var a: HttpsServer? = null
        var b: HttpsServer? = null
        var clear: HttpServer? = null
        try {
            val (serverContext, clientContext, trustManager) = contexts(dir)
            val firstServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                httpsConfigurator = HttpsConfigurator(serverContext)
            }
            a = firstServer
            val foreignServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                httpsConfigurator = HttpsConfigurator(serverContext)
            }
            b = foreignServer
            val clearServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            clear = clearServer
            val first = "https://localhost:${firstServer.address.port}"
            val foreign = "https://localhost:${foreignServer.address.port}"
            fun observed(exchange: HttpExchange) {
                records.add(Observed(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("Cookie")))
            }
            fun ok(exchange: HttpExchange) {
                val bytes = "fixture".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            fun redirect(exchange: HttpExchange, location: String) {
                exchange.responseHeaders.add("Location", location)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            firstServer.createContext("/") { exchange ->
                observed(exchange)
                when (exchange.requestURI.path) {
                    "/seed" -> { exchange.responseHeaders.add("Set-Cookie", "session=fixture-a; Secure; Path=/"); ok(exchange) }
                    "/cross" -> redirect(exchange, "$foreign/foreign")
                    "/downgrade" -> redirect(exchange, "http://localhost:${clearServer.address.port}/clear")
                    else -> ok(exchange)
                }
            }
            foreignServer.createContext("/") { exchange ->
                observed(exchange)
                if (exchange.requestURI.path == "/back") redirect(exchange, "$first/returned") else ok(exchange)
            }
            clearServer.createContext("/") { exchange -> observed(exchange); ok(exchange) }
            firstServer.start(); foreignServer.start(); clearServer.start()
            val harness = OpdsRepositoryHarness()
            val repository = harness.repository
            val baseFactory = repository.javaClass.getDeclaredMethod("createClient").apply { isAccessible = true }
            val productionClient = baseFactory.invoke(repository) as OkHttpClient
            check(!productionClient.followSslRedirects)
            repository.setClientForTest(productionClient.newBuilder()
                .sslSocketFactory(clientContext.socketFactory, trustManager)
                .cookieJar(CookieJar.NO_COOKIES).build())
            val root = "$first/opds"
            val accountA = harness.authenticated(root, "account-a", "fixture-a")
            val accountB = harness.authenticated(root, "account-b", "fixture-b")
            val sessionA = OpdsCatalogSession(accountA)
            val sessionB = OpdsCatalogSession(accountB)
            suspend fun download(url: String, session: OpdsCatalogSession) = repository.downloadPublication(url, target, root, session)
            check(download("$first/seed", sessionA))
            check(download("$first/book-a", sessionA))
            check(download("$first/book-b", sessionB))
            check(download("$first/cross", sessionA))
            check(download("$foreign/back", sessionA))
            check(!download("$first/downgrade", sessionA))
            val seen = records.associateBy { it.path }
            check(seen["/seed"]?.auth == Credentials.basic("account-a", "fixture-a"))
            check(seen["/book-a"]?.auth == Credentials.basic("account-a", "fixture-a") && seen["/book-a"]?.cookie?.contains("session=fixture-a") == true)
            check(seen["/book-b"]?.auth == Credentials.basic("account-b", "fixture-b") && seen["/book-b"]?.cookie == null)
            for (path in listOf("/foreign", "/back", "/returned")) {
                check(seen.containsKey(path) && seen[path]?.auth == null && seen[path]?.cookie == null)
            }
            check(seen.containsKey("/downgrade") && !seen.containsKey("/clear"))
        } finally {
            a?.stop(0); b?.stop(0); clear?.stop(0)
            dir.deleteRecursively()
        }
    }

    private data class Contexts(val server: SSLContext, val client: SSLContext, val trust: X509TrustManager)

    private fun contexts(dir: File): Contexts {
        val storeFile = File(dir, "fixture.p12")
        val password = "fixture-only".toCharArray()
        val executable = File(System.getProperty("java.home"), "bin/keytool")
        val process = ProcessBuilder(executable.absolutePath, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", storeFile.absolutePath, "-storepass", String(password), "-noprompt")
            .redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        check(process.waitFor() == 0) { "Unable to create disposable TLS fixture" }
        val store = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", store.getCertificate("fixture")) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        val trust = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
        val server = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val client = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        password.fill('\u0000')
        return Contexts(server, client, trust)
    }
}
