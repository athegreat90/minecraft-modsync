package de.alexandermora.modsync.client

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the child JVM command used by `modsync interactive` so it cannot silently drift from the TUI distribution. */
class InteractiveLauncherTest {
    private val library = Path.of("tui", "lib")

    @Test
    fun classPathIsAPlainWildcardString() {
        val command = interactiveCommand("java", library)
        assertEquals("tui${File.separator}lib${File.separator}*", command[command.indexOf("-cp") + 1])
    }

    @Test
    fun launchesTheTuiMainClass() {
        assertEquals("de.alexandermora.modsync.client.tui.InteractiveKt", interactiveCommand("java", library).last())
    }

    @Test
    fun grantsNativeAccessToTheTerminalLibrary() {
        assertTrue("--enable-native-access=ALL-UNNAMED" in interactiveCommand("java", library))
    }

    @Test
    fun mainClassMatchesTheTuiBuildScript() {
        val script = Files.readString(Path.of("..", "modsync-client-tui", "build.gradle.kts"))
        val declared = Regex("""mainClass\.set\("([^"]+)"\)""").find(script)?.groupValues?.get(1)
        assertEquals(declared, TUI_MAIN_CLASS)
    }
}
