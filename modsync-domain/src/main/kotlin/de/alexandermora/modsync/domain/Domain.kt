package de.alexandermora.modsync.domain

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

/** Where a mod must run: on the server, on clients, or both; [UNKNOWN] until classified. */
@Serializable enum class Classification { SERVER_ONLY, CLIENT_REQUIRED, CLIENT_OPTIONAL, CLIENT_ONLY, BOTH_REQUIRED, UNKNOWN }
/** Relationship of an artifact to another project it references. */
@Serializable enum class DependencyKind { REQUIRED, OPTIONAL, INCOMPATIBLE, EMBEDDED }
/** Action a sync plan performs on one file; only the file-changing kinds mutate the instance. */
@Serializable enum class OperationKind { INSTALL, UPDATE, KEEP, DISABLE, REMOVE_MANAGED, SKIP, CONFLICT, MANUAL_ACTION_REQUIRED }
/** How a server publishes client manifests automatically after an inventory snapshot. */
@Serializable enum class PublicationPolicy { MANUAL, TRUSTED_AUTO, REVIEW_UNKNOWN }
/** Synchronization state of one client instance relative to a server manifest. */
@Serializable enum class CoverageStatus { UP_TO_DATE, MISSING_MODS, WRONG_VERSION, INCOMPATIBLE, SYNC_PENDING, SYNCING, PENDING_GAME_EXIT, SYNC_FAILED, OFFLINE, INVENTORY_STALE, UNKNOWN }
/** Automatic synchronization permission a user granted to one client instance. */
@Serializable enum class SyncPolicy { MANUAL, AUTO_APPROVED_INSTALL, AUTO_APPROVED_UPDATE, AUTO_ALL_MANAGED }
/** Operational state a client instance reports, independent of its installed files. */
@Serializable enum class ClientActivity { IDLE, SYNC_PENDING, SYNCING, PENDING_GAME_EXIT, SYNC_FAILED }
/**
 * One piece of evidence explaining how a mod was identified or classified.
 *
 * @property source Origin of the evidence, such as a metadata file or a hash lookup.
 * @property detail Human-readable finding.
 */
@Serializable data class Evidence(val source: String, val detail: String)
/**
 * One scanned mod file in a server inventory.
 *
 * Only [fileName], [sha512] and [size] are part of the inventory digest.
 *
 * @property fileName Plain file name without directories.
 * @property sha512 Lower-case hex SHA-512 of the file.
 * @property size File size in bytes.
 * @property modIds Mod ids declared in the JAR metadata.
 * @property classification Where the mod must run.
 * @property evidence Findings that led to the classification.
 * @property metadata Additional key/value facts read from the JAR metadata.
 */
@Serializable data class ModEntry(
    val fileName: String, val sha512: String, val size: Long, val modIds: List<String> = emptyList(),
    val classification: Classification = Classification.UNKNOWN, val evidence: List<Evidence> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)
/**
 * Reference from an artifact to another Modrinth project.
 *
 * @property projectId Modrinth project id of the dependency.
 * @property versionId Pinned version id, or null when any compatible version satisfies it.
 * @property kind Relationship of the dependency.
 */
@Serializable data class Dependency(val projectId: String, val versionId: String? = null, val kind: DependencyKind = DependencyKind.REQUIRED)
/**
 * A verified, downloadable mod file that a manifest or profile pins by hash.
 *
 * @property projectId Modrinth project id.
 * @property versionId Modrinth version id.
 * @property fileName Plain `.jar` file name used on disk.
 * @property sha512 Lower-case hex SHA-512 the downloaded file must match.
 * @property size File size in bytes.
 * @property url Download URL, or null when the file is not downloadable.
 * @property minecraftVersions Minecraft versions the file supports.
 * @property loaders Mod loaders the file supports.
 * @property classification Where the mod must run.
 * @property dependencies Declared dependencies.
 * @property evidence Findings that led to the classification.
 */
