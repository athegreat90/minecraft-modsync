package net.modsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }
const val PROTOCOL = 1
const val COMPONENT_VERSION = "0.1.0"
fun versionAtLeast(actual: String, minimum: String): Boolean {
    fun numbers(v: String): List<Int>? {
        val parts=v.substringBefore('-').removePrefix("v").split('.').takeIf { it.size == 3 } ?: return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }
    val a=numbers(actual) ?: return false; val m=numbers(minimum) ?: return false
    for (i in 0..2) { if (a[i] != m[i]) return a[i] > m[i] }
    return true
}
@Serializable data class Capabilities(val controlPlaneId: String, val protocol: Int = PROTOCOL, val minimumClient: String = "0.1.0", val minimumAgent: String = "0.1.0")
@Serializable data class Login(val username: String, val password: String, val deviceId: String, val protocol: Int = PROTOCOL)
@Serializable data class Credentials(val id: String, val token: String, val expiresAt: String, val refreshToken: String? = null)
@Serializable data class Enrollment(val token: String, val agentId: String, val protocol: Int = PROTOCOL)
@Serializable data class EnrollmentRequest(val ttlSeconds: Long = 600)
@Serializable data class EnrollmentToken(val token: String, val expiresAt: String)
@Serializable data class ServerRegistration(val name: String, val minecraftVersion: String, val loader: String = "neoforge", val loaderVersion: String? = null)
@Serializable data class Assignment(val serverId: String, val agentId: String, val generation: Long, val mapping: String)
@Serializable data class Transfer(val agentId: String, val mapping: String)
@Serializable data class ServerInfo(val id: String, val name: String, val minecraftVersion: String, val loader: String, val loaderVersion: String?, val inventoryAt: String? = null, val publishedRevision: Long? = null, val inventoryStale: Boolean = true)
@Serializable data class SnapshotBegin(val serverId: String, val scanId: String, val generation: Long, val sequence: Long, val scannedAt: String, val digest: String, val count: Int, val protocol: Int = PROTOCOL)
@Serializable data class SnapshotBatch(val index: Int, val entries: List<ModEntry>)
@Serializable data class SnapshotAck(val scanId: String, val sequence: Long, val digest: String, val committedAt: String)
@Serializable data class SnapshotView(val scan: SnapshotBegin, val entries: List<ModEntry>)
@Serializable data class ScanConfirmation(val serverId: String, val generation: Long, val digest: String)
@Serializable data class Heartbeat(val protocol: Int = PROTOCOL, val scans: List<ScanConfirmation> = emptyList())
@Serializable data class OverrideRequest(val sha512: String, val classification: Classification, val reason: String)
@Serializable data class PublishRequest(val scanId: String, val artifacts: List<Artifact>)
@Serializable data class RefreshRequest(val token: String)
@Serializable data class Failure(val code: String, val message: String, val correlationId: String)

@Serializable data class UserRegistration(val username: String, val password: String)
@Serializable data class SyncReport(val serverId: String, val revision: Long, val status: String)
