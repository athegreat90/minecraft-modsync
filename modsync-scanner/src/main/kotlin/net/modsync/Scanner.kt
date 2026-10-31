package net.modsync

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.tomlj.Toml
import kotlinx.serialization.json.*

class Scanner(private val maxJarBytes: Long = 512L * 1024 * 1024, private val maxFiles: Int = 10000) {
    fun scan(mods: Path): List<ModEntry> {
        require(Files.isDirectory(mods, NOFOLLOW_LINKS) && !Files.isSymbolicLink(mods)) { "Mods directory must exist and must not be a symlink" }
        val root = mods.toRealPath()
        val paths = Files.list(root).use { it.filter { p -> p.fileName.toString().endsWith(".jar", true) }.sorted().limit(maxFiles.toLong() + 1).toList() }
        require(paths.size <= maxFiles) { "Too many mods" }
        var metadataBytes=0L
        val entries = paths.map { path ->
            require(!Files.isSymbolicLink(path) && path.toRealPath().parent == root) { "Symlinks are not accepted" }
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.isRegularFile && before.size() <= maxJarBytes && safeFileName(path.fileName.toString())) { "Unsafe or oversized JAR" }
            val digest = MessageDigest.getInstance("SHA-512")
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= maxJarBytes); digest.update(buffer, 0, n) }
            }
            val ids = mutableListOf<String>(); val metadata = mutableMapOf<String, String>(); val evidence = mutableListOf<Evidence>()
            ZipFile(path.toFile()).use { zip ->
                require(zip.size() <= 100000) { "Archive has too many entries" }
                for (name in listOf("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "fabric.mod.json", "META-INF/jarjar/metadata.json")) {
                    val entry = zip.getEntry(name) ?: continue
                    require(entry.size in 0..1048576) { "Metadata exceeds limit" }
                    val bytes = zip.getInputStream(entry).use { it.readNBytes(1048577) }; require(bytes.size <= 1048576)
                    metadataBytes += bytes.size
                    require(metadataBytes <= 2 * 1024 * 1024) { "Inventory metadata exceeds scanner memory budget" }
                    val text = bytes.toString(Charsets.UTF_8)
                    metadata[name] = text
                    if (name.endsWith(".toml")) {
                        val toml = Toml.parse(text); require(!toml.hasErrors()) { "Invalid mod metadata" }
                        val table = toml.getArray("mods")
                        if (table != null) for (i in 0 until table.size()) table.getTable(i).getString("modId")?.let(ids::add)
                    } else if (name == "fabric.mod.json") {
                        wireJson.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content?.let(ids::add)
                    }
                    evidence += Evidence("jar-metadata", name)
                }
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) { "JAR changed during scan; snapshot discarded" }
            ModEntry(path.fileName.toString(), digest.digest().toHex(), after.size(), ids.distinct(), evidence = evidence, metadata = metadata)
        }
        require(paths.map { it.fileName.toString() }.toSet() == Files.list(root).use { it.filter { p -> p.fileName.toString().endsWith(".jar", true) }.map { it.fileName.toString() }.toList().toSet() }) { "Directory changed during scan" }
        val hashes=entries.groupingBy { it.sha512 }.eachCount()
        val ids=entries.flatMap { it.modIds.distinct() }.groupingBy { it }.eachCount()
        return entries.map { entry ->
            val duplicateEvidence=buildList {
                if (hashes.getValue(entry.sha512)>1) add(Evidence("duplicate-hash","Identical JAR content appears under multiple filenames"))
                entry.modIds.filter { ids.getValue(it)>1 }.forEach { add(Evidence("duplicate-mod-id",it)) }
            }
            entry.copy(evidence=entry.evidence+duplicateEvidence)
        }
    }
    private val wireJson = Json { ignoreUnknownKeys = true }
}
fun fileHash(path: Path): String = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
    val digest = MessageDigest.getInstance("SHA-512"); val buffer = ByteArray(65536)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().toHex()
}
