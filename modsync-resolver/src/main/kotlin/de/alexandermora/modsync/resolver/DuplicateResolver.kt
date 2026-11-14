package de.alexandermora.modsync.resolver

import de.alexandermora.modsync.domain.ModInfo
import de.alexandermora.modsync.domain.ModVersion
import de.alexandermora.modsync.domain.VersionOrder
import de.alexandermora.modsync.domain.VersionRange

/** Decision about one file that provides a mod id also provided by another file. */
sealed interface DuplicateVerdict {
    /** Explanation shown in the sync plan. */
    val reason: String

    /**
     * The file is an older (or identical, or not compatible) duplicate that may be removed once ownership or approval allows it.
     *
     * @property reason Explanation naming the kept version.
     */
    data class Older(override val reason: String) : DuplicateVerdict

    /**
     * The duplicate cannot be resolved automatically; the user must act.
     *
     * @property reason Explanation of what is unclear or forbidden.
     */
    data class Blocked(override val reason: String) : DuplicateVerdict
}

/** Whether a file declares support for the instance's Minecraft and loader version. */
private enum class Compatibility { COMPATIBLE, INCOMPATIBLE, UNKNOWN }

/**
 * Decides which file of each duplicated mod id is kept.
 *
 * Pure decision logic: a manifest-pinned file always wins, a newer installed file is never downgraded, and
 * whenever an order or compatibility cannot be established the verdict is [DuplicateVerdict.Blocked].
 * Locally installed duplicates without a pin keep the highest version that verifiably supports the instance.
 */
internal class DuplicateResolver {
    /**
     * Produces verdicts for every file that must not stay next to another one.
     *
     * @param groups Contenders per mod id from [DuplicateDetector].
     * @param minecraftVersion Minecraft version of the instance.
     * @param loaderVersion Loader version of the instance, or null when not pinned.
     * @return Verdicts keyed by file name; files that are kept have none. A blocked verdict wins over an older one.
     */
    fun decide(groups: Map<String, List<Contender>>, minecraftVersion: String, loaderVersion: String?): Map<String, DuplicateVerdict> {
        val verdicts = mutableMapOf<String, DuplicateVerdict>()
        val target = Target(minecraftVersion, loaderVersion)
        groups.toSortedMap().forEach { (modId, group) ->
            val decided = if (group.any { it.pinned }) decideWithPin(modId, group) else decideLocalOnly(modId, group, target)
            decided.forEach { (file, verdict) -> if (verdicts[file] !is DuplicateVerdict.Blocked) verdicts[file] = verdict }
        }
        return verdicts
    }

    private fun decideWithPin(modId: String, group: List<Contender>): Map<String, DuplicateVerdict> {
        val pins = group.filter { it.pinned }.distinctBy { it.fileName.lowercase() }
        if (pins.size > 1) {
            val reason = "Several selected artifacts provide mod $modId (${pins.joinToString { it.fileName }}); the manifest must select one"
            return pins.associate { it.fileName to DuplicateVerdict.Blocked(reason) }
        }
        val kept = pins.single()
        return group.filterNot { it.pinned }.associate { it.fileName to againstPin(modId, kept, it) }
    }

    private fun againstPin(modId: String, kept: Contender, other: Contender): DuplicateVerdict {
        val keptText = "$modId ${kept.versionText} (${kept.fileName})"
        val keptVersion = kept.version
        val otherVersion = other.version
        return when {
            other.sha512 == kept.sha512 -> {
                DuplicateVerdict.Older("Identical copy of the kept file; keeping $keptText")
            }

            other.modCount > 1 -> {
                DuplicateVerdict.Blocked("${other.fileName} bundles several mods and duplicates $modId; resolve it manually (keeping $keptText)")
            }

            keptVersion == null || otherVersion == null -> {
                unestablished(modId, other, keptText)
            }

            else -> {
                when (otherVersion.order(keptVersion)) {
                    VersionOrder.OLDER, VersionOrder.EQUAL -> {
                        DuplicateVerdict.Older("Older duplicate of mod $modId: keeping $keptText; this file is ${other.versionText}")
                    }

                    VersionOrder.NEWER -> {
                        DuplicateVerdict.Blocked(
                            "Installed $modId ${other.versionText} is newer than the exact version the server requires ($keptText); " +
                                "ModSync will not downgrade it or install a second copy. Ask the server administrator to publish ${other.versionText} or newer, or remove ${other.fileName} yourself"
                        )
                    }

                    VersionOrder.INCOMPARABLE -> {
                        unestablished(modId, other, keptText)
                    }
                }
            }
        }
    }

