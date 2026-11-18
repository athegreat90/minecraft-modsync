package de.alexandermora.modsync.server.publication

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Evidence
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.PublicationPolicy

/**
 * Approval the automatic publisher records for an artifact it classified itself.
 *
 * @property sha512 Exact artifact hash.
 * @property classification Inherited classification.
 * @property reason Human-readable provenance stored with the approval.
 */
data class Approval(val sha512: String, val classification: Classification, val reason: String)

/** Outcome of evaluating a committed snapshot against a publication policy. */
sealed interface PublicationDecision {
    /**
     * Every scanned artifact is classified and identified; a manifest may be previewed.
     *
     * @property artifacts Publish-request artifacts in scan order.
     * @property approvals New approvals to record before previewing.
     */
    data class Ready(val artifacts: List<Artifact>, val approvals: List<Approval>) : PublicationDecision

    /**
     * Some artifacts cannot be classified trustworthily; an administrator must review them.
     *
     * @property files File names needing review.
     * @property reasons Why each file needs review, keyed by file name; empty when the policy is MANUAL.
     */
    data class ReviewRequired(val files: List<String>, val reasons: Map<String, String> = emptyMap()) : PublicationDecision
}

private const val NOT_IDENTIFIED = "Modrinth cannot identify this file and the latest manifest does not list its exact hash"
private const val NO_CLASSIFICATION = "No approved classification, and no trusted project to inherit one from"

private val CLIENT_RELEVANT = setOf(Classification.CLIENT_REQUIRED, Classification.BOTH_REQUIRED, Classification.CLIENT_OPTIONAL)

/**
 * Evaluates a snapshot under an automatic publication policy.
 *
 * - [PublicationPolicy.REVIEW_UNKNOWN]: only artifacts whose exact hash already has an approved
 *   classification are accepted; any other artifact requires review.
 * - [PublicationPolicy.TRUSTED_AUTO]: additionally, a new version of a Modrinth project that an earlier
 *   published manifest already classified inherits that classification, recorded as an approval.
 * - [PublicationPolicy.MANUAL]: never automatic; always requires review.
 *
 * An artifact that Modrinth cannot identify (a private or local build) is accepted only when the latest published
 * manifest already lists that exact hash as a manual artifact (no download URL) with a classification other than
 * UNKNOWN; the same bytes an administrator already approved are carried over unchanged. Under [PublicationPolicy.REVIEW_UNKNOWN]
 * that also requires an approval of the hash. A new unidentifiable file, or one whose classification is UNKNOWN, always requires review.
 *
 * @param policy Server publication policy.
 * @param entries Scanned artifacts.
 * @param approved Existing approvals by exact hash.
 * @param trustedProjects Classifications of projects present in the latest published manifest.
 * @param resolve Identifies an artifact by hash, or returns null when it cannot be identified.
 * @param published Manual artifacts (no download URL) of the latest published manifest by exact hash.
 * @return [PublicationDecision.Ready] or [PublicationDecision.ReviewRequired].
 */
fun decidePublication(
    policy: PublicationPolicy,
    entries: List<ModEntry>,
    approved: Map<String, Classification>,
    trustedProjects: Map<String, Classification>,
    resolve: (String) -> Artifact?,
    published: Map<String, Artifact> = emptyMap()
): PublicationDecision {
    if (policy == PublicationPolicy.MANUAL) return PublicationDecision.ReviewRequired(entries.map { it.fileName })
    val artifacts = mutableListOf<Artifact>()
    val approvals = mutableListOf<Approval>()
    val review = linkedMapOf<String, String>()
    entries.forEach { entry ->
        val identified = resolve(entry.sha512)
        val carried = if (identified == null) published[entry.sha512]?.takeIf { it.classification != Classification.UNKNOWN } else null
        val existing = approved[entry.sha512]?.takeIf { it != Classification.UNKNOWN }
        val inherited = identified?.let { trustedProjects[it.projectId] }?.takeIf { it != Classification.UNKNOWN && policy == PublicationPolicy.TRUSTED_AUTO }
        val classification = existing ?: inherited ?: carried?.classification?.takeIf { policy == PublicationPolicy.TRUSTED_AUTO }
        val base = identified ?: carried
        if (base == null || classification == null) {
            review[entry.fileName] = if (base == null) NOT_IDENTIFIED else NO_CLASSIFICATION
            return@forEach
        }
        if (existing == null) {
            val reason = if (inherited != null) "Automatically inherited from trusted project ${base.projectId}" else "Automatically carried over from the latest published manifest"
            approvals += Approval(entry.sha512, classification, reason)
        }
        artifacts += base.copy(classification = classification, evidence = listOf(Evidence("auto-publish", "Policy $policy")))
    }
    return if (review.isEmpty()) PublicationDecision.Ready(artifacts, approvals) else PublicationDecision.ReviewRequired(review.keys.toList(), review)
}

/**
 * Builds the key describing what clients effectively need from a manifest.
 *
 * Server-only artifacts and metadata such as timestamps are excluded, so republishing identical
 * client requirements does not create a new revision.
 *
 * @param manifest Manifest to summarize.
 * @return A deterministic set of requirement descriptors.
 */
fun effectiveClientRequirements(manifest: Manifest): Set<String> =
    manifest.artifacts
        .filter { it.classification in CLIENT_RELEVANT }
        .map { "${it.projectId}:${it.versionId}:${it.sha512}:${it.classification}" }
        .toSet() +
        "platform:${manifest.minecraftVersion}:${manifest.loader}:${manifest.loaderVersion}"
