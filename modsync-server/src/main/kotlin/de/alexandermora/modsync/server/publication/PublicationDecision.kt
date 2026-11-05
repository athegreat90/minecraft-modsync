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
     */
    data class ReviewRequired(val files: List<String>) : PublicationDecision
}

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
 * Artifacts that Modrinth cannot identify, or whose classification is UNKNOWN, always require review.
 *
 * @param policy Server publication policy.
 * @param entries Scanned artifacts.
 * @param approved Existing approvals by exact hash.
 * @param trustedProjects Classifications of projects present in the latest published manifest.
 * @param resolve Identifies an artifact by hash, or returns null when it cannot be identified.
 * @return [PublicationDecision.Ready] or [PublicationDecision.ReviewRequired].
 */
fun decidePublication(
    policy: PublicationPolicy,
    entries: List<ModEntry>,
    approved: Map<String, Classification>,
    trustedProjects: Map<String, Classification>,
    resolve: (String) -> Artifact?
): PublicationDecision {
    if (policy == PublicationPolicy.MANUAL) return PublicationDecision.ReviewRequired(entries.map { it.fileName })
    val artifacts = mutableListOf<Artifact>()
    val approvals = mutableListOf<Approval>()
    val review = mutableListOf<String>()
    entries.forEach { entry ->
        val identified = resolve(entry.sha512)
        val existing = approved[entry.sha512]?.takeIf { it != Classification.UNKNOWN }
        val inherited = identified?.let { trustedProjects[it.projectId] }?.takeIf { it != Classification.UNKNOWN && policy == PublicationPolicy.TRUSTED_AUTO }
        val classification = existing ?: inherited
        if (identified == null || classification == null) {
            review += entry.fileName
            return@forEach
        }
        if (existing == null) approvals += Approval(entry.sha512, classification, "Automatically inherited from trusted project ${identified.projectId}")
        artifacts += identified.copy(classification = classification, evidence = listOf(Evidence("auto-publish", "Policy $policy")))
    }
    return if (review.isEmpty()) PublicationDecision.Ready(artifacts, approvals) else PublicationDecision.ReviewRequired(review)
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
