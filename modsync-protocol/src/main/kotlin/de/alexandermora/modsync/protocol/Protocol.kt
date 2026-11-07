package de.alexandermora.modsync.protocol

import de.alexandermora.modsync.domain.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON codec for every wire message; tolerates unknown keys so older peers accept newer payloads. */
val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }
/** Wire protocol version spoken by this build. */
const val PROTOCOL = 1
/** Semantic version of the ModSync components, compared against the peers' minimum versions. */
const val COMPONENT_VERSION = "0.2.0"
/**
 * Compares two component versions.
 *
 * Both must be `major.minor.patch` (optional `v` prefix, `-suffix` ignored); anything else yields false.
 *
 * @param actual Version of the peer.
 * @param minimum Lowest acceptable version.
 * @return True if [actual] is at least [minimum].
 */
fun versionAtLeast(actual: String, minimum: String): Boolean {
    /**
     * Parses a version into its three numeric components.
     *
     * @param v Version text.
     * @return Major, minor and patch numbers, or null when the text is malformed.
     */
    fun numbers(v: String): List<Int>? {
        val parts=v.substringBefore('-').removePrefix("v").split('.').takeIf { it.size == 3 } ?: return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }
    val a=numbers(actual) ?: return false; val m=numbers(minimum) ?: return false
    for (i in 0..2) { if (a[i] != m[i]) return a[i] > m[i] }
    return true
}
/** Public server description: control-plane identity, protocol and minimum component versions. */
@Serializable data class Capabilities(val controlPlaneId: String, val protocol: Int = PROTOCOL, val minimumClient: String = "0.1.0", val minimumAgent: String = "0.1.0")
/** Username/password credentials plus the device the session is bound to. */
@Serializable data class Login(val username: String, val password: String, val deviceId: String, val protocol: Int = PROTOCOL)
/** Bearer token issued after login, enrollment or rotation, with its expiry and optional refresh token. */
@Serializable data class Credentials(val id: String, val token: String, val expiresAt: String, val refreshToken: String? = null)
/** Agent enrollment request that redeems a one-time enrollment token. */
@Serializable data class Enrollment(val token: String, val agentId: String, val protocol: Int = PROTOCOL)
/** Admin request for a short-lived agent enrollment token. */
@Serializable data class EnrollmentRequest(val ttlSeconds: Long = 600)
/** One-time token an agent redeems to enroll, valid until [expiresAt]. */
@Serializable data class EnrollmentToken(val token: String, val expiresAt: String)
/** Admin request to register a Minecraft server with its game and loader versions. */
@Serializable data class ServerRegistration(val name: String, val minecraftVersion: String, val loader: String = "neoforge", val loaderVersion: String? = null)
/** Binding of a server to the agent allowed to upload its inventory; [generation] fences stale owners. */
@Serializable data class Assignment(val serverId: String, val agentId: String, val generation: Long, val mapping: String)
/** Admin request to move a server's ownership to another agent. */
@Serializable data class Transfer(val agentId: String, val mapping: String)
/** Server summary visible to clients: versions, last inventory time, published revision and staleness. */
@Serializable data class ServerInfo(val id: String, val name: String, val minecraftVersion: String, val loader: String, val loaderVersion: String?, val inventoryAt: String? = null, val publishedRevision: Long? = null, val inventoryStale: Boolean = true)
/** Opens an inventory snapshot upload; the snapshot stays invisible until it is committed. */
@Serializable data class SnapshotBegin(val serverId: String, val scanId: String, val generation: Long, val sequence: Long, val scannedAt: String, val digest: String, val count: Int, val protocol: Int = PROTOCOL)
/** One ordered batch of inventory entries within an open snapshot. */
@Serializable data class SnapshotBatch(val index: Int, val entries: List<ModEntry>)
/** Server confirmation that a snapshot was committed with the expected digest. */
@Serializable data class SnapshotAck(val scanId: String, val sequence: Long, val digest: String, val committedAt: String)
/** Committed snapshot header together with all its entries. */
@Serializable data class SnapshotView(val scan: SnapshotBegin, val entries: List<ModEntry>)
/** Agent statement that it holds an unchanged scan of a server, avoiding a re-upload. */
@Serializable data class ScanConfirmation(val serverId: String, val generation: Long, val digest: String)
/** Periodic agent liveness message carrying scan confirmations. */
@Serializable data class Heartbeat(val protocol: Int = PROTOCOL, val scans: List<ScanConfirmation> = emptyList())
/** Admin request to override the classification of the artifact with the given hash. */
@Serializable data class OverrideRequest(val sha512: String, val classification: Classification, val reason: String)
/** Admin request to preview or publish a manifest from a snapshot and its classified artifacts. */
@Serializable data class PublishRequest(val scanId: String, val artifacts: List<Artifact>)
/** Request to exchange a refresh token for new credentials. */
@Serializable data class RefreshRequest(val token: String)
/** Error body returned for every failed request; [correlationId] ties it to server logs. */
@Serializable data class Failure(val code: String, val message: String, val correlationId: String)

/** Admin request to create a user account. */
@Serializable data class UserRegistration(val username: String, val password: String)
/** One installed mod in a client inventory report: hash, file name only, recognized ids and managed ownership. */
@Serializable data class ClientModEntry(val sha512: String, val fileName: String, val projectId: String? = null, val versionId: String? = null, val managed: Boolean = false)
/** Revisioned inventory of one client instance; a higher sequence replaces the stored inventory. */
@Serializable data class ClientInventoryReport(val instanceId: String, val instanceName: String, val serverId: String? = null, val minecraftVersion: String, val loader: String, val loaderVersion: String? = null, val sequence: Long, val appliedRevision: Long? = null, val syncPolicy: SyncPolicy = SyncPolicy.MANUAL, val activity: ClientActivity = ClientActivity.IDLE, val profileId: String? = null, val mods: List<ClientModEntry>)
/** Server acknowledgement of a client inventory report; accepted is false for stale sequences. */
@Serializable data class ClientInventoryAck(val instanceId: String, val sequence: Long, val accepted: Boolean, val verifiedAt: String)
/** Coverage of one client instance against the latest manifest of a server. */
@Serializable data class CoverageRow(val instanceId: String, val instanceName: String, val status: CoverageStatus, val appliedRevision: Long? = null, val verifiedAt: String? = null, val missingCount: Int = 0)
/** Coverage matrix of all client instances that selected a server. */
@Serializable data class ServerCoverage(val serverId: String, val manifestRevision: Long, val rows: List<CoverageRow>)
/** Request to change the automatic publication policy of a server. */
@Serializable data class PublicationPolicyRequest(val policy: PublicationPolicy)
/** One automatic publication evaluation with outcome, revision and actor. */
@Serializable data class PublicationRecord(val id: Long, val scanId: String, val policy: PublicationPolicy, val outcome: String, val revision: Long? = null, val detail: String, val actor: String, val createdAt: String)
/** Outcome of installing a manifest revision on a client. */
@Serializable data class SyncReport(val serverId: String, val revision: Long, val status: String)