@Serializable data class Artifact(
    val projectId: String, val versionId: String, val fileName: String, val sha512: String, val size: Long,
    val url: String? = null, val minecraftVersions: Set<String>, val loaders: Set<String>,
    val classification: Classification, val dependencies: List<Dependency> = emptyList(),
    val evidence: List<Evidence> = emptyList()
)
/**
 * Immutable, numbered set of artifacts a server publishes for clients.
 *
 * @property serverId Server UUID.
 * @property revision Monotonically increasing revision number.
 * @property minecraftVersion Minecraft version the artifacts target.
 * @property loader Mod loader the artifacts target.
 * @property loaderVersion Loader version, or null when not constrained.
 * @property artifacts Published artifacts.
 * @property createdAt ISO-8601 publication timestamp.
 * @property inventoryDigest Digest of the inventory snapshot the manifest was derived from.
 * @property policyRevision Revision of the publication policy in force when it was published.
 */
@Serializable data class Manifest(
    val serverId: String, val revision: Long, val minecraftVersion: String, val loader: String,
    val loaderVersion: String? = null, val artifacts: List<Artifact>, val createdAt: String,
    val inventoryDigest: String, val policyRevision: Long = 0
)
/**
 * A user's personal set of client-only artifacts, layered on top of a server manifest.
 *
 * @property id Profile UUID.
 * @property name Display name.
 * @property artifacts Client-only artifacts of the profile.
 */
@Serializable data class PersonalProfile(val id: String, val name: String, val artifacts: List<Artifact>)
/**
 * One step of a sync plan.
 *
 * @property kind Action to perform.
 * @property fileName Plain file name the action applies to.
 * @property reason Human-readable explanation of why the action was planned.
 * @property artifact Artifact to install or update, or null for actions without a download.
 */
@Serializable data class PlanOperation(val kind: OperationKind, val fileName: String, val reason: String, val artifact: Artifact? = null)
/**
 * Deterministic plan that brings a local instance in line with a manifest.
 *
 * @property manifest Manifest the plan was computed against.
 * @property operations Planned steps.
 * @property localDigest Digest of the local inventory the plan was computed from.
 * @property includeOptional Whether optional artifacts were included.
 */
@Serializable data class SyncPlan(val manifest: Manifest, val operations: List<PlanOperation>, val localDigest: String, val includeOptional: Boolean = false) {
    /** True when the plan contains a conflict or manual action and therefore must not be applied. */
    val blocked: Boolean get() = operations.any { it.kind in setOf(OperationKind.CONFLICT, OperationKind.MANUAL_ACTION_REQUIRED) }
}
/**
 * Canonicalizes a UUID string.
 *
 * @param value Text to parse.
 * @return Lower-case UUID text.
 * @throws IllegalArgumentException If [value] is not a valid UUID.
 */
fun uuid(value: String): String = UUID.fromString(value).toString()
/**
 * Hashes bytes with SHA-512.
 *
 * @param bytes Data to hash.
 * @return Lower-case hex digest.
 */
fun sha512(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512").digest(bytes).toHex()
/**
 * Encodes bytes as hex without separators.
 *
 * @receiver Bytes to encode.
 * @return Lower-case hex text.
 */
fun ByteArray.toHex(): String = let { java.util.HexFormat.of().formatHex(it) }
/**
 * Checks the shape of a SHA-512 digest.
 *
 * @param hash Candidate digest.
 * @return True only for a lower-case, 128-character hex string.
 */
fun validHash(hash: String) = hash.matches(Regex("[0-9a-f]{128}"))
/**
 * Checks that a file name is safe to write on every supported OS.
 *
 * Accepts only plain `.jar` names: no path separators, control or reserved characters, no Windows
 * device names and at most 240 characters.
 *
 * @param name Candidate file name.
 * @return True if the name is safe.
 */
fun safeFileName(name: String): Boolean = name.isNotBlank() && name.length <= 240 && name !in setOf(".", "..") &&
    name.none { it == '/' || it == '\\' || it in "<>:\"|?*" || it.code < 32 || it.code == 127 } && name.endsWith(".jar", ignoreCase = true) &&
    !name.substringBefore('.').uppercase().matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
/**
 * Computes an order-independent digest of an inventory.
 *
 * Only file name, hash and size contribute; metadata, ids and classification do not affect it.
 *
 * @param entries Inventory entries.
 * @return Lower-case hex SHA-512 digest.
 */
fun inventoryDigest(entries: List<ModEntry>): String = sha512(entries.sortedBy { it.fileName }.joinToString("\n") {
    "${it.fileName}\u0000${it.sha512}\u0000${it.size}"
}.toByteArray(Charsets.UTF_8))
