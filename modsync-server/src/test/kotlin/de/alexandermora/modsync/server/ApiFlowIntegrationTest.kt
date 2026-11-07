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
        version: String = "0.3.1"
    ): Pair<Int, String> {
        val builder =
            HttpRequest
                .newBuilder(URI("$base$path"))
                .header("X-ModSync-Protocol", "1")
                .header("X-ModSync-Version", version)
                .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        val response = http.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
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
        val admin = login("admin", adminPassword).token
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
}
