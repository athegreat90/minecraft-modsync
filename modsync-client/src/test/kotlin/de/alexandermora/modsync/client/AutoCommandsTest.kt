package de.alexandermora.modsync.client

import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the `modsync auto` command line against a temporary state directory without network access. */
class AutoCommandsTest {
    @TempDir
    lateinit var home: Path
    private val server = UUID.randomUUID().toString()
    private lateinit var state: Path

    /** Registers an instance named `main` and writes a client configuration that cannot reach any server. */
    @BeforeEach
    fun prepare() {
        state = home.resolve("state")
        val instance = Files.createDirectories(home.resolve("instance"))
        Installer(instance).configureTarget(InstanceTarget("1.21.1", "neoforge"))
        secureWrite(
            state.resolve("config.json"),
            wire.encodeToString(ClientConfig("https://modsync.invalid", UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, mapOf("main" to instance.toRealPath().toString())))
        )
    }

    /** Runs the CLI with the state directory and returns the exit code and captured standard output. */
    private fun cli(vararg args: String): Pair<Int, String> {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            val code = clientCommandLine().execute("--state-dir", state.toString(), *args)
            return code to captured.toString()
        } finally {
            System.setOut(original)
        }
    }

    /** Consent without a console and without `--yes` is refused and nothing is stored. */
    @Test
    fun enableNeedsConsent() {
        val (code, _) = cli("auto", "enable", "--instance", "main", "--server", server)
        assertEquals(1, code)
        assertEquals("No instance has automatic synchronization enabled", cli("auto", "status").second.trim())
    }

    /** Manual is not a valid automatic policy, and unknown instances cannot be enabled. */
    @Test
    fun enableRejectsManualAndUnknownInstances() {
        assertEquals(1, cli("auto", "enable", "--instance", "main", "--server", server, "--policy", "MANUAL", "--yes").first)
        assertEquals(1, cli("auto", "enable", "--instance", "ghost", "--server", server, "--yes").first)
        assertEquals(1, cli("auto", "enable", "--instance", "main", "--server", "not-a-uuid", "--yes").first)
    }

    /** Enable, status, a failing offline pass and disable form a consistent lifecycle that keeps the instance id stable. */
    @Test
    fun lifecycle() {
        assertEquals(0, cli("auto", "enable", "--instance", "main", "--server", server, "--policy", "AUTO_APPROVED_UPDATE", "--yes").first)
        val store = AutoStore(state)
        val firstId = store.load().getValue("main").instanceId
        val status = cli("auto", "status").second
        assertTrue(status.contains("main") && status.contains("AUTO_APPROVED_UPDATE") && status.contains(server))
        assertEquals(0, cli("auto", "enable", "--instance", "main", "--server", server, "--yes").first)
        assertEquals(firstId, store.load().getValue("main").instanceId)
        assertEquals(1, cli("auto", "run", "--once").first)
        assertEquals(1, cli("auto", "run", "--once", "--instance", "ghost").first)
        assertTrue(cli("auto", "disable", "--instance", "main").second.contains("disabled"))
        assertTrue(cli("auto", "disable", "--instance", "main").second.contains("was not enabled"))
    }

    /** A pass over no enabled instances succeeds, and scheduler definitions are printed for supported platforms only. */
    @Test
    fun runAndSchedule() {
        assertEquals(0, cli("auto", "run", "--once").first)
        assertTrue(cli("auto", "schedule", "--platform", "systemd", "--executable", "/opt/modsync").second.contains("OnUnitActiveSec=30min"))
        assertTrue(cli("auto", "schedule", "--platform", "windows", "--executable", "C:\\modsync.exe", "--interval-minutes", "60").second.contains("/MO 60"))
        assertEquals(1, cli("auto", "schedule", "--platform", "cron", "--executable", "x").first)
    }
}
