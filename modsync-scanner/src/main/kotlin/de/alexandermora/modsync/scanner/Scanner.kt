package de.alexandermora.modsync.scanner

import de.alexandermora.modsync.domain.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Read-only scanner for a mods directory.
 *
 * @property maxJarBytes Largest accepted JAR size in bytes.
 * @property maxFiles Largest accepted number of JAR files.
 */
class Scanner(private val maxJarBytes: Long = 512L * 1024 * 1024, private val maxFiles: Int = 10000) {
    /**
     * Scans the top-level `.jar` files of a mods directory.
     *
     * Hashes content and reads known metadata entries without extracting or executing anything. Fails as a
     * whole on symlinks, unsafe names, size or count limits, or if files change during the scan. Duplicate
     * hashes and mod ids are flagged as evidence; classification stays UNKNOWN.
     *
     * @param mods Mods directory; must exist and must not be a symlink.
     * @return One entry per JAR, sorted by path.
     */
    fun scan(mods: Path): List<ModEntry> {
        require(Files.isDirectory(mods, NOFOLLOW_LINKS) && !Files.isSymbolicLink(mods)) { "Mods directory must exist and must not be a symlink" }
        val root = mods.toRealPath()
        val paths =
            Files.list(root).use {
                it
                    .filter { p -> p.fileName.toString().endsWith(".jar", true) }
                    .sorted()
                    .limit(maxFiles.toLong() + 1)
                    .toList()
            }
        require(paths.size <= maxFiles) { "Too many mods" }
        var metadataBytes = 0L
        val entries =
            paths.map { path ->
                require(!Files.isSymbolicLink(path) && path.toRealPath().parent == root) { "Symlinks are not accepted" }
                val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(before.isRegularFile && before.size() <= maxJarBytes && safeFileName(path.fileName.toString())) { "Unsafe or oversized JAR" }
                val digest = MessageDigest.getInstance("SHA-512")
                Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= maxJarBytes)
                        digest.update(buffer, 0, n)
                    }
                }
                val mods = mutableListOf<ModInfo>()
                val metadata = mutableMapOf<String, String>()
                val evidence = mutableListOf<Evidence>()
                ZipFile(path.toFile()).use { zip ->
                    require(zip.size() <= 100000) { "Archive has too many entries" }
                    for (name in listOf("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "fabric.mod.json", "META-INF/jarjar/metadata.json")) {
                        val entry = zip.getEntry(name) ?: continue
                        val bytes = readBounded(zip, entry)
                        metadataBytes += bytes.size
                        require(metadataBytes <= 2 * 1024 * 1024) { "Inventory metadata exceeds scanner memory budget" }
                        val text = bytes.toString(Charsets.UTF_8)
                        metadata[name] = text
                        mods += ModMetadataParser.parse(name, text) { manifestVersion(zip) }
                        evidence += Evidence("jar-metadata", name)
                    }
                }
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) { "JAR changed during scan; snapshot discarded" }
                val distinctMods = mods.distinctBy { it.id }
                ModEntry(path.fileName.toString(), digest.digest().toHex(), after.size(), distinctMods.map { it.id }, evidence = evidence, metadata = metadata, mods = distinctMods)
            }
        require(
            paths.map { it.fileName.toString() }.toSet() ==
                Files.list(root).use { it ->
                    it
                        .filter { p -> p.fileName.toString().endsWith(".jar", true) }
                        .map { it.fileName.toString() }
                        .toList()
                        .toSet()
                }
        ) { "Directory changed during scan" }
        val hashes = entries.groupingBy { it.sha512 }.eachCount()
        val ids = entries.flatMap { it.modIds.distinct() }.groupingBy { it }.eachCount()
        return entries.map { entry ->
            val duplicateEvidence =
                buildList {
                    if (hashes.getValue(entry.sha512) > 1) add(Evidence("duplicate-hash", "Identical JAR content appears under multiple filenames"))
                    entry.modIds.filter { ids.getValue(it) > 1 }.forEach { add(Evidence("duplicate-mod-id", it)) }
                }
            entry.copy(evidence = entry.evidence + duplicateEvidence)
        }
    }

    /**
     * Reads a metadata entry with the per-file size limit.
     *
     * @param zip Open archive.
     * @param entry Entry to read.
     * @return The entry content, at most 1 MiB.
     */
    private fun readBounded(zip: ZipFile, entry: ZipEntry): ByteArray {
        require(entry.size in 0..MAX_METADATA_BYTES) { "Metadata exceeds limit" }
        val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_METADATA_BYTES + 1) }
        require(bytes.size <= MAX_METADATA_BYTES)
        return bytes
    }

    /**
     * Reads the `Implementation-Version` of the JAR manifest, used to resolve `${file.jarVersion}`.
     *
     * @param zip Open archive.
     * @return The manifest version, or null when the manifest or the attribute is missing.
     */
    private fun manifestVersion(zip: ZipFile): String? =
        zip.getEntry("META-INF/MANIFEST.MF")?.let { ModMetadataParser.implementationVersion(readBounded(zip, it).toString(Charsets.UTF_8)) }

    private companion object {
        const val MAX_METADATA_BYTES = 1048576
    }
}

/**
 * Streams a file through SHA-512.
 *
 * Symlinks are not followed.
 *
 * @param path File to hash.
 * @return Lower-case hex digest.
 */
fun fileHash(path: Path): String =
    Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
        val digest = MessageDigest.getInstance("SHA-512")
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().toHex()
    }
