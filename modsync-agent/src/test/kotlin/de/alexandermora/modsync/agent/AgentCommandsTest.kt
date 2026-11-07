package de.alexandermora.modsync.agent

import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the offline behavior of the agent command line against a temporary state directory. */
class AgentCommandsTest {
    @TempDir
    lateinit var home: Path
    private lateinit var state: Path
    private val controlPlane = UUID.randomUUID().toString()
    private val agentId = UUID.randomUUID().toString()

    @BeforeEach
    fun prepare() {
        state = home.resolve("state")
    }

    private fun config(
        credentials: Credentials? = null,
        mappings: Map<String, String> = emptyMap()
    ) = secureWrite(
        state.resolve("config.json"),
        wire.encodeToString(AgentConfig("https://modsync.invalid", controlPlane, agentId, credentials, mappings))
    )

    private fun credentials(expiresAt: String = Instant.now().plusSeconds(10 * 86400L).toString()) = Credentials("id", "token", expiresAt, null)

    /** Runs the agent with the state directory and returns the exit code and captured standard output. */
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

    /** Without a subcommand the usage text is printed. */
    @Test
    fun usageIsPrinted() {
        val (code, out) = cli()
        assertEquals(0, code)
        assertTrue(out.contains("modsync-agent") && out.contains("setup"))
    }

    /** Mappings are stored by alias with a real directory path and invalid input is refused. */
    @Test
    fun mapStoresAliasForRealDirectory() {
        config()
        val mods = Files.createDirectories(home.resolve("mods"))
        assertEquals(0, cli("map", "main", mods.toString()).first)
        val stored = wire.decodeFromString<AgentConfig>(Files.readString(state.resolve("config.json")))
        assertEquals(mapOf("main" to mods.toRealPath().toString()), stored.mappings)
        assertEquals(1, cli("map", "bad alias", mods.toString()).first)
        assertEquals(1, cli("map", "other", home.resolve("missing").toString()).first)
    }

    /** Mapping needs an existing configuration. */
    @Test
    fun mapWithoutConfigurationFails() {
        assertEquals(1, cli("map", "main", Files.createDirectories(home.resolve("mods")).toString()).first)
    }

    /** Status reports a fresh cycle and rejects stale or missing heartbeats. */
    @Test
    fun statusChecksHeartbeatAge() {
        assertEquals(1, cli("status").first)
        secureWrite(state.resolve("healthy-at"), Instant.now().minusSeconds(86400).toString())
        assertEquals(1, cli("status").first)
        secureWrite(state.resolve("healthy-at"), Instant.now().toString())
        val (code, out) = cli("status")
        assertEquals(0, code)
        assertTrue(out.contains("Last healthy cycle"))
    }

    /** The scan interval is bounded before any work starts. */
    @Test
    fun runRejectsUnsafeInterval() {
        assertEquals(1, cli("run", "--interval", "5").first)
        assertEquals(1, cli("run", "--interval", "100000").first)
    }

    /** Setup needs an explicit trusted identity and never reaches an unverifiable server. */
    @Test
    fun setupFailsClosed() {
        assertEquals(2, cli("setup", "--server-url", "https://modsync.invalid").first)
        assertEquals(1, cli("setup", "--server-url", "https://modsync.invalid", "--trust-server-id", controlPlane).first)
        assertEquals(1, cli("setup", "--server-url", "http://modsync.invalid", "--trust-server-id", controlPlane).first)
        assertTrue(Files.notExists(state.resolve("config.json")))
    }

    /** Registration refuses an already enrolled agent and an unreachable server. */
    @Test
    fun registerFailsClosed() {
        val tokenFile = Files.writeString(home.resolve("token"), "secret\n")
        config(credentials())
        assertEquals(1, cli("register", "--enrollment-token-file", tokenFile.toString()).first)
        config()
        assertEquals(1, cli("register", "--enrollment-token-file", tokenFile.toString()).first)
    }

    /** Scanning and diagnostics refuse to proceed without credentials or a reachable server. */
    @Test
    fun onlineCommandsFailClosed() {
        config()
        assertEquals(1, cli("scan").first)
        assertEquals(1, cli("doctor").first)
        config(credentials())
        assertEquals(1, cli("scan", "--server", UUID.randomUUID().toString()).first)
        assertEquals(1, cli("doctor").first)
        assertTrue(Files.exists(state.resolve("agent.lock")))
    }
}
