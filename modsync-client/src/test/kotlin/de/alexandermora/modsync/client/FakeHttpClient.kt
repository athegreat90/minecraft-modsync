package de.alexandermora.modsync.client

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

/**
 * [HttpClient] that answers every request with a fixed status and body, so download code runs without a network.
 *
 * Only synchronous sends with an [InputStream] body handler are supported.
 *
 * @property status HTTP status returned for every request.
 * @property body Response body bytes.
 */
class FakeHttpClient(private val status: Int, private val body: ByteArray) : HttpClient() {
    /** Requests received, in order. */
    val requests = mutableListOf<HttpRequest>()

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    @Suppress("UNCHECKED_CAST")
    override fun <T> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): HttpResponse<T> {
        requests += request
        return object : HttpResponse<InputStream> {
            override fun statusCode() = status

            override fun request() = request

            override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()

            override fun headers() = HttpHeaders.of(emptyMap()) { _, _ -> true }

            override fun body(): InputStream = ByteArrayInputStream(body)

            override fun sslSession(): Optional<SSLSession> = Optional.empty()

            override fun uri(): URI = request.uri()

            override fun version() = Version.HTTP_1_1
        } as HttpResponse<T>
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?
    ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()
}
