package net.modsync

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

@Serializable enum class Classification { SERVER_ONLY, CLIENT_REQUIRED, CLIENT_OPTIONAL, CLIENT_ONLY, BOTH_REQUIRED, UNKNOWN }
@Serializable enum class DependencyKind { REQUIRED, OPTIONAL, INCOMPATIBLE, EMBEDDED }
@Serializable enum class OperationKind { INSTALL, UPDATE, KEEP, DISABLE, REMOVE_MANAGED, SKIP, CONFLICT, MANUAL_ACTION_REQUIRED }
@Serializable data class Evidence(val source: String, val detail: String)
@Serializable data class ModEntry(
    val fileName: String, val sha512: String, val size: Long, val modIds: List<String> = emptyList(),
    val classification: Classification = Classification.UNKNOWN, val evidence: List<Evidence> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)
@Serializable data class Dependency(val projectId: String, val versionId: String? = null, val kind: DependencyKind = DependencyKind.REQUIRED)
@Serializable data class Artifact(
    val projectId: String, val versionId: String, val fileName: String, val sha512: String, val size: Long,
    val url: String? = null, val minecraftVersions: Set<String>, val loaders: Set<String>,
    val classification: Classification, val dependencies: List<Dependency> = emptyList(),
    val evidence: List<Evidence> = emptyList()
)
@Serializable data class Manifest(
    val serverId: String, val revision: Long, val minecraftVersion: String, val loader: String,
    val loaderVersion: String? = null, val artifacts: List<Artifact>, val createdAt: String,
    val inventoryDigest: String, val policyRevision: Long = 0
)
@Serializable data class PersonalProfile(val id: String, val name: String, val artifacts: List<Artifact>)
@Serializable data class PlanOperation(val kind: OperationKind, val fileName: String, val reason: String, val artifact: Artifact? = null)
@Serializable data class SyncPlan(val manifest: Manifest, val operations: List<PlanOperation>, val localDigest: String, val includeOptional: Boolean = false) {
    val blocked: Boolean get() = operations.any { it.kind in setOf(OperationKind.CONFLICT, OperationKind.MANUAL_ACTION_REQUIRED) }
}
fun uuid(value: String): String = UUID.fromString(value).toString()
fun sha512(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512").digest(bytes).toHex()
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
fun validHash(hash: String) = hash.matches(Regex("[0-9a-f]{128}"))
fun safeFileName(name: String): Boolean = name.isNotBlank() && name.length <= 240 && name !in setOf(".", "..") &&
    name.none { it == '/' || it == '\\' || it in "<>:\"|?*" || it.code < 32 || it.code == 127 } && name.endsWith(".jar", ignoreCase = true) &&
    !name.substringBefore('.').uppercase().matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
fun inventoryDigest(entries: List<ModEntry>): String = sha512(entries.sortedBy { it.fileName }.joinToString("\n") {
    "${it.fileName}\u0000${it.sha512}\u0000${it.size}"
}.toByteArray(Charsets.UTF_8))
