package de.alexandermora.modsync.agent

import de.alexandermora.modsync.fixtures.TestHttpsServer
import de.alexandermora.modsync.fixtures.TestReply
import de.alexandermora.modsync.fixtures.TestRequest
import de.alexandermora.modsync.protocol.Assignment
import de.alexandermora.modsync.protocol.Capabilities
import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.PROTOCOL
import de.alexandermora.modsync.protocol.SnapshotAck
import de.alexandermora.modsync.protocol.SnapshotBegin
import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the agent against an in-process HTTPS control plane that trusts a throw-away self-signed certificate. */
class AgentScanTest {
    @TempDir
    lateinit var home: Path
    private lateinit var server: TestHttpsServer
    private lateinit var state: Path
    private lateinit var mods: Path
    private val controlPlane = UUID.randomUUID().toString()
    private val agentId = UUID.randomUUID().toString()
    private val serverId = UUID.randomUUID().toString()
    private var generation = 1L
    private var mapping = "main"
    private val begins = mutableListOf<SnapshotBegin>()
    private val requests get() = server.requests

    /** Starts the HTTPS control plane and prepares one mods directory with a single jar. */
    @BeforeEach
    fun prepare() {
        server = TestHttpsServer(home, ::handle)
        state = home.resolve("state")
        mods = Files.createDirectories(home.resolve("mods"))
        jar(mods.resolve("alpha.jar"), "alpha")
    }

    @AfterEach
    fun cleanUp() = server.close()

