package de.alexandermora.modsync.server

import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Login
import de.alexandermora.modsync.protocol.RefreshRequest
import de.alexandermora.modsync.protocol.wire
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * Minimal dashboard browser: a cookie jar and no automatic redirects, so tests see every redirect and status.
 *
 * @property base Base URL of the running application.
 */
internal class TestBrowser(
    private val base: String
) {
    private val cookies = CookieManager()
    private val http =
        HttpClient
            .newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    /** Sends a GET and returns the response. */
    fun get(path: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("$base$path")).build(), HttpResponse.BodyHandlers.ofString())

    /** Sends a form POST, adding the CSRF token when [csrf] is not null. */
    fun post(
        path: String,
        form: Map<String, String>,
        csrf: String?
    ): HttpResponse<String> {
        val fields = form + (csrf?.let { mapOf("_csrf" to it) } ?: emptyMap())
        val body = fields.entries.joinToString("&") { "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        val request =
            HttpRequest
                .newBuilder(URI("$base$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** Extracts the CSRF token rendered in a page. */
    fun csrf(page: String): String = Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: error("No CSRF token in page")

    /** Fetches [path] and returns the CSRF token of the rendered page. */
    fun csrfOf(path: String): String = csrf(get(path).body())

    /** Submits the login form and returns the response. */
    fun signIn(
        user: String,
        password: String
    ): HttpResponse<String> = post("/login", mapOf("username" to user, "password" to password), csrfOf("/login"))

    /** Returns the current session cookie value, or null when there is none. */
    fun sessionId(): String? =
        cookies.cookieStore.cookies
            .firstOrNull { it.name == "JSESSIONID" }
            ?.value
}

/** Returns the redirect target of a response, or an empty string when it is not a redirect. */
internal fun HttpResponse<*>.location(): String = headers().firstValue("Location").map { URI(it).let { uri -> uri.path + (uri.query?.let { query -> "?$query" } ?: "") } }.orElse("")

/**
 * Calls the bearer-authenticated API with the protocol headers every component sends.
 *
 * @property base Base URL of the running application.
 */
internal class ApiProbe(
    private val base: String
) {
    private val http = HttpClient.newHttpClient()

    /** Sends an API request and returns the status code. */
    fun status(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null
    ): Int {
        val builder =
            HttpRequest
                .newBuilder(URI("$base$path"))
                .header("X-ModSync-Protocol", "1")
                .header("X-ModSync-Version", "0.3.1")
                .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    /** Logs in and returns the status code with the issued credentials, which are null unless the status is 200. */
    fun login(
        user: String,
        password: String,
        device: String = UUID.randomUUID().toString()
    ): Pair<Int, Credentials?> {
        val body = wire.encodeToString(Login.serializer(), Login(user, password, device))
        val builder =
            HttpRequest
                .newBuilder(URI("$base/api/v1/auth/login"))
                .header("X-ModSync-Protocol", "1")
                .header("X-ModSync-Version", "0.3.1")
                .header("Content-Type", "application/json")
        val response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to if (response.statusCode() == 200) wire.decodeFromString(Credentials.serializer(), response.body()) else null
    }

    /** Redeems a refresh token and returns the status code. */
    fun refresh(token: String): Int = status("POST", "/api/v1/auth/refresh", body = wire.encodeToString(RefreshRequest.serializer(), RefreshRequest(token)))
}
