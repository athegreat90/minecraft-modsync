package de.alexandermora.modsync.client

import de.alexandermora.modsync.fixtures.Fixtures
import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
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

/** Exercises the offline commands of the client command line against a temporary state directory. */
class ClientCommandsTest {
    @TempDir
    lateinit var home: Path
    private val controlPlane = UUID.randomUUID().toString()
    private val device = UUID.randomUUID().toString()
    private lateinit var state: Path
    private lateinit var instance: Path

    /** Writes a configuration for an unreachable server with one registered instance named `main`. */
    @BeforeEach
    fun prepare() {
        state = home.resolve("state")
        instance = Files.createDirectories(home.resolve("instance"))
        Installer(instance).configureTarget(InstanceTarget("1.21.1", "neoforge"))
        secureWrite(
            state.resolve("config.json"),
            wire.encodeToString(ClientConfig("https://modsync.invalid", controlPlane, device, null, mapOf("main" to instance.toRealPath().toString())))
        )
    }

    /** Runs the CLI with the state directory and returns the exit code and captured standard output. */
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

    /** The configuration is shown without secrets. */
    @Test
    fun configShow() {
        val (code, out) = cli("config", "show")
        assertEquals(0, code)
        assertTrue(out.contains("server-url=https://modsync.invalid") && out.contains("control-plane-id=$controlPlane") && out.contains("device-id=$device"))
    }

    /** Doctor lists registered instances and fails when one disappeared. */
    @Test
    fun doctor() {
        val (code, out) = cli("doctor")
        assertEquals(0, code)
        assertTrue(out.contains("Instance registered: main") && out.contains("Local diagnostics passed"))
        instance.toFile().deleteRecursively()
        assertEquals(1, cli("doctor").first)
    }

    /** Instances are registered only with valid names and directories, and persist their target. */
    @Test
    fun instancesAdd() {
        val other = Files.createDirectories(home.resolve("other"))
        val (code, out) = cli("instances", "add", "second", other.toString(), "--minecraft-version", "1.20.1", "--loader", "fabric", "--loader-version", "0.15")
        assertEquals(0, code)
        assertTrue(out.contains("Instance registered"))
        assertEquals(InstanceTarget("1.20.1", "fabric", "0.15"), Installer(other).target())
        assertTrue(cli("config", "show").first == 0 && Files.readString(state.resolve("config.json")).contains("second"))
        assertEquals(1, cli("instances", "add", "bad name", other.toString(), "--minecraft-version", "1.20.1", "--loader", "fabric").first)
        assertEquals(1, cli("instances", "add", "third", home.resolve("missing").toString(), "--minecraft-version", "1.20.1", "--loader", "fabric").first)
    }

    /** Discovery only prints candidates and never registers anything. */
    @Test
    fun instancesDiscover() {
        val (code, out) = cli("instances", "discover")
        assertEquals(0, code)
        assertTrue(out.contains("Candidates only"))
    }

    /** Scanning an instance prints the inventory of its mods directory offline. */
    @Test
    fun modsScanAndList() {
        Fixtures.jar(Installer(instance).mods.resolve("a.jar"), "alpha")
        val scan = cli("mods", "scan", "--instance", "main")
        val list = cli("mods", "list", "--instance", "main")
        assertEquals(0, scan.first)
        assertTrue(scan.second.contains("a.jar") && scan.second.contains("alpha"))
        assertEquals(scan.second, list.second)
        assertEquals(1, cli("mods", "scan", "--instance", "ghost").first)
    }

    /** Cached manifests are shown with their age and never claim to be current. */
    @Test
    fun syncCached() {
        val server = UUID.randomUUID().toString()
        assertEquals(1, cli("sync", "cached", "--server", server).first)
        Files.writeString(state.resolve("manifest-$server.cached-at"), Instant.now().minusSeconds(120).toString())
        Files.writeString(state.resolve("manifest-$server.json"), "{\"cached\":true}")
        val (code, out) = cli("sync", "cached", "--server", server)
        assertEquals(0, code)
        assertTrue(out.contains("Cached at") && out.contains("{\"cached\":true}"))
        assertEquals(1, cli("sync", "cached", "--server", "not-a-uuid").first)
    }

    /** Rollback and verification refuse to act without recorded state. */
    @Test
    fun syncWithoutRecordedState() {
        assertEquals(1, cli("sync", "rollback", "--instance", "main").first)
        assertEquals(1, cli("sync", "verify", "--instance", "main").first)
    }

    /** Online commands fail closed when the server is unreachable. */
    @Test
    fun onlineCommandsFailClosed() {
        assertEquals(1, cli("connection", "test").first)
        assertEquals(1, cli("servers", "list").first)
        assertEquals(1, cli("personal", "list").first)
        assertEquals(1, cli("login", "--username", "u", "--password-file", home.resolve("pw").toString()).first)
    }

    /** The terminal UI launcher needs an explicit distribution directory. */
    @Test
    fun interactiveNeedsDistribution() {
        assertEquals(1, cli("interactive", "--tui-home", home.resolve("missing").toString()).first)
        assertFalse(Files.exists(home.resolve("missing")))
    }
}
