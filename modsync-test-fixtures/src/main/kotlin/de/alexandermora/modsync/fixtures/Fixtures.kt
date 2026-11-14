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

    /**
     * Writes a synthetic NeoForge JAR declaring several mods with versions and optional dependency ranges.
     *
     * A [ModInfo.version] of null omits the `version` key; the entry time is fixed so the output is reproducible.
     *
     * @param path Destination file.
     * @param mods Mods to declare; ranges become `minecraft` and `neoforge` dependency declarations.
     * @param manifestVersion Optional `Implementation-Version` written to `META-INF/MANIFEST.MF`.
     * @param payload Optional extra file content stored as `payload.bin`, so equal metadata can yield different hashes.
     * @return [path].
     */
    fun modJar(path: Path, vararg mods: ModInfo, manifestVersion: String? = null, payload: String = ""): Path {
        val toml =
            buildString {
                append("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n")
                mods.forEach { mod ->
                    append("[[mods]]\nmodId=\"${mod.id}\"\n")
                    mod.version?.let { append("version=\"$it\"\n") }
                }
                mods.forEach { mod ->
                    mod.minecraftRange?.let { append("[[dependencies.${mod.id}]]\nmodId=\"minecraft\"\ntype=\"required\"\nversionRange=\"$it\"\n") }
                    mod.loaderRange?.let { append("[[dependencies.${mod.id}]]\nmodId=\"neoforge\"\ntype=\"required\"\nversionRange=\"$it\"\n") }
                }
            }
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun put(name: String, content: String) {
                val entry = ZipEntry(name)
                entry.time = 0
                zip.putNextEntry(entry)
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("META-INF/neoforge.mods.toml", toml)
            manifestVersion?.let { put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: $it\r\n") }
            if (payload.isNotEmpty()) put("payload.bin", payload)
        }
        return path
    }
}
