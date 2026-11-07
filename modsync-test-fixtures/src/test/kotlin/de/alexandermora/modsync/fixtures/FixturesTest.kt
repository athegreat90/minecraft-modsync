package de.alexandermora.modsync.fixtures

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

class FixturesTest {
    @TempDir lateinit var dir: Path

    @Test fun `jar declares the requested mod id and is reproducible`() {
        val a = Fixtures.jar(dir.resolve("a.jar"), "demo")
        val b = Fixtures.jar(dir.resolve("b.jar"), "demo")
        ZipFile(a.toFile()).use { zip -> assertContains(zip.getInputStream(zip.getEntry("META-INF/neoforge.mods.toml")).readAllBytes().toString(Charsets.UTF_8), "modId=\"demo\"") }
        assertContentEquals(a.toFile().readBytes(), b.toFile().readBytes())
    }
}
