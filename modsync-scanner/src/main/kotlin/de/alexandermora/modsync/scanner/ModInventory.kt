package de.alexandermora.modsync.scanner

import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.sha512
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Lists mod ids that more than one scanned file provides.
 *
 * Independent of any manifest or ownership, so it also covers files ModSync did not install.
 *
 * @param entries Scanned files.
 * @return One sorted explanation per duplicated mod id, empty when every mod id has a single provider.
 */
fun duplicateMods(entries: List<ModEntry>): List<String> =
    entries
        .flatMap { entry -> entry.modIds.map { it to entry.fileName } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.distinct().size > 1 }
        .map { (id, files) -> "mod $id is provided by ${files.distinct().sorted().joinToString()}" }
        .sorted()

/**
 * Fingerprints the top-level JAR files of a mods directory without reading their content.
 *
 * Covers file names, sizes and modification times, so any added, removed, renamed or rewritten file changes it
 * while the check stays much cheaper than hashing every JAR.
 *
 * @param mods Mods directory.
 * @return Lower-case hex digest; a fixed digest of the empty listing when the directory has no JARs.
 */
fun directoryFingerprint(mods: Path): String {
    val lines =
        Files.list(mods).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                .map { path ->
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    "${path.fileName}\u0000${attributes.size()}\u0000${attributes.lastModifiedTime().toMillis()}"
                }.sorted()
                .toList()
        }
    return sha512(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
}
