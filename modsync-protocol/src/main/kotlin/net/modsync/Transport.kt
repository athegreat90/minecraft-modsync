package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

class Api(val endpoint: String, val token: String? = null, private val component: String = "client") {
    private val base = URI(endpoint.trimEnd('/')).also {
        require(it.scheme == "https" && it.host != null && it.userInfo == null && it.query == null && it.fragment == null) { "An HTTPS endpoint without credentials, query or fragment is required" }
    }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun request(path: String, method: String = "GET", body: String? = null): String {
        require(path.startsWith("/api/v1/"))
        val builder = HttpRequest.newBuilder(URI(base.toString() + path)).timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json").header("X-ModSync-Protocol", PROTOCOL.toString()).header("X-ModSync-Version", COMPONENT_VERSION)
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
    fun capabilities() = wire.decodeFromString<Capabilities>(request("/api/v1/info")).also { require(it.protocol == PROTOCOL && versionAtLeast(COMPONENT_VERSION, if (component == "agent") it.minimumAgent else it.minimumClient)) { "Incompatible protocol or component version" } }
}
fun secureWrite(path: Path, text: String) {
    Files.createDirectories(path.toAbsolutePath().parent)
    val temp = Files.createTempFile(path.toAbsolutePath().parent, ".modsync-", ".tmp")
    try {
        if (Files.getFileStore(temp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(temp, text)
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
}
