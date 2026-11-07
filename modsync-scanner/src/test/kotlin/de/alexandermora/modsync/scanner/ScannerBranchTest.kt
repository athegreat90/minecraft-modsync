package de.alexandermora.modsync.scanner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Verifies metadata formats, evidence and every refusal path of the read-only scanner. */
class ScannerBranchTest {
    @TempDir
    lateinit var dir: Path

    private fun jar(
        name: String,
        vararg files: Pair<String, String>
    ): Path =
        dir.resolve(name).also { path ->
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                files.forEach { (entry, text) ->
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun toml(vararg ids: String) =
        "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n" + ids.joinToString("\n") { "[[mods]]\nmodId=\"$it\"\nversion=\"1\"" }

    /** Every supported metadata file contributes ids and evidence. */
    @Test
    fun readsAllMetadataFormats() {
        jar("fabric.jar", "fabric.mod.json" to "{\"id\":\"fab\",\"unknown\":1}")
        jar("legacy.jar", "META-INF/mods.toml" to toml("old1", "old2"))
        jar("jarjar.jar", "META-INF/jarjar/metadata.json" to "{}")
        jar("none.jar")
        val entries = Scanner().scan(dir).associateBy { it.fileName }
        assertEquals(listOf("fab"), entries.getValue("fabric.jar").modIds)
        assertEquals(listOf("old1", "old2"), entries.getValue("legacy.jar").modIds)
        assertTrue(entries.getValue("jarjar.jar").evidence.any { it.detail == "META-INF/jarjar/metadata.json" })
        assertTrue(entries.getValue("none.jar").modIds.isEmpty() && entries.getValue("none.jar").evidence.isEmpty())
    }

    /** TOML without a mods table yields no ids and only matching extensions are scanned. */
    @Test
    fun ignoresMissingTableAndOtherFiles() {
        jar("empty.JAR", "META-INF/neoforge.mods.toml" to "license=\"MIT\"")
        Files.writeString(dir.resolve("notes.txt"), "ignored")
        val entries = Scanner().scan(dir)
        assertEquals(listOf("empty.JAR"), entries.map { it.fileName })
        assertTrue(entries.single().modIds.isEmpty())
    }

    /** Identical content and shared mod ids are reported as evidence on every affected entry. */
    @Test
    fun reportsDuplicates() {
        jar("a.jar", "META-INF/neoforge.mods.toml" to toml("shared"))
        jar("b.jar", "META-INF/neoforge.mods.toml" to toml("shared"))
        val first = Scanner().scan(dir).first { it.fileName == "a.jar" }
        assertTrue(first.evidence.any { it.source == "duplicate-mod-id" && it.detail == "shared" })
        Files.copy(dir.resolve("a.jar"), dir.resolve("c.jar"))
        val copies = Scanner().scan(dir).filter { it.fileName in setOf("a.jar", "c.jar") }
        assertTrue(copies.all { entry -> entry.evidence.any { it.source == "duplicate-hash" } })
    }

    /** The directory must exist and contain a bounded number of reasonably sized, valid archives. */
    @Test
    fun refusesUnsafeInput() {
        assertFailsWith<IllegalArgumentException> { Scanner().scan(dir.resolve("missing")) }
        jar("one.jar")
        jar("two.jar")
        assertFailsWith<IllegalArgumentException> { Scanner(maxFiles = 1).scan(dir) }
        assertFailsWith<IllegalArgumentException> { Scanner(maxJarBytes = 10).scan(dir) }
    }

    /** Broken or oversized metadata discards the whole scan. */
    @Test
    fun refusesBadMetadata() {
        jar("bad.jar", "META-INF/neoforge.mods.toml" to "[[mods")
        assertFailsWith<IllegalArgumentException> { Scanner().scan(dir) }
        Files.delete(dir.resolve("bad.jar"))
        jar("big.jar", "fabric.mod.json" to "x".repeat(1048577))
        assertFailsWith<IllegalArgumentException> { Scanner().scan(dir) }
    }

    /** The combined metadata of all archives is bounded. */
    @Test
    fun refusesExcessiveTotalMetadata() {
        val big = "{\"pad\":\"" + "x".repeat(900_000) + "\"}"
        repeat(3) { jar("m$it.jar", "fabric.mod.json" to big) }
        assertFailsWith<IllegalArgumentException> { Scanner().scan(dir) }
    }

    /** File hashing is a SHA-512 hex digest of the content. */
    @Test
    fun hashesFiles() {
        val path = jar("h.jar")
        assertEquals(128, fileHash(path).length)
    }
}
