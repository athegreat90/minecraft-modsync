package net.modsync
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.zip.*
import kotlin.test.*
class ScannerTest {
    @TempDir lateinit var dir: Path
    private fun jar(name: String, metadata: String): Path = dir.resolve(name).also { p -> ZipOutputStream(Files.newOutputStream(p)).use { it.putNextEntry(ZipEntry("META-INF/neoforge.mods.toml")); it.write(metadata.toByteArray()); it.closeEntry() } }
    @Test fun `extracts ids without treating display test as sidedness`() {
        val p=jar("test.jar", "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"test\"\nversion=\"1\"\ndisplayTest=\"IGNORE_ALL_VERSION\"")
        val entry=Scanner().scan(dir).single(); assertEquals(listOf("test"),entry.modIds); assertEquals(fileHash(p),entry.sha512); assertEquals(Classification.UNKNOWN,entry.classification)
    }
    @Test fun `malformed archive fails whole scan`() { Files.writeString(dir.resolve("broken.jar"), "broken"); assertFails { Scanner().scan(dir) } }
    @Test fun `symlink cannot escape mods directory`() {
        val target=Files.createTempFile("outside", ".jar")
        try { org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"),"Symlink creation requires Windows privileges; validate on a privileged platform runner"); Files.createSymbolicLink(dir.resolve("escape.jar"),target); assertFails { Scanner().scan(dir) } } finally { Files.delete(target) }
    }
    @Test fun `digest independent of enumeration order`() { val a=ModEntry("a.jar","a".repeat(128),1); val b=a.copy(fileName="b.jar"); assertEquals(inventoryDigest(listOf(a,b)),inventoryDigest(listOf(b,a))) }
}