    private fun jar(
        path: Path,
        id: String
    ) = ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/neoforge.mods.toml").also { it.time = 0 })
        zip.write("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"$id\"\nversion=\"1\"".toByteArray())
        zip.closeEntry()
    }

    private fun handle(request: TestRequest): TestReply {
        val (_, path, body) = request
        return when {
            path == "/api/v1/info" -> {
                TestReply(body = wire.encodeToString(Capabilities.serializer(), Capabilities(controlPlane, PROTOCOL)))
            }

            path == "/api/v1/enroll" -> {
                TestReply(body = wire.encodeToString(Credentials.serializer(), credentials()))
            }

            path == "/api/v1/agent/credentials/rotate" -> {
                TestReply(body = wire.encodeToString(Credentials.serializer(), credentials("rotated")))
            }

            path == "/api/v1/agent/assignments" -> {
                TestReply(body = wire.encodeToString(ListSerializer(Assignment.serializer()), listOf(Assignment(serverId, agentId, generation, mapping))))
            }

            path == "/api/v1/agent/snapshots" -> {
                begins += wire.decodeFromString(SnapshotBegin.serializer(), body)
                TestReply(body = "{}")
            }

            path.endsWith("/commit") -> {
                val begin = begins.last()
                TestReply(body = wire.encodeToString(SnapshotAck.serializer(), SnapshotAck(begin.scanId, begin.sequence, begin.digest, Instant.now().toString())))
            }

            else -> {
                TestReply(body = "{}")
            }
        }
    }

    private fun credentials(token: String = "token") = Credentials("id", token, Instant.now().plusSeconds(10 * 86400L).toString(), null)

    private fun config(
        credentials: Credentials? = credentials(),
        mappings: Map<String, String> = mapOf("main" to mods.toString())
    ) = secureWrite(
        state.resolve("config.json"),
        wire.encodeToString(AgentConfig.serializer(), AgentConfig(server.endpoint, controlPlane, agentId, credentials, mappings))
    )

    private fun cli(vararg args: String): Pair<Int, String> {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            return CommandLine(Agent()).execute("--state-dir", state.toString(), *args) to captured.toString()
        } finally {
            System.setOut(original)
        }
    }

    /** A first scan uploads and commits the inventory, and an unchanged second scan only confirms it. */
    @Test
    fun scanCommitsAndThenConfirmsUnchangedInventory() {
        config()
        assertEquals(0, cli("scan").first)
        assertEquals(1, begins.size)
        assertEquals(1, requests.count { it == "PUT /api/v1/agent/snapshots/${begins[0].scanId}/batches" })
        assertTrue(Files.exists(state.resolve("healthy-at")) && Files.exists(state.resolve("progress-$serverId.json")))
        assertFalse(Files.exists(state.resolve("pending-$serverId.json")))
        assertEquals(0, cli("scan").first)
        assertEquals(1, begins.size)
        jar(mods.resolve("beta.jar"), "beta")
        assertEquals(0, cli("scan", "--server", serverId).first)
        assertEquals(listOf(1L, 2L), begins.map { it.sequence })
        assertEquals(0, cli("status").first)
    }

    /** Credentials that expire within a day are rotated and stored before scanning. */
    @Test
    fun scanRotatesExpiringCredentials() {
        config(Credentials("id", "old", Instant.now().plusSeconds(3600).toString(), null))
        assertEquals(0, cli("scan").first)
        assertTrue(requests.contains("POST /api/v1/agent/credentials/rotate"))
        assertTrue(Files.readString(state.resolve("config.json")).contains("rotated"))
    }

    /** A server that is not assigned or has no local mapping is refused. */
    @Test
    fun scanRefusesUnassignedServerAndMissingMapping() {
        config()
        assertEquals(1, cli("scan", "--server", UUID.randomUUID().toString()).first)
        mapping = "ghost"
        assertEquals(1, cli("scan").first)
    }

    /** A pending inventory of an older generation is discarded and replaced by a fresh snapshot. */
    @Test
    fun stalePendingInventoryIsReplaced() {
        config()
        generation = 2
        val stale = SnapshotBegin(serverId, UUID.randomUUID().toString(), 1, 1, Instant.now().toString(), "digest", 0)
        secureWrite(state.resolve("pending-$serverId.json"), wire.encodeToString(PendingInventory.serializer(), PendingInventory(stale, emptyList())))
        assertEquals(0, cli("scan").first)
        assertEquals(listOf(2L), begins.map { it.generation })
        assertFalse(Files.exists(state.resolve("pending-$serverId.json")))
    }

    /** A pending inventory of the current generation is redelivered unchanged. */
    @Test
    fun currentPendingInventoryIsRedelivered() {
        config()
        val pending = SnapshotBegin(serverId, UUID.randomUUID().toString(), 1, 5, Instant.now().toString(), "digest", 0)
        secureWrite(state.resolve("pending-$serverId.json"), wire.encodeToString(PendingInventory.serializer(), PendingInventory(pending, emptyList())))
        assertEquals(0, cli("scan").first)
        assertEquals(listOf(pending.scanId), begins.map { it.scanId })
    }

    /** Doctor verifies identity, authentication and mapped directories. */
    @Test
    fun doctorPassesAndDetectsMissingMapping() {
        config()
        assertTrue(cli("doctor").second.contains("passed"))
        config(mappings = mapOf("main" to home.resolve("gone").toString()))
        assertEquals(1, cli("doctor").first)
    }

    /** Setup stores the verified identity and register stores the issued credentials. */
    @Test
    fun setupAndRegister() {
        assertEquals(0, cli("setup", "--server-url", "${server.endpoint}/", "--trust-server-id", controlPlane).first)
        val tokenFile = Files.writeString(home.resolve("token"), "secret\n")
        assertEquals(0, cli("register", "--enrollment-token-file", tokenFile.toString()).first)
        assertTrue(Files.readString(state.resolve("config.json")).contains("\"token\""))
        assertEquals(0, cli("setup", "--server-url", server.endpoint, "--trust-server-id", controlPlane).first)
        assertEquals(1, cli("setup", "--server-url", server.endpoint, "--trust-server-id", UUID.randomUUID().toString()).first)
    }

    /** The long-running loop survives failed passes, backs off and ends with the interrupt that stops it. */
    @Test
    fun loopRetriesAndStopsOnInterrupt() {
        config()
        server.close()
        val agent = Agent().also { it.stateDir = state }
        var failure: Throwable? = null
        val worker = Thread { runCatching { agent.loop(30) }.onFailure { failure = it } }
        worker.start()
        Thread.sleep(1500)
        worker.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertTrue(failure is InterruptedException)
    }
}
