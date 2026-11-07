package de.alexandermora.modsync.fixtures

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Request received by a [TestHttpsServer].
 *
 * @property method HTTP method.
 * @property path Request path without query.
 * @property body Request body as UTF-8 text.
 */
data class TestRequest(val method: String, val path: String, val body: String)

/**
 * Response of a [TestHttpsServer] handler.
 *
 * @property status HTTP status code.
 * @property body Response body; empty sends no content.
 */
data class TestReply(val status: Int = 200, val body: String = "")

/**
 * In-process HTTPS server with a throw-away self-signed certificate for 127.0.0.1.
 *
 * It installs its trust material as the JVM default [SSLContext] so unmodified HTTP clients connect to it,
 * and restores the previous default on [close]. Requests are recorded as `METHOD path` for assertions.
 *
 * @param workDir Directory that receives the generated keystore.
 * @property handler Produces the reply for each received request.
 */
class TestHttpsServer(workDir: Path, private val handler: (TestRequest) -> TestReply) : AutoCloseable {
    /** Default [SSLContext] to restore on close. */
    private val previous: SSLContext = SSLContext.getDefault()

    /** Underlying JDK HTTPS server. */
    private val server: HttpsServer

    /** Recorded requests as `METHOD path`, in arrival order. */
    val requests = mutableListOf<String>()

    /** Base URL of the running server, such as `https://127.0.0.1:port`. */
    val endpoint: String

    init {
        val keystore = workDir.resolve("test-keystore.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val process =
            ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias",
                "test",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=127.0.0.1",
                "-ext",
                "san=ip:127.0.0.1,dns:localhost",
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit"
            ).redirectErrorStream(true).start()
        check(process.waitFor() == 0) { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keystore).use { load(it, "changeit".toCharArray()) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(keys.keyManagers, trust.trustManagers, null) }
        SSLContext.setDefault(context)
        server = HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { serve(it) }
        server.start()
        endpoint = "https://127.0.0.1:${server.address.port}"
    }

    /**
     * Records one request and answers it with the handler's reply.
     *
     * @param exchange The incoming exchange.
     */
    private fun serve(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        val path = exchange.requestURI.path
        requests += "${exchange.requestMethod} $path"
        val reply = handler(TestRequest(exchange.requestMethod, path, body))
        val bytes = reply.body.toByteArray()
        exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }

    /** Stops the server and restores the previous default [SSLContext]. */
    override fun close() {
        server.stop(0)
        SSLContext.setDefault(previous)
    }
}
