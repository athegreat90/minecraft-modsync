package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.PersonalProfile
import de.alexandermora.modsync.fixtures.Fixtures
import de.alexandermora.modsync.fixtures.TestHttpsServer
import de.alexandermora.modsync.fixtures.TestReply
import de.alexandermora.modsync.fixtures.TestRequest
import de.alexandermora.modsync.protocol.Capabilities
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.scanner.fileHash
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the online client commands against an in-process HTTPS control plane. */
class ClientOnlineTest {
    @TempDir
    lateinit var home: Path
    private lateinit var server: TestHttpsServer
    private lateinit var state: Path
    private lateinit var instance: Path
    private val controlPlane = UUID.randomUUID().toString()
    private val serverId = UUID.randomUUID().toString()
    private var advertisedControlPlane = controlPlane
    private var manifest = Manifest(serverId, 1, "1.21.1", "neoforge", null, emptyList(), "2026-01-01T00:00:00Z", "d")
    private var profiles = emptyList<PersonalProfile>()

    /** Starts the control plane and registers one instance with a managed mod that the manifest does not select. */
    @BeforeEach
    fun prepare() {
        server = TestHttpsServer(home, ::handle)
        state = home.resolve("state")
        instance = Files.createDirectories(home.resolve("instance"))
        val installer = Installer(instance)
        installer.configureTarget(InstanceTarget("1.21.1", "neoforge"))
        val old = Fixtures.jar(installer.mods.resolve("old.jar"), "old")
        secureWrite(instance.resolve(".modsync/managed.json"), wire.encodeToString(ManagedState.serializer(), ManagedState(mapOf("old.jar" to fileHash(old)))))
        config()
    }

    @AfterEach
    fun cleanUp() = server.close()

    private fun credentials(
        token: String = "token",
        lifetimeSeconds: Long = 86400
    ) = Credentials("id", token, Instant.now().plusSeconds(lifetimeSeconds).toString(), "refresh")

    private fun handle(request: TestRequest): TestReply =
        when {
            request.path == "/api/v1/info" -> {
                TestReply(body = wire.encodeToString(Capabilities.serializer(), Capabilities(advertisedControlPlane)))
            }

            request.path == "/api/v1/auth/login" || request.path == "/api/v1/auth/refresh" -> {
                TestReply(body = wire.encodeToString(Credentials.serializer(), credentials(if (request.path.endsWith("login")) "logged-in" else "refreshed")))
            }

            request.path == "/api/v1/personal" && request.method == "GET" -> {
                TestReply(body = wire.encodeToString(ListSerializer(PersonalProfile.serializer()), profiles))
            }

            request.path.endsWith("/manifest") -> {
                TestReply(body = wire.encodeToString(Manifest.serializer(), manifest))
            }

            request.path == "/api/v1/servers" -> {
                TestReply(body = "[{\"id\":\"s\"}]")
            }

            request.path == "/api/v1/sync/reports" -> {
                TestReply(500, "boom")
            }

            else -> {
                TestReply(body = "{\"ok\":true}")
            }
        }

    private fun config(credentials: Credentials? = credentials()) =
        secureWrite(
            state.resolve("config.json"),
            wire.encodeToString(ClientConfig.serializer(), ClientConfig(server.endpoint, controlPlane, UUID.randomUUID().toString(), credentials, mapOf("main" to instance.toRealPath().toString())))
        )

