package net.modsync

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class Modrinth(private val endpoint: URI = URI("https://api.modrinth.com/v2/")) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, JsonElement>>()
    @Synchronized private fun call(path: String, body: String? = null): JsonElement {
        val key = path + (body ?: "")
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < 3600000 }?.let { return it.second }
        repeat(4) { attempt ->
            val builder = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Minecraft-ModSync/0.1.0 (self-hosted mod inventory)")
            if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val text = response.body().use { stream -> val bytes = stream.readNBytes(8 * 1024 * 1024 + 1); require(bytes.size <= 8 * 1024 * 1024); bytes.toString(Charsets.UTF_8) }
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                if (attempt == 3) error("Modrinth temporarily unavailable")
                val delay = response.headers().firstValue("Retry-After").orElse("").toLongOrNull()?.coerceIn(1, 30)?.times(1000) ?: (500L shl attempt)
                Thread.sleep(delay + kotlin.random.Random.nextLong(250)); return@repeat
            }
            check(response.statusCode() in 200..299) { "Modrinth HTTP ${response.statusCode()}" }
            val parsed = wire.parseToJsonElement(text)
            if (cache.size >= 4096) cache.clear()
            cache[key] = System.currentTimeMillis() to parsed
            return parsed
        }
        error("Retry limit exceeded")
    }
    fun identify(hashes: List<String>): Map<String, JsonObject> {
        require(hashes.all(::validHash))
        return hashes.distinct().chunked(100).flatMap { batch ->
            call("version_files", buildJsonObject { put("algorithm", "sha512"); put("hashes", JsonArray(batch.map(::JsonPrimitive))) }.toString()).jsonObject.entries.map { it.key to it.value.jsonObject }
        }.toMap()
    }
    fun version(id: String): JsonObject = call("version/${component(id)}").jsonObject
    fun project(id: String): JsonObject = call("project/${component(id)}").jsonObject
    fun compatibleVersions(project: String, minecraft: String, loader: String): List<JsonObject> =
        call("project/${component(project)}/version").jsonArray.map { it.jsonObject }.filter { v -> v.getValue("game_versions").jsonArray.any { it.jsonPrimitive.content == minecraft } && v.getValue("loaders").jsonArray.any { it.jsonPrimitive.content == loader } }
    private fun component(value: String): String { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))); return value }
    fun artifact(hash: String, classification: Classification, evidence: List<Evidence>): Artifact {
        val v = identify(listOf(hash))[hash] ?: error("Artifact is not available from Modrinth; manual resolution required")
        val f = v.getValue("files").jsonArray.map { it.jsonObject }.first { it.getValue("hashes").jsonObject["sha512"]?.jsonPrimitive?.content == hash }
        val deps = v.getValue("dependencies").jsonArray.map { it.jsonObject }.mapNotNull { d ->
            val versionId = d["version_id"]?.jsonPrimitive?.contentOrNull
            val projectId = d["project_id"]?.jsonPrimitive?.contentOrNull ?: versionId?.let { version(it).getValue("project_id").jsonPrimitive.content }
            projectId?.let { Dependency(it, versionId, when(d["dependency_type"]?.jsonPrimitive?.content) { "required" -> DependencyKind.REQUIRED; "incompatible" -> DependencyKind.INCOMPATIBLE; "embedded" -> DependencyKind.EMBEDDED; else -> DependencyKind.OPTIONAL }) }
        }
        return Artifact(v.getValue("project_id").jsonPrimitive.content, v.getValue("id").jsonPrimitive.content, f.getValue("filename").jsonPrimitive.content, hash,
            f.getValue("size").jsonPrimitive.long, f.getValue("url").jsonPrimitive.content,
            v.getValue("game_versions").jsonArray.map { it.jsonPrimitive.content }.toSet(), v.getValue("loaders").jsonArray.map { it.jsonPrimitive.content }.toSet(), classification, deps, evidence)
    }
}