    private fun unestablished(modId: String, other: Contender, keptText: String): DuplicateVerdict =
        DuplicateVerdict.Blocked("Cannot establish which version of mod $modId is newer (${other.fileName} is ${other.versionText}, selected is $keptText); resolve it manually")

    private fun decideLocalOnly(modId: String, group: List<Contender>, target: Target): Map<String, DuplicateVerdict> {
        // Files ModSync owns and no longer selects are removed by the regular planner, so they never compete.
        val contenders = group.filterNot { it.owned }.distinctBy { it.fileName.lowercase() }
        if (contenders.size < 2) return emptyMap()
        val compatibility = contenders.associateWith { compatibility(it.info, target) }
        val keep =
            highestCompatible(contenders, compatibility)
                ?: return contenders.associate {
                    it.fileName to
                        DuplicateVerdict.Blocked(
                            "Cannot establish a single highest version of mod $modId that is verifiably compatible with " +
                                "Minecraft ${target.minecraft}; resolve it manually"
                        )
                }
        val keptText = "$modId ${keep.versionText} (${keep.fileName})"
        return (contenders - keep).associate { it.fileName to againstLocalKeep(modId, keep, keptText, it, compatibility.getValue(it), target) }
    }

    private fun highestCompatible(contenders: List<Contender>, compatibility: Map<Contender, Compatibility>): Contender? {
        val eligible = contenders.filter { compatibility[it] == Compatibility.COMPATIBLE && it.version != null }
        return eligible.singleOrNull { candidate ->
            eligible.all { it === candidate || checkNotNull(candidate.version).order(checkNotNull(it.version)) == VersionOrder.NEWER }
        }
    }

    private fun againstLocalKeep(modId: String, keep: Contender, keptText: String, other: Contender, compatibility: Compatibility, target: Target): DuplicateVerdict {
        val otherVersion = other.version
        return when {
            other.modCount > 1 -> {
                DuplicateVerdict.Blocked("${other.fileName} bundles several mods and duplicates $modId; resolve it manually (keeping $keptText)")
            }

            compatibility == Compatibility.INCOMPATIBLE -> {
                DuplicateVerdict.Older("Duplicate of mod $modId that does not support Minecraft ${target.minecraft}: keeping $keptText; this file is ${other.versionText}")
            }

            otherVersion == null -> {
                unestablished(modId, other, keptText)
            }

            else -> {
                when (otherVersion.order(checkNotNull(keep.version))) {
                    VersionOrder.OLDER -> DuplicateVerdict.Older("Older duplicate of mod $modId: keeping $keptText; this file is ${other.versionText}")
                    else -> unestablished(modId, other, keptText)
                }
            }
        }
    }

    private fun compatibility(info: ModInfo, target: Target): Compatibility {
        val minecraft = ModVersion.parse(target.minecraft)
        val minecraftRange = VersionRange.parse(info.minecraftRange)
        val minecraftOk = if (minecraft == null || minecraftRange == null) null else minecraftRange.contains(minecraft)
        val loaderOk = loaderSupported(info, target)
        return when {
            minecraftOk == false || loaderOk == false -> Compatibility.INCOMPATIBLE
            minecraftOk == true && loaderOk == true -> Compatibility.COMPATIBLE
            else -> Compatibility.UNKNOWN
        }
    }

    /** A declared loader range is only evaluated when the instance pins a loader version; otherwise it cannot disqualify a file. */
    private fun loaderSupported(info: ModInfo, target: Target): Boolean? {
        val declared = info.loaderRange
        val loader = target.loaderVersion?.let { ModVersion.parse(it) }
        return when {
            declared == null || target.loaderVersion == null -> true
            else -> VersionRange.parse(declared)?.let { range -> loader?.let(range::contains) }
        }
    }

    /** Game and loader version of the instance. */
    private data class Target(val minecraft: String, val loaderVersion: String?)
}
