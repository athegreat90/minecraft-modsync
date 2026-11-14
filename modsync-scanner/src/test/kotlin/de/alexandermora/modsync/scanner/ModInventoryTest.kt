package de.alexandermora.modsync.scanner

import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.ModInfo
import de.alexandermora.modsync.fixtures.Fixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Verifies duplicate mod detection over scanned files and the cheap mods folder fingerprint. */
class ModInventoryTest {
    @TempDir lateinit var dir: Path

    private fun entry(file: String, vararg ids: String) = ModEntry(file, "a".repeat(128), 1, ids.toList())

    @Test fun `duplicate mod ids name every providing file`() {
        val entries = listOf(entry("b.jar", "alpha"), entry("a.jar", "alpha", "beta"), entry("c.jar", "gamma"))
        assertEquals(listOf("mod alpha is provided by a.jar, b.jar"), duplicateMods(entries))
    }

    @Test fun `a single provider or no mod id is not a duplicate`() {
        assertEquals(emptyList(), duplicateMods(listOf(entry("a.jar", "alpha"), entry("b.jar"))))
    }

    @Test fun `fingerprint changes when a jar is added renamed or rewritten and ignores other files`() {
        val first = Fixtures.modJar(dir.resolve("a.jar"), ModInfo("alpha", "1"))
        val base = directoryFingerprint(dir)
        Files.writeString(dir.resolve("notes.txt"), "x")
        assertEquals(base, directoryFingerprint(dir))
        Fixtures.modJar(dir.resolve("b.jar"), ModInfo("beta", "1"))
        val added = directoryFingerprint(dir)
        assertNotEquals(base, added)
        Files.setLastModifiedTime(first, FileTime.fromMillis(1_000_000))
        assertNotEquals(added, directoryFingerprint(dir))
        Files.move(dir.resolve("b.jar"), dir.resolve("c.jar"))
        assertNotEquals(added, directoryFingerprint(dir))
    }
}
