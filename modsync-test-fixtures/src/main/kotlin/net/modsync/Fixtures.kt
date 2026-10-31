package net.modsync
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
object Fixtures {
    fun jar(path: Path, id: String): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val entry=ZipEntry("META-INF/neoforge.mods.toml"); entry.time=0; zip.putNextEntry(entry)
            zip.write("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"$id\"\nversion=\"1\"".toByteArray()); zip.closeEntry()
        }
        return path
    }
}