    private fun cli(vararg args: String): Pair<Int, String> {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            return clientCommandLine().execute("--state-dir", state.toString(), *args) to captured.toString()
        } finally {
            System.setOut(original)
        }
    }

    /** Setup and login store the verified identity and the issued credentials. */
    @Test
    fun setupAndLogin() {
        Files.delete(state.resolve("config.json"))
        assertEquals(0, cli("setup", "--server-url", server.endpoint, "--trust-server-id", controlPlane).first)
        advertisedControlPlane = UUID.randomUUID().toString()
        assertEquals(1, cli("setup", "--server-url", server.endpoint, "--trust-server-id", controlPlane).first)
        advertisedControlPlane = controlPlane
        val password = Files.writeString(home.resolve("pw"), "secret\n")
        val (code, out) = cli("login", "--username", "alice", "--password-file", password.toString())
        assertEquals(0, code)
        assertTrue(out.contains("Authenticated") && Files.readString(state.resolve("config.json")).contains("logged-in"))
        assertEquals(0, cli("config", "show").first)
    }

    /** Authenticated read commands print the server responses and refresh expiring tokens. */
    @Test
    fun readCommandsAndTokenRefresh() {
        assertTrue(cli("connection", "test").second.contains("passed"))
        assertTrue(cli("servers", "list").second.contains("\"id\""))
        assertTrue(cli("personal", "list").second.contains("["))
        config(credentials(lifetimeSeconds = 10))
        assertEquals(0, cli("servers", "list").first)
        assertTrue(server.requests.contains("POST /api/v1/auth/refresh") && Files.readString(state.resolve("config.json")).contains("refreshed"))
    }

    /** A changed control-plane identity or missing login is refused before any request is sent. */
    @Test
    fun identityAndLoginAreEnforced() {
        advertisedControlPlane = UUID.randomUUID().toString()
        assertEquals(1, cli("servers", "list").first)
        advertisedControlPlane = controlPlane
        config(null)
        assertEquals(1, cli("servers", "list").first)
    }

    /** Importing a personal profile sends it to the server. */
    @Test
    fun personalImport() {
        val file = home.resolve("profile.json")
        Files.writeString(file, wire.encodeToString(PersonalProfile.serializer(), PersonalProfile(UUID.randomUUID().toString(), "mine", emptyList())))
        assertEquals(0, cli("personal", "import", file.toString()).first)
        assertTrue(server.requests.any { it.startsWith("PUT /api/v1/personal/") })
    }

    /** Plan, dry run, apply, verify and rollback of a removal-only change work end to end. */
    @Test
    fun planApplyVerifyRollback() {
        val plan = home.resolve("plan.json")
        val (code, out) = cli("sync", "plan", "--instance", "main", "--server", serverId, "--out", plan.toString())
        assertEquals(0, code)
        assertTrue(out.contains("REMOVE_MANAGED old.jar"))
        assertTrue(Files.exists(state.resolve("manifest-$serverId.json")))
        assertEquals(0, cli("sync", "apply", "--instance", "main", "--plan", plan.toString(), "--dry-run").first)
        assertTrue(Files.exists(instance.resolve("mods/old.jar")))
        assertEquals(0, cli("sync", "apply", "--instance", "main", "--plan", plan.toString()).first)
        assertFalse(Files.exists(instance.resolve("mods/old.jar")))
        assertTrue(cli("sync", "verify", "--instance", "main").second.contains("verified"))
        assertEquals(0, cli("sync", "rollback", "--instance", "main").first)
        assertTrue(Files.exists(instance.resolve("mods/old.jar")))
    }

    /** Apply refuses a plan whose manifest was superseded or whose personal artifacts are no longer authorized. */
    @Test
    fun applyRefusesStalePlan() {
        val plan = home.resolve("plan.json")
        assertEquals(0, cli("sync", "plan", "--instance", "main", "--server", serverId, "--out", plan.toString()).first)
        manifest = manifest.copy(revision = 2)
        assertEquals(1, cli("sync", "apply", "--instance", "main", "--plan", plan.toString()).first)
        assertTrue(Files.exists(instance.resolve("mods/old.jar")))
    }

    /** Verification notices a newer published manifest even when local files match. */
    @Test
    fun verifyNoticesNewerManifest() {
        val plan = home.resolve("plan.json")
        cli("sync", "plan", "--instance", "main", "--server", serverId, "--out", plan.toString())
        assertEquals(0, cli("sync", "apply", "--instance", "main", "--plan", plan.toString()).first)
        manifest = manifest.copy(revision = 2)
        assertEquals(1, cli("sync", "verify", "--instance", "main").first)
    }
}
