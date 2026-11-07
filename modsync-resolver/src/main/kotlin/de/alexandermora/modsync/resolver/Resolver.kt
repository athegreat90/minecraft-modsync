package de.alexandermora.modsync.resolver

import de.alexandermora.modsync.domain.*

/** Pure planner that merges a server manifest with personal artifacts and the local mods folder. */
class Resolver {
    /**
     * Builds a deterministic sync plan.
     *
     * Uses the manifest's client-required artifacts (plus optional ones when requested) and the personal
     * artifacts. Only entries whose hash matches the managed ownership record may be replaced or removed.
     * Conflicts (versions, dependencies, filenames, compatibility, unmanaged overwrites) are reported as
     * operations, never resolved silently.
     *
     * @param manifest Published manifest to satisfy.
     * @param personal Client-only artifacts from personal profiles.
     * @param local Current contents of the mods folder.
     * @param managed Map from file name to the hash this tool installed.
     * @param includeOptional Whether client-optional manifest artifacts are selected.
     * @return The plan with its operations and the digest of [local].
     */
    fun plan(manifest: Manifest, personal: List<Artifact>, local: List<ModEntry>, managed: Map<String, String>, includeOptional: Boolean = false): SyncPlan {
        val operations = mutableListOf<PlanOperation>()
        val server = manifest.artifacts.filter { it.classification in setOf(Classification.CLIENT_REQUIRED, Classification.BOTH_REQUIRED) || (includeOptional && it.classification == Classification.CLIENT_OPTIONAL) }
        val desired = (server + personal).groupBy { it.projectId }
        val selected = mutableListOf<Artifact>()
        desired.toSortedMap().forEach { (_, versions) ->
            if (versions.map { it.versionId to it.sha512 }.distinct().size != 1) {
                operations += PlanOperation(OperationKind.CONFLICT, versions.first().fileName, "Conflicting exact versions for ${versions.first().projectId}")
            } else {
                selected += versions.first()
            }
        }
        personal.filter { it.classification != Classification.CLIENT_ONLY }.forEach { operations += PlanOperation(OperationKind.CONFLICT, it.fileName, "Personal artifacts require approved client-only classification") }
        manifest.artifacts.filter { it.classification == Classification.UNKNOWN }.forEach { operations += PlanOperation(OperationKind.MANUAL_ACTION_REQUIRED, it.fileName, "Unknown classification") }
        selected
            .groupBy { it.fileName.lowercase() }
            .values
            .filter { it.size > 1 }
            .forEach { operations += PlanOperation(OperationKind.CONFLICT, it.first().fileName, "Multiple artifacts target the same filename") }
        val byProject = selected.associateBy { it.projectId }
        val embedded = selected.flatMap { parent -> parent.dependencies.filter { it.kind == DependencyKind.EMBEDDED } }.groupBy { it.projectId }
        embedded.forEach { (project, constraints) ->
            val pins = constraints.mapNotNull { it.versionId }.distinct()
            if (pins.size > 1 || (project in byProject && pins.any { it != byProject.getValue(project).versionId })) {
                operations += PlanOperation(OperationKind.CONFLICT, project, "Conflicting embedded dependency versions")
            }
        }
        selected.forEach { artifact ->
            if (!safeFileName(artifact.fileName) || !validHash(artifact.sha512) || artifact.size < 0) operations += PlanOperation(OperationKind.CONFLICT, artifact.fileName, "Invalid artifact metadata")
            if (manifest.minecraftVersion !in artifact.minecraftVersions || manifest.loader !in artifact.loaders) operations += PlanOperation(OperationKind.CONFLICT, artifact.fileName, "Minecraft or loader incompatibility")
            artifact.dependencies.forEach { dependency ->
                val found = byProject[dependency.projectId]
                val matches = (found != null && (dependency.versionId == null || dependency.versionId == found.versionId)) || embedded[dependency.projectId].orEmpty().any { dependency.versionId == null || dependency.versionId == it.versionId }
                if (dependency.kind == DependencyKind.REQUIRED && !matches) operations += PlanOperation(OperationKind.CONFLICT, artifact.fileName, "Missing required dependency ${dependency.projectId}; resolve before download")
                if (dependency.kind == DependencyKind.INCOMPATIBLE && matches) operations += PlanOperation(OperationKind.CONFLICT, artifact.fileName, "Incompatible dependency ${dependency.projectId}")
            }
            val existing = local.find { it.fileName.equals(artifact.fileName, true) }
            when {
                existing?.sha512 == artifact.sha512 -> operations += PlanOperation(OperationKind.KEEP, artifact.fileName, "Exact verified artifact present", artifact)
                existing != null && (managed[existing.fileName] != existing.sha512 || existing.fileName != artifact.fileName) -> operations += PlanOperation(OperationKind.CONFLICT, artifact.fileName, "Unmanaged, modified, or case-colliding file would be overwritten")
                artifact.url == null -> operations += PlanOperation(OperationKind.MANUAL_ACTION_REQUIRED, artifact.fileName, "Supply exact authorized artifact locally", artifact)
                else -> operations += PlanOperation(if (existing == null) OperationKind.INSTALL else OperationKind.UPDATE, artifact.fileName, "Required by selected manifest or personal profile", artifact)
            }
        }
        local.filter { entry -> selected.none { it.fileName == entry.fileName } }.forEach { entry ->
            operations +=
                if (managed[entry.fileName] == entry.sha512) {
                    PlanOperation(OperationKind.REMOVE_MANAGED, entry.fileName, "Previously managed artifact no longer selected")
                } else {
                    PlanOperation(OperationKind.SKIP, entry.fileName, "Preserve unmanaged or locally modified mod")
                }
        }
        return SyncPlan(manifest, operations, inventoryDigest(local), includeOptional)
    }
}
