package de.alexandermora.modsync.scanner

import de.alexandermora.modsync.domain.ModInfo
import de.alexandermora.modsync.fixtures.Fixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

/** Verifies that the scanner reports each declared mod with its version and dependency ranges. */
class ScannerModInfoTest {
    @TempDir lateinit var dir: Path

    private fun scanOnly() = Scanner().scan(dir).single()

    @Test fun `declared version and ranges are read from neoforge metadata`() {
        Fixtures.modJar(dir.resolve("a.jar"), ModInfo("alpha", "1.2.3", "[1.21,1.22)", "[21.1,)"))
        assertEquals(listOf(ModInfo("alpha", "1.2.3", "[1.21,1.22)", "[21.1,)")), scanOnly().mods)
    }

    @Test fun `multi mod jars report every mod`() {
        Fixtures.modJar(dir.resolve("a.jar"), ModInfo("alpha", "1.0"), ModInfo("beta", "2.0"))
        assertEquals(listOf("alpha" to "1.0", "beta" to "2.0"), scanOnly().mods.map { it.id to it.version })
        assertEquals(listOf("alpha", "beta"), scanOnly().modIds)
    }

    @Test fun `jar version placeholder is resolved from the manifest`() {
        Fixtures.modJar(dir.resolve("a.jar"), ModInfo("alpha", "\${file.jarVersion}"), manifestVersion = "4.5.6")
        assertEquals("4.5.6", scanOnly().mods.single().version)
    }

    @Test fun `unresolved placeholder and missing version yield no version`() {
        Fixtures.modJar(dir.resolve("a.jar"), ModInfo("alpha", "\${file.jarVersion}"))
        Fixtures.modJar(dir.resolve("b.jar"), ModInfo("beta", null))
        val mods = Scanner().scan(dir).flatMap { it.mods }.associate { it.id to it.version }
        assertEquals(mapOf("alpha" to null, "beta" to null), mods)
    }

    private fun zip(name: String, entry: String, content: String = "") {
        ZipOutputStream(Files.newOutputStream(dir.resolve(name))).use { zip ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }

    @Test fun `fabric metadata contributes id and version`() {
        zip("f.jar", "fabric.mod.json", """{"schemaVersion":1,"id":"fab","version":"0.9.1"}""")
        assertEquals(listOf(ModInfo("fab", "0.9.1")), scanOnly().mods)
    }

    @Test fun `jars without metadata have no mods`() {
        zip("plain.jar", "x.txt")
        assertEquals(emptyList(), scanOnly().mods)
    }
}
