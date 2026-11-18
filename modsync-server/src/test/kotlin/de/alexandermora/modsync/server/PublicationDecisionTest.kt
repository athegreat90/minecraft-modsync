package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.server.publication.Approval
import de.alexandermora.modsync.server.publication.PublicationDecision
import de.alexandermora.modsync.server.publication.decidePublication
import de.alexandermora.modsync.server.publication.effectiveClientRequirements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Verifies the pure publication decision logic. */
class PublicationDecisionTest {
    private val hashA = "a".repeat(128)
    private val hashB = "b".repeat(128)

    /** Builds an identified, unclassified artifact for [project] with [hash]. */
    private fun artifact(project: String, hash: String) =
        Artifact(
            project,
            "v-$hash".take(12),
            "$project.jar",
            hash,
            1,
            "https://cdn.modrinth.com/data/$project/x.jar",
            setOf("1.21.1"),
            setOf("neoforge"),
            Classification.UNKNOWN
        )

    /** Builds a scan entry matching [artifact]. */
    private fun entry(project: String, hash: String) = ModEntry("$project.jar", hash, 1)

    /** Resolver knowing the supplied artifacts. */
    private fun resolver(vararg known: Artifact): (String) -> Artifact? = { hash -> known.find { it.sha512 == hash } }

    /** MANUAL policy always requires review. */
    @Test fun manualAlwaysReviews() {
        val result = decidePublication(PublicationPolicy.MANUAL, listOf(entry("p", hashA)), mapOf(hashA to Classification.CLIENT_REQUIRED), emptyMap(), resolver(artifact("p", hashA)))
        assertIs<PublicationDecision.ReviewRequired>(result)
    }

    /** An exact-hash approval is accepted under REVIEW_UNKNOWN. */
    @Test fun approvedHashIsReady() {
        val result = decidePublication(PublicationPolicy.REVIEW_UNKNOWN, listOf(entry("p", hashA)), mapOf(hashA to Classification.CLIENT_REQUIRED), emptyMap(), resolver(artifact("p", hashA)))
        val ready = assertIs<PublicationDecision.Ready>(result)
        assertEquals(Classification.CLIENT_REQUIRED, ready.artifacts.single().classification)
        assertEquals(emptyList(), ready.approvals)
    }

    /** REVIEW_UNKNOWN does not inherit from trusted projects; TRUSTED_AUTO does and records the approval. */
    @Test fun trustedProjectInheritance() {
        val entries = listOf(entry("p", hashB))
        val trusted = mapOf("p" to Classification.BOTH_REQUIRED)
        assertIs<PublicationDecision.ReviewRequired>(decidePublication(PublicationPolicy.REVIEW_UNKNOWN, entries, emptyMap(), trusted, resolver(artifact("p", hashB))))
        val ready = assertIs<PublicationDecision.Ready>(decidePublication(PublicationPolicy.TRUSTED_AUTO, entries, emptyMap(), trusted, resolver(artifact("p", hashB))))
        assertEquals(listOf(Approval(hashB, Classification.BOTH_REQUIRED, "Automatically inherited from trusted project p")), ready.approvals)
    }

    /** Unknown projects and unidentifiable artifacts require review, naming the files. */
    @Test fun unknownRequiresReview() {
        val result = decidePublication(PublicationPolicy.TRUSTED_AUTO, listOf(entry("new", hashA), entry("ghost", hashB)), emptyMap(), mapOf("p" to Classification.CLIENT_REQUIRED), resolver(artifact("new", hashA)))
        assertEquals(listOf("new.jar", "ghost.jar"), assertIs<PublicationDecision.ReviewRequired>(result).files)
    }

    /** A published manual artifact (no URL) with the same hash is carried over under TRUSTED_AUTO and recorded as an approval. */
    @Test fun unidentifiedPublishedManualArtifactIsCarriedOver() {
        val manual = artifact("manual-local", hashA).copy(url = null, classification = Classification.BOTH_REQUIRED)
        val ready = assertIs<PublicationDecision.Ready>(decidePublication(PublicationPolicy.TRUSTED_AUTO, listOf(entry("local", hashA)), emptyMap(), emptyMap(), resolver(), mapOf(hashA to manual)))
        assertEquals("manual-local", ready.artifacts.single().projectId)
        assertEquals(null, ready.artifacts.single().url)
        assertEquals(Classification.BOTH_REQUIRED, ready.artifacts.single().classification)
        assertEquals(listOf(Approval(hashA, Classification.BOTH_REQUIRED, "Automatically carried over from the latest published manifest")), ready.approvals)
    }

    /** Under REVIEW_UNKNOWN a carried artifact still needs an approval of its exact hash, and then it needs no new one. */
    @Test fun carriedArtifactNeedsApprovalUnderReviewUnknown() {
        val manual = artifact("manual-local", hashA).copy(url = null, classification = Classification.BOTH_REQUIRED)
        val entries = listOf(entry("local", hashA))
        val blocked = assertIs<PublicationDecision.ReviewRequired>(decidePublication(PublicationPolicy.REVIEW_UNKNOWN, entries, emptyMap(), emptyMap(), resolver(), mapOf(hashA to manual)))
        assertEquals(listOf("local.jar"), blocked.files)
        val ready = assertIs<PublicationDecision.Ready>(decidePublication(PublicationPolicy.REVIEW_UNKNOWN, entries, mapOf(hashA to Classification.BOTH_REQUIRED), emptyMap(), resolver(), mapOf(hashA to manual)))
        assertEquals(emptyList(), ready.approvals)
    }

    /** A new unidentifiable file, or a published one classified UNKNOWN, still requires review and says why. */
    @Test fun unidentifiedWithoutTrustedPublicationRequiresReview() {
        val unknown = artifact("manual-local", hashA).copy(url = null, classification = Classification.UNKNOWN)
        val result = decidePublication(PublicationPolicy.TRUSTED_AUTO, listOf(entry("local", hashA), entry("fresh", hashB)), emptyMap(), emptyMap(), resolver(), mapOf(hashA to unknown))
        val review = assertIs<PublicationDecision.ReviewRequired>(result)
        assertEquals(listOf("local.jar", "fresh.jar"), review.files)
        assertEquals(setOf("local.jar", "fresh.jar"), review.reasons.keys)
    }

    /** An UNKNOWN trusted or approved classification is never inherited. */
    @Test fun unknownClassificationIsNotTrusted() {
        val result = decidePublication(PublicationPolicy.TRUSTED_AUTO, listOf(entry("p", hashA)), mapOf(hashA to Classification.UNKNOWN), mapOf("p" to Classification.UNKNOWN), resolver(artifact("p", hashA)))
        assertIs<PublicationDecision.ReviewRequired>(result)
    }

    /** Server-only changes and timestamps do not alter effective client requirements. */
    @Test fun effectiveRequirementsIgnoreServerOnly() {
        fun manifest(vararg artifacts: Artifact, createdAt: String = "2026-01-01T00:00:00Z") =
            Manifest("s", 1, "1.21.1", "neoforge", null, artifacts.toList(), createdAt, "d")
        val client = artifact("c", hashA).copy(classification = Classification.CLIENT_REQUIRED)
        val serverOnly = artifact("s", hashB).copy(classification = Classification.SERVER_ONLY)
        assertEquals(effectiveClientRequirements(manifest(client)), effectiveClientRequirements(manifest(client, serverOnly, createdAt = "2027-01-01T00:00:00Z")))
        assertNotEquals(effectiveClientRequirements(manifest(client)), effectiveClientRequirements(manifest(client.copy(sha512 = hashB))))
    }
}
