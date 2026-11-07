package de.alexandermora.modsync.protocol

import de.alexandermora.modsync.domain.*
import kotlinx.serialization.decodeFromString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/**
 * HTTPS client for the ModSync control plane.
 *
 * Only HTTPS endpoints without credentials, query or fragment are accepted; TLS verification is never
 * relaxed and redirects are never followed.
 *
 * @property endpoint Base URL of the server.
 * @property token Bearer token sent with every request, or null for public calls.
 * @property component Component name (`agent` or `client`) used to pick the applicable minimum version.
 */
class Api(val endpoint: String, val token: String? = null, private val component: String = "client") {
    private val base =
        URI(endpoint.trimEnd('/')).also {
            require(it.scheme == "https" && it.host != null && it.userInfo == null && it.query == null && it.fragment == null) { "An HTTPS endpoint without credentials, query or fragment is required" }
        }
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    /**
     * Sends one request to an `/api/v1/` path and returns the body.
     *
     * Redirects are never followed. Fails on a non-2xx status or a response above 8 MiB.
     *
     * @param path Request path; must start with `/api/v1/`.
     * @param method HTTP method.
     * @param body JSON request body, or null for none.
     * @return Response body as UTF-8 text.
     */
    fun request(path: String, method: String = "GET", body: String? = null): String {
        require(path.startsWith("/api/v1/"))
        val builder =
            HttpRequest
                .newBuilder(URI(base.toString() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("X-ModSync-Protocol", PROTOCOL.toString())
                .header("X-ModSync-Version", COMPONENT_VERSION)
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (body != null) builder.header("Content-Type", "application/json")
        val response = client.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { stream ->
            val bytes = stream.readNBytes(8 * 1024 * 1024 + 1)
            require(bytes.size <= 8 * 1024 * 1024) { "API response too large" }
            check(response.statusCode() in 200..299) { "API request failed: HTTP ${response.statusCode()}" }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    /**
     * Fetches server capabilities and checks compatibility.
     *
     * Fails unless the protocol matches and this component's version satisfies the server's minimum.
     *
     * @return The server's capabilities.
     */
    fun capabilities() = wire.decodeFromString<Capabilities>(request("/api/v1/info")).also { require(it.protocol == PROTOCOL && versionAtLeast(COMPONENT_VERSION, if (component == "agent") it.minimumAgent else it.minimumClient)) { "Incompatible protocol or component version" } }
}

/**
 * Writes a file atomically via a sibling temp file.
 *
 * The file is owner-only (`rw-------`) where POSIX permissions exist; missing parent directories are created.
 *
 * @param path Destination file.
 * @param text Content to write.
 */
fun secureWrite(path: Path, text: String) {
    Files.createDirectories(path.toAbsolutePath().parent)
    val temp = Files.createTempFile(path.toAbsolutePath().parent, ".modsync-", ".tmp")
    try {
        if (Files.getFileStore(temp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(temp, text)
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}
