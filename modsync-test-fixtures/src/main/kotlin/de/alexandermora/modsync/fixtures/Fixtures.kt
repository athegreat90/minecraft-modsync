package de.alexandermora.modsync.fixtures

import de.alexandermora.modsync.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builders for synthetic test inputs. */
object Fixtures {
    /**
     * Writes a synthetic NeoForge JAR declaring one mod.
     *
     * The entry time is fixed so the output is reproducible.
     *
     * @param path Destination file.
     * @param id Mod id declared in the metadata.
     * @return [path].
     */
    fun jar(path: Path, id: String): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val entry = ZipEntry("META-INF/neoforge.mods.toml")
            entry.time = 0
            zip.putNextEntry(entry)
            zip.write("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"$id\"\nversion=\"1\"".toByteArray())
            zip.closeEntry()
        }
        return path
    }
}
