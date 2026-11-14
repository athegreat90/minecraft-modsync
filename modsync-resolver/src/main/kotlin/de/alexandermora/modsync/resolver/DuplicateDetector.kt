package de.alexandermora.modsync.resolver

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.ModInfo
import de.alexandermora.modsync.domain.ModVersion

/**
 * One file that provides a mod id, either already installed or about to be installed.
 *
 * @property fileName Plain file name inside `mods`.
 * @property sha512 Hash of the file content.
 * @property info The mod this file declares for the contested id.
 * @property modCount Number of mods the JAR declares; more than one means a multi-mod JAR.
 * @property pinned True when a manifest or personal profile requires exactly this file.
 * @property owned True when ModSync installed this exact installed file and may remove it.
 */
internal data class Contender(
    val fileName: String,
    val sha512: String,
    val info: ModInfo,
    val modCount: Int,
    val pinned: Boolean,
    val owned: Boolean
) {
    /** Parsed version, or null when it is missing or not understood. */
    val version: ModVersion? get() = ModVersion.parse(info.version)

    /** Version text for explanations. */
    val versionText: String get() = info.version ?: "unknown version"
}

/**
 * Finds files that provide the same mod id.
 *
 * Identity is the mod id declared in verified metadata, never the file name. A selected artifact replaces an
 * installed file of the same name, so updating a file in place is not a duplicate. Only identification happens
 * here; ordering and decisions belong to [DuplicateResolver].
 */
internal class DuplicateDetector {
    /**
     * Groups competing files by mod id.
     *
     * @param selected Artifacts the plan will install or keep.
     * @param local Installed files with their declared mods.
     * @param managed Hashes of the files ModSync installed, keyed by file name.
     * @return Contenders per mod id, only for ids provided by more than one file.
     */
    fun groups(selected: List<Artifact>, local: List<ModEntry>, managed: Map<String, String>): Map<String, List<Contender>> {
        val selectedNames = selected.map { it.fileName.lowercase() }.toSet()
        val pinned = selected.flatMap { artifact -> artifact.mods.map { Contender(artifact.fileName, artifact.sha512, it, artifact.mods.size, pinned = true, owned = false) } }
        val installed =
            local
                .filter { it.fileName.lowercase() !in selectedNames }
                .flatMap { entry ->
                    val owned = managed[entry.fileName] == entry.sha512
                    entry.mods.map { Contender(entry.fileName, entry.sha512, it, entry.mods.size, pinned = false, owned = owned) }
                }
        return (pinned + installed)
            .groupBy { it.info.id }
            .filterValues { group -> group.map { it.fileName.lowercase() }.distinct().size > 1 }
    }
}
