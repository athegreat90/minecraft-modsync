package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.PersonalProfile
import de.alexandermora.modsync.domain.inventoryDigest
import de.alexandermora.modsync.protocol.Assignment
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.Enrollment
import de.alexandermora.modsync.protocol.EnrollmentRequest
import de.alexandermora.modsync.protocol.EnrollmentToken
import de.alexandermora.modsync.protocol.Heartbeat
import de.alexandermora.modsync.protocol.Login
import de.alexandermora.modsync.protocol.OverrideRequest
import de.alexandermora.modsync.protocol.PublishRequest
import de.alexandermora.modsync.protocol.RefreshRequest
import de.alexandermora.modsync.protocol.ServerRegistration
import de.alexandermora.modsync.protocol.SnapshotAck
import de.alexandermora.modsync.protocol.SnapshotBatch
import de.alexandermora.modsync.protocol.SnapshotBegin
import de.alexandermora.modsync.protocol.SyncReport
import de.alexandermora.modsync.protocol.Transfer
import de.alexandermora.modsync.protocol.UserRegistration
import de.alexandermora.modsync.protocol.wire
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the complete REST API of the running application: enrollment, inventory, publication, sync reports and profiles. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiFlowIntegrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.10")
    }

    private val state: Path = Files.createTempDirectory("modsync-api-test")
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var base: String
    private val http = HttpClient.newHttpClient()
    private val adminPassword = "fixture-only-bootstrap-password"
    private val hash = "c".repeat(128)
    private val adminToken by lazy { login("admin", adminPassword).token }

    /** Starts the full application against MariaDB. */
    @BeforeAll
    fun start() {
        val passwordFile = state.resolve("bootstrap-password")
        Files.writeString(passwordFile, adminPassword)
        context =
            SpringApplicationBuilder(ServerApplication::class.java).run(
                "--server.port=0",
                "--spring.datasource.url=${database.jdbcUrl}",
                "--spring.datasource.username=${database.username}",
                "--spring.datasource.password=${database.password}",
                "--modsync.bootstrap-password-file=$passwordFile"
            )
        base = "http://127.0.0.1:${requireNotNull((context as WebServerApplicationContext).webServer).port}"
    }

    @AfterAll
    fun stop() {
        context.close()
        state.toFile().deleteRecursively()
    }

    private fun call(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
        version: String = "0.3.1",
        waitOnRateLimit: Boolean = true
    ): Pair<Int, String> {
        val builder =
            HttpRequest
                .newBuilder(URI("$base$path"))
                .header("X-ModSync-Protocol", "1")
                .header("X-ModSync-Version", version)
                .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        val request = builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
        var response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (waitOnRateLimit && response.statusCode() == 429) {
            Thread.sleep(60_000 - System.currentTimeMillis() % 60_000 + 500)
            response = http.send(request, HttpResponse.BodyHandlers.ofString())
        }
        return response.statusCode() to response.body()
    }

    private fun <T> ok(
        result: Pair<Int, String>,
        serializer: KSerializer<T>
    ): T {
        assertEquals(200, result.first, result.second)
        return wire.decodeFromString(serializer, result.second)
    }

    private fun <T> send(
        method: String,
        path: String,
        token: String?,
        serializer: KSerializer<T>,
        body: String? = null
    ): T = ok(call(method, path, token, body), serializer)

    private fun login(
        user: String,
        password: String
    ) = send("POST", "/api/v1/auth/login", null, Credentials.serializer(), wire.encodeToString(Login.serializer(), Login(user, password, UUID.randomUUID().toString())))

    /** Old components and unauthenticated or wrongly authenticated calls are refused. */
    @Test
    fun accessControl() {
        assertEquals(426, call("GET", "/api/v1/servers", version = "0.1.0").first)
        assertEquals(401, call("GET", "/api/v1/servers").first)
        assertEquals(401, call("GET", "/api/v1/servers", "not-a-token").first)
        assertEquals(200, call("GET", "/api/v1/info").first)
        assertTrue(call("POST", "/api/v1/auth/login", null, wire.encodeToString(Login.serializer(), Login("admin", "wrong-password", UUID.randomUUID().toString()))).first in 400..401)
        val admin = login("admin", adminPassword)
        val user = UserRegistration("player1", "player-password-1234")
        assertEquals(200, call("POST", "/api/v1/admin/users", admin.token, wire.encodeToString(UserRegistration.serializer(), user)).first)
        val player = login("player1", "player-password-1234")
        assertEquals(403, call("POST", "/api/v1/admin/users", player.token, wire.encodeToString(UserRegistration.serializer(), UserRegistration("x", "y"))).first)
        assertEquals(403, call("GET", "/api/v1/agent/assignments", player.token).first)
        val refreshed = send("POST", "/api/v1/auth/refresh", null, Credentials.serializer(), wire.encodeToString(RefreshRequest.serializer(), RefreshRequest(requireNotNull(player.refreshToken))))
        assertTrue(refreshed.token != player.token)
        assertTrue(call("POST", "/api/v1/auth/refresh", null, wire.encodeToString(RefreshRequest.serializer(), RefreshRequest("bogus"))).first in 400..401)
        val devices = send("GET", "/api/v1/devices", refreshed.token, ListSerializer(String.serializer()))
        assertEquals(1, devices.size.coerceAtLeast(1))
        assertEquals(200, call("POST", "/api/v1/devices/${devices.first()}/revoke", refreshed.token).first)
        assertEquals(200, call("GET", "/api/v1/openapi.json", admin.token).first)
    }

    /** An agent enrolls, receives an assignment, delivers a snapshot and an administrator publishes a manifest that a player syncs. */
    @Test
    fun enrollmentInventoryPublicationAndSync() {
        val admin = adminToken
        val serverId = Json.parseToJsonElement(call("POST", "/api/v1/admin/servers", admin, wire.encodeToString(ServerRegistration.serializer(), ServerRegistration("Flow", "1.21.1", "neoforge", "21.1"))).second).jsonPrimitive.content
        val enrollment = send("POST", "/api/v1/admin/enrollments", admin, EnrollmentToken.serializer(), wire.encodeToString(EnrollmentRequest.serializer(), EnrollmentRequest(300)))
        val agentId = UUID.randomUUID().toString()
        val agent = send("POST", "/api/v1/enroll", null, Credentials.serializer(), wire.encodeToString(Enrollment.serializer(), Enrollment(enrollment.token, agentId)))
        assertTrue(call("POST", "/api/v1/enroll", null, wire.encodeToString(Enrollment.serializer(), Enrollment(enrollment.token, UUID.randomUUID().toString()))).first >= 400)
        val assignment = send("PUT", "/api/v1/admin/servers/$serverId/assignment", admin, Assignment.serializer(), wire.encodeToString(Transfer.serializer(), Transfer(agentId, "main")))
        assertEquals(listOf(assignment), send("GET", "/api/v1/agent/assignments", agent.token, ListSerializer(Assignment.serializer())))
        assertEquals(200, call("POST", "/api/v1/agent/heartbeat", agent.token, wire.encodeToString(Heartbeat.serializer(), Heartbeat())).first)
        assertTrue(call("GET", "/api/v1/admin/agents", admin).second.contains(agentId))
        assertTrue(send("POST", "/api/v1/agent/credentials/rotate", agent.token, Credentials.serializer()).token != agent.token)

        val entry = ModEntry("flow.jar", hash, 3)
        val scanId = UUID.randomUUID().toString()
        val begin = SnapshotBegin(serverId, scanId, assignment.generation, 1, Instant.now().toString(), inventoryDigest(listOf(entry)), 1)
        assertEquals(200, call("POST", "/api/v1/agent/snapshots", agent.token, wire.encodeToString(SnapshotBegin.serializer(), begin)).first)
        assertEquals(200, call("PUT", "/api/v1/agent/snapshots/$scanId/batches", agent.token, wire.encodeToString(SnapshotBatch.serializer(), SnapshotBatch(0, listOf(entry)))).first)
        val ack = send("POST", "/api/v1/agent/snapshots/$scanId/commit", agent.token, SnapshotAck.serializer())
        assertEquals(begin.digest, ack.digest)
        assertTrue(call("GET", "/api/v1/admin/servers/$serverId/inventory", admin).second.contains("flow.jar"))
        assertTrue(call("GET", "/api/v1/servers", admin).second.contains("Flow"))

        val artifact = Artifact("local-flow", "1", "flow.jar", hash, 3, null, setOf("1.21.1"), setOf("neoforge"), Classification.CLIENT_REQUIRED)
        val request = wire.encodeToString(PublishRequest.serializer(), PublishRequest(scanId, listOf(artifact)))
        assertTrue(call("POST", "/api/v1/admin/servers/$serverId/preview", admin, request).first >= 400)
        val override = OverrideRequest(hash, Classification.CLIENT_REQUIRED, "reviewed")
        assertEquals(200, call("PUT", "/api/v1/admin/servers/$serverId/classification", admin, wire.encodeToString(OverrideRequest.serializer(), override)).first)
        val preview = send("POST", "/api/v1/admin/servers/$serverId/preview", admin, Manifest.serializer(), request)
        assertEquals(1, preview.revision)
        val published = send("POST", "/api/v1/admin/servers/$serverId/publish", admin, Manifest.serializer(), request)
        assertEquals(1, published.revision)

        assertEquals(200, call("POST", "/api/v1/admin/users", admin, wire.encodeToString(UserRegistration.serializer(), UserRegistration("player2", "player-password-5678"))).first)
        val player = login("player2", "player-password-5678").token
        assertEquals(published, send("GET", "/api/v1/servers/$serverId/manifest", player, Manifest.serializer()))
        assertEquals(published, send("GET", "/api/v1/servers/$serverId/manifest?revision=1", player, Manifest.serializer()))
        assertEquals(404, call("GET", "/api/v1/servers/$serverId/manifest?revision=9", player).first)
        assertEquals(404, call("GET", "/api/v1/servers/${UUID.randomUUID()}/manifest", player).first)
        val report = wire.encodeToString(SyncReport.serializer(), SyncReport(serverId, 1, "SUCCESS"))
        assertEquals(200, call("POST", "/api/v1/sync/reports", player, report).first)
        assertTrue(call("POST", "/api/v1/sync/reports", player, wire.encodeToString(SyncReport.serializer(), SyncReport(serverId, 1, "BOGUS"))).first >= 400)
        assertEquals(404, call("POST", "/api/v1/sync/reports", player, wire.encodeToString(SyncReport.serializer(), SyncReport(serverId, 7, "SUCCESS"))).first)

        val profile = PersonalProfile(UUID.randomUUID().toString(), "mine", emptyList())
        assertEquals(profile, send("PUT", "/api/v1/personal/${profile.id}", player, PersonalProfile.serializer(), wire.encodeToString(PersonalProfile.serializer(), profile)))
        assertEquals(listOf(profile), send("GET", "/api/v1/personal", player, ListSerializer(PersonalProfile.serializer())))

        assertEquals(200, call("POST", "/api/v1/admin/enrollments/revoke", admin).first)
        assertEquals(200, call("POST", "/api/v1/admin/agents/$agentId/revoke", admin).first)
        assertEquals(401, call("GET", "/api/v1/agent/assignments", agent.token).first)
    }

    private class Deployment(
        val admin: String,
        val agent: String,
        val agentId: String,
        val serverId: String,
        val generation: Long
    )

    private fun clientError(result: Pair<Int, String>) = result.first in 400..499

    private fun deployment(): Deployment {
        val admin = adminToken
        val serverId = Json.parseToJsonElement(call("POST", "/api/v1/admin/servers", admin, wire.encodeToString(ServerRegistration.serializer(), ServerRegistration("Srv-${UUID.randomUUID()}", "1.21.1", "neoforge"))).second).jsonPrimitive.content
        val enrollment = send("POST", "/api/v1/admin/enrollments", admin, EnrollmentToken.serializer(), wire.encodeToString(EnrollmentRequest.serializer(), EnrollmentRequest(300)))
        val agentId = UUID.randomUUID().toString()
        val agent = send("POST", "/api/v1/enroll", null, Credentials.serializer(), wire.encodeToString(Enrollment.serializer(), Enrollment(enrollment.token, agentId)))
        val assignment = send("PUT", "/api/v1/admin/servers/$serverId/assignment", admin, Assignment.serializer(), wire.encodeToString(Transfer.serializer(), Transfer(agentId, "main")))
        return Deployment(admin, agent.token, agentId, serverId, assignment.generation)
    }

    private fun begin(
        d: Deployment,
        entries: List<ModEntry>,
        sequence: Long = 1,
        scanId: String = UUID.randomUUID().toString()
    ) = SnapshotBegin(d.serverId, scanId, d.generation, sequence, Instant.now().toString(), inventoryDigest(entries), entries.size)

    private fun post(
        d: Deployment,
        begin: SnapshotBegin
    ) = call("POST", "/api/v1/agent/snapshots", d.agent, wire.encodeToString(SnapshotBegin.serializer(), begin))

    private fun batch(
        d: Deployment,
        scanId: String,
        index: Int,
        entries: List<ModEntry>
    ) = call("PUT", "/api/v1/agent/snapshots/$scanId/batches", d.agent, wire.encodeToString(SnapshotBatch.serializer(), SnapshotBatch(index, entries)))

    private fun commit(
        d: Deployment,
        scanId: String
    ) = call("POST", "/api/v1/agent/snapshots/$scanId/commit", d.agent)

    private fun deliver(
        d: Deployment,
        entries: List<ModEntry>,
        sequence: Long = 1
    ): String {
        val begin = begin(d, entries, sequence)
        assertEquals(200, post(d, begin).first)
        assertEquals(200, batch(d, begin.scanId, 0, entries).first)
        assertEquals(200, commit(d, begin.scanId).first)
        return begin.scanId
    }

    /** Snapshot begin, batch and commit refuse malformed, foreign, conflicting and outdated deliveries and stay idempotent on retries. */
    @Test
    fun snapshotValidation() {
        val d = deployment()
        val entry = ModEntry("a.jar", hash, 1)
        val good = begin(d, listOf(entry))
        listOf(
            good.copy(scanId = UUID.randomUUID().toString(), protocol = 2),
            good.copy(scanId = UUID.randomUUID().toString(), count = 10001),
            good.copy(scanId = UUID.randomUUID().toString(), sequence = 0),
            good.copy(scanId = UUID.randomUUID().toString(), digest = "short"),
            good.copy(scanId = UUID.randomUUID().toString(), scannedAt = Instant.now().plusSeconds(3600).toString()),
            good.copy(scanId = UUID.randomUUID().toString(), generation = d.generation + 1),
            good.copy(scanId = UUID.randomUUID().toString(), serverId = UUID.randomUUID().toString())
        ).forEach { assertTrue(clientError(post(d, it)), it.toString()) }
        assertEquals(200, post(d, good).first)
        assertEquals(200, post(d, good).first)
        assertTrue(clientError(post(d, good.copy(count = 2))))
        listOf(
            -1 to listOf(entry),
            0 to emptyList(),
            0 to listOf(ModEntry("../x.jar", hash, 1)),
            0 to listOf(ModEntry("b.jar", "bad", 1)),
            0 to listOf(ModEntry("b.jar", hash, -1))
        ).forEach { (index, entries) -> assertTrue(clientError(batch(d, good.scanId, index, entries)), "$index $entries") }
        assertEquals(404, batch(d, UUID.randomUUID().toString(), 0, listOf(entry)).first)
        assertEquals(200, batch(d, good.scanId, 0, listOf(entry)).first)
        assertEquals(200, batch(d, good.scanId, 0, listOf(entry)).first)
        assertTrue(clientError(batch(d, good.scanId, 0, listOf(ModEntry("other.jar", hash, 1)))))
        val other = deployment()
        assertTrue(clientError(call("PUT", "/api/v1/agent/snapshots/${good.scanId}/batches", other.agent, wire.encodeToString(SnapshotBatch.serializer(), SnapshotBatch(0, listOf(entry))))))
        val incomplete = begin(d, listOf(entry, entry.copy(fileName = "b.jar")), sequence = 2)
        assertEquals(200, post(d, incomplete).first)
        assertEquals(200, batch(d, incomplete.scanId, 0, listOf(entry)).first)
        assertTrue(clientError(commit(d, incomplete.scanId)))
        assertTrue(clientError(call("GET", "/api/v1/admin/servers/${d.serverId}/inventory", d.admin)))
        assertEquals(200, commit(d, good.scanId).first)
        assertEquals(200, commit(d, good.scanId).first)
        assertTrue(clientError(batch(d, good.scanId, 1, listOf(entry.copy(fileName = "c.jar")))))
        assertTrue(clientError(post(d, begin(d, listOf(entry), sequence = 1))))
        assertTrue(clientError(commit(d, UUID.randomUUID().toString())))
    }

    /** A snapshot with names that differ only by case cannot be committed. */
    @Test
    fun caseCollidingSnapshotIsRejected() {
        val d = deployment()
        val entries = listOf(ModEntry("A.jar", hash, 1), ModEntry("a.jar", "d".repeat(128), 1))
        val begin = begin(d, entries)
        assertEquals(200, post(d, begin).first)
        assertEquals(200, batch(d, begin.scanId, 0, entries).first)
        assertTrue(clientError(commit(d, begin.scanId)))
    }

    /** Classification and publication refuse invalid approvals and artifacts that do not match the committed inventory. */
    @Test
    fun publicationValidation() {
        val d = deployment()
        val a = ModEntry("a.jar", hash, 3)
        val b = ModEntry("b.jar", "d".repeat(128), 4)
        val scan = deliver(d, listOf(a, b))

        fun classify(request: OverrideRequest, server: String = d.serverId) = call("PUT", "/api/v1/admin/servers/$server/classification", d.admin, wire.encodeToString(OverrideRequest.serializer(), request))
        assertTrue(clientError(classify(OverrideRequest("bad", Classification.CLIENT_REQUIRED, "r"))))
        assertTrue(clientError(classify(OverrideRequest(hash, Classification.CLIENT_REQUIRED, ""))))
        assertTrue(clientError(classify(OverrideRequest(hash, Classification.UNKNOWN, "r"))))
        assertEquals(404, classify(OverrideRequest(hash, Classification.CLIENT_REQUIRED, "r"), UUID.randomUUID().toString()).first)

        fun artifact(entry: ModEntry, project: String, classification: Classification = Classification.CLIENT_REQUIRED) =
            Artifact(project, "1", entry.fileName, entry.sha512, entry.size, null, setOf("1.21.1"), setOf("neoforge"), classification)

        fun preview(scanId: String, vararg artifacts: Artifact) = call("POST", "/api/v1/admin/servers/${d.serverId}/preview", d.admin, wire.encodeToString(PublishRequest.serializer(), PublishRequest(scanId, artifacts.toList())))
        assertTrue(clientError(preview(scan, artifact(a, "pa"), artifact(b, "pb"))))
        assertEquals(200, classify(OverrideRequest(hash, Classification.CLIENT_REQUIRED, "ok")).first)
        assertTrue(clientError(preview(scan, artifact(a, "pa"), artifact(b, "pb"))))
        assertEquals(200, classify(OverrideRequest(b.sha512, Classification.SERVER_ONLY, "ok")).first)
        assertTrue(clientError(preview(UUID.randomUUID().toString(), artifact(a, "pa"), artifact(b, "pb"))))
        assertTrue(clientError(preview(scan, artifact(a, "pa"))))
        assertTrue(clientError(preview(scan, artifact(a, "same"), artifact(b, "same"))))
        assertTrue(clientError(preview(scan, artifact(a, "pa", Classification.UNKNOWN), artifact(b, "pb"))))
        assertTrue(clientError(preview(scan, artifact(a, "pa").copy(sha512 = "bad"), artifact(b, "pb"))))
        assertTrue(clientError(preview(scan, artifact(a, "pa").copy(size = 99), artifact(b, "pb"))))
        assertTrue(clientError(preview(scan, artifact(a, "pa").copy(fileName = "../a.jar"), artifact(b, "pb"))))
        val unscanned = Artifact("pu", "1", "u.jar", "e".repeat(128), 1, null, setOf("1.21.1"), setOf("neoforge"), Classification.CLIENT_REQUIRED)
        assertTrue(clientError(preview(scan, artifact(a, "pa"), artifact(b, "pb"), unscanned)))
        assertEquals(200, preview(scan, artifact(a, "pa"), artifact(b, "pb")).first)
        assertEquals(200, call("POST", "/api/v1/admin/servers/${d.serverId}/publish", d.admin, wire.encodeToString(PublishRequest.serializer(), PublishRequest(scan, listOf(artifact(a, "pa"), artifact(b, "pb"))))).first)
        assertEquals(404, call("POST", "/api/v1/admin/servers/${UUID.randomUUID()}/preview", d.admin, wire.encodeToString(PublishRequest.serializer(), PublishRequest(scan, emptyList()))).first)
    }

    /** Account, enrollment and agent management refuse duplicates, reuse, revoked credentials and unknown targets. */
    @Test
    fun accountAndAgentValidation() {
        val d = deployment()
        val users = "/api/v1/admin/users"

        fun register(name: String, password: String) = call("POST", users, d.admin, wire.encodeToString(UserRegistration.serializer(), UserRegistration(name, password)))
        val name = "user-${UUID.randomUUID().toString().take(8)}"
        assertEquals(200, register(name, "a-long-enough-password").first)
        assertTrue(register(name, "a-long-enough-password").first >= 400)
        assertTrue(clientError(register("short-pw-${UUID.randomUUID().toString().take(6)}", "x")))
        assertTrue(clientError(register("", "a-long-enough-password")))
        val credentials = login(name, "a-long-enough-password")
        val refresh = wire.encodeToString(RefreshRequest.serializer(), RefreshRequest(requireNotNull(credentials.refreshToken)))
        assertEquals(200, call("POST", "/api/v1/auth/refresh", null, refresh).first)
        assertTrue(clientError(call("POST", "/api/v1/auth/refresh", null, refresh)))
        assertTrue(clientError(call("POST", "/api/v1/auth/login", null, wire.encodeToString(Login.serializer(), Login("nobody-here", "whatever-password", UUID.randomUUID().toString())))))
        assertTrue(clientError(call("POST", "/api/v1/devices/${UUID.randomUUID()}/revoke", credentials.token)))

        val enrollment = send("POST", "/api/v1/admin/enrollments", d.admin, EnrollmentToken.serializer(), wire.encodeToString(EnrollmentRequest.serializer(), EnrollmentRequest(300)))
        val enroll = wire.encodeToString(Enrollment.serializer(), Enrollment(enrollment.token, UUID.randomUUID().toString()))
        assertEquals(200, call("POST", "/api/v1/enroll", null, enroll).first)
        assertTrue(clientError(call("POST", "/api/v1/enroll", null, wire.encodeToString(Enrollment.serializer(), Enrollment(enrollment.token, UUID.randomUUID().toString())))))
        assertTrue(clientError(call("POST", "/api/v1/admin/enrollments", d.admin, wire.encodeToString(EnrollmentRequest.serializer(), EnrollmentRequest(0)))))
        assertTrue(clientError(call("POST", "/api/v1/enroll", null, wire.encodeToString(Enrollment.serializer(), Enrollment("unknown-token", UUID.randomUUID().toString())))))
        assertTrue(clientError(call("PUT", "/api/v1/admin/servers/${d.serverId}/assignment", d.admin, wire.encodeToString(Transfer.serializer(), Transfer(UUID.randomUUID().toString(), "main")))))
        assertTrue(clientError(call("PUT", "/api/v1/admin/servers/${UUID.randomUUID()}/assignment", d.admin, wire.encodeToString(Transfer.serializer(), Transfer(d.agentId, "main")))))
        val moved = send("PUT", "/api/v1/admin/servers/${d.serverId}/assignment", d.admin, Assignment.serializer(), wire.encodeToString(Transfer.serializer(), Transfer(d.agentId, "second")))
        assertEquals(d.generation + 1, moved.generation)
        assertTrue(clientError(post(d, begin(d, listOf(ModEntry("a.jar", hash, 1))))))
    }

    /** Authentication endpoints are limited per client address and minute. */
    @Test
    fun authenticationRateLimit() {
        val body = wire.encodeToString(RefreshRequest.serializer(), RefreshRequest("bogus"))
        val statuses = (1..25).map { call("POST", "/api/v1/auth/refresh", null, body, waitOnRateLimit = false).first }
        assertTrue(429 in statuses)
        Thread.sleep(60_000 - System.currentTimeMillis() % 60_000 + 500)
    }

    /** The request guard refuses oversized bodies and ignores malformed or oversized bearer headers. */
    @Test
    fun requestGuard() {
        val admin = adminToken
        val oversized = runCatching { call("POST", "/api/v1/admin/users", admin, "x".repeat(3 * 1024 * 1024)).first }
        assertTrue(oversized.isFailure || oversized.getOrThrow() == 413)
        assertEquals(401, call("GET", "/api/v1/servers", "x".repeat(600)).first)
        val malformed =
            HttpRequest
                .newBuilder(URI("$base/api/v1/servers"))
                .header("X-ModSync-Protocol", "1")
                .header("X-ModSync-Version", "0.3.1")
                .header("Authorization", "Basic abc")
                .GET()
                .build()
        assertEquals(401, http.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode())
        assertEquals(426, call("GET", "/api/v1/servers", admin, version = "garbage").first)
    }
}
