package de.alexandermora.modsync.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the agent against an in-process HTTPS control plane that trusts a throw-away self-signed certificate. */
class AgentScanTest {
    @TempDir
    lateinit var home: Path
    private lateinit var server: HttpsServer
    private lateinit var previousContext: SSLContext
    private lateinit var state: Path
    private lateinit var mods: Path
    private val controlPlane = UUID.randomUUID().toString()
    private val agentId = UUID.randomUUID().toString()
    private val serverId = UUID.randomUUID().toString()
    private val requests = mutableListOf<String>()
    private var generation = 1L
    private var mapping = "main"
    private val begins = mutableListOf<SnapshotBegin>()
    private lateinit var endpoint: String

    /** Starts the HTTPS control plane and prepares one mods directory with a single jar. */
    @BeforeEach
    fun prepare() {
        val keystore = home.resolve("keystore.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val process =
            ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias",
                "test",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=127.0.0.1",
                "-ext",
                "san=ip:127.0.0.1,dns:localhost",
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit"
            ).redirectErrorStream(true).start()
        check(process.waitFor() == 0) { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keystore).use { load(it, "changeit".toCharArray()) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        previousContext = SSLContext.getDefault()
        SSLContext.setDefault(context)
        server = HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { handle(it) }
        server.start()
        endpoint = "https://127.0.0.1:${server.address.port}"
        state = home.resolve("state")
        mods = Files.createDirectories(home.resolve("mods"))
        jar(mods.resolve("alpha.jar"), "alpha")
    }

    @AfterEach
    fun cleanUp() {
        server.stop(0)
        SSLContext.setDefault(previousContext)
    }

    private fun jar(
        path: Path,
        id: String
    ) = ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/neoforge.mods.toml").also { it.time = 0 })
        zip.write("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"$id\"\nversion=\"1\"".toByteArray())
        zip.closeEntry()
    }

    private fun reply(
        exchange: HttpExchange,
        body: String
    ) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        requests += "${exchange.requestMethod} $path"
        when {
            path == "/api/v1/info" -> {
                reply(exchange, wire.encodeToString(Capabilities.serializer(), Capabilities(controlPlane, PROTOCOL)))
            }

            path == "/api/v1/enroll" -> {
                reply(exchange, wire.encodeToString(Credentials.serializer(), credentials()))
            }

            path == "/api/v1/agent/credentials/rotate" -> {
                reply(exchange, wire.encodeToString(Credentials.serializer(), credentials("rotated")))
            }

            path == "/api/v1/agent/assignments" -> {
                reply(exchange, wire.encodeToString(ListSerializer(Assignment.serializer()), listOf(Assignment(serverId, agentId, generation, mapping))))
            }

            path == "/api/v1/agent/snapshots" -> {
                begins += wire.decodeFromString(SnapshotBegin.serializer(), body)
                reply(exchange, "{}")
            }

            path.endsWith("/commit") -> {
                val begin = begins.last()
                reply(exchange, wire.encodeToString(SnapshotAck.serializer(), SnapshotAck(begin.scanId, begin.sequence, begin.digest, Instant.now().toString())))
            }

            else -> {
                reply(exchange, "{}")
            }
        }
    }

    private fun credentials(token: String = "token") = Credentials("id", token, Instant.now().plusSeconds(10 * 86400L).toString(), null)

    private fun config(
        credentials: Credentials? = credentials(),
        mappings: Map<String, String> = mapOf("main" to mods.toString())
    ) = secureWrite(state.resolve("config.json"), wire.encodeToString(AgentConfig.serializer(), AgentConfig(endpoint, controlPlane, agentId, credentials, mappings)))

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
        assertEquals(0, cli("setup", "--server-url", "$endpoint/", "--trust-server-id", controlPlane).first)
        val tokenFile = Files.writeString(home.resolve("token"), "secret\n")
        assertEquals(0, cli("register", "--enrollment-token-file", tokenFile.toString()).first)
        assertTrue(Files.readString(state.resolve("config.json")).contains("\"token\""))
        assertEquals(0, cli("setup", "--server-url", endpoint, "--trust-server-id", controlPlane).first)
        assertEquals(1, cli("setup", "--server-url", endpoint, "--trust-server-id", UUID.randomUUID().toString()).first)
    }
}
