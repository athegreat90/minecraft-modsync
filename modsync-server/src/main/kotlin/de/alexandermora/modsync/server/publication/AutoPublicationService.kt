package de.alexandermora.modsync.server.publication

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.protocol.OverrideRequest
import de.alexandermora.modsync.protocol.PublishRequest
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.manifest.ManifestService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val SYSTEM_ACTOR = "system:auto-publish"

/** Identifies artifacts by hash without trusting file names. */
fun interface ArtifactResolver {
    /**
     * Identifies an artifact.
     *
     * @param sha512 Exact artifact hash.
     * @return The identified artifact (classification UNKNOWN), or null when it cannot be identified.
     */
    fun resolve(sha512: String): Artifact?
}

/** [ArtifactResolver] backed by Modrinth hash identification. */
@Component
class ModrinthArtifactResolver(private val modrinth: Modrinth) : ArtifactResolver {
    /**
     * Identifies an artifact through Modrinth.
     *
     * @param sha512 Exact artifact hash.
     * @return Identified artifact, or null when Modrinth does not know the hash or is unavailable.
     */
    override fun resolve(sha512: String): Artifact? =
        runCatching { modrinth.artifact(sha512, Classification.UNKNOWN, emptyList()) }.getOrNull()
}

/** Manifest operations needed by automatic publication. */
interface ManifestGateway {
    /**
     * Loads the latest published manifest.
     *
     * @param serverId Server UUID.
     * @return The manifest, or null when none was published.
     */
    fun latest(serverId: String): Manifest?

    /**
     * Validates a candidate manifest without storing it.
     *
     * @param serverId Server UUID.
     * @param request Publish request.
     * @return The candidate manifest.
     */
    fun preview(serverId: String, request: PublishRequest): Manifest

    /**
     * Publishes a validated candidate.
     *
     * @param actor Audit actor.
     * @param manifest Candidate from [preview].
     * @param scanId Snapshot the candidate was built from.
     * @return The published manifest.
     */
    fun publish(actor: String, manifest: Manifest, scanId: String): Manifest
}

/** [ManifestGateway] delegating to [ManifestService]. */
@Component
class ManifestServiceGateway(private val manifests: ManifestService) : ManifestGateway {
    /**
     * Loads the latest manifest, mapping "never published" to null.
     *
     * @param serverId Server UUID.
     * @return Latest manifest or null.
     */
    override fun latest(serverId: String): Manifest? =
        try {
            manifests.manifest(serverId, null)
        } catch (e: ResponseStatusException) {
            if (e.statusCode.value() == 404) null else throw e
        }

    /**
     * Previews a candidate manifest.
     *
     * @param serverId Server UUID.
     * @param request Publish request.
     * @return Candidate manifest.
     */
    override fun preview(serverId: String, request: PublishRequest): Manifest = manifests.preview(serverId, request)

    /**
     * Publishes a candidate manifest.
     *
     * @param actor Audit actor.
     * @param manifest Candidate.
     * @param scanId Source snapshot.
     * @return Published manifest.
     */
    override fun publish(actor: String, manifest: Manifest, scanId: String): Manifest = manifests.publish(actor, manifest, scanId)
}

/**
 * Publishes client manifests automatically after a snapshot commit when the server's policy allows it.
 *
 * Publications run on one background thread so that they never block the agent and never race each other.
 * Failures are recorded in the publication history and never propagate to the agent.
 */
@Service
class AutoPublicationService(
    private val inventory: InventoryService,
    private val manifests: ManifestGateway,
    private val resolver: ArtifactResolver,
    private val persistence: PublicationPersistence
) {
    private val logger = LoggerFactory.getLogger(AutoPublicationService::class.java)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "auto-publication").apply { isDaemon = true } }

    /**
     * Schedules evaluation of a committed snapshot without blocking the caller.
     *
     * @param scanId Committed snapshot id.
     */
    fun onSnapshotCommitted(scanId: String) {
        executor.execute {
            runCatching { evaluate(scanId) }
                .onFailure { logger.warn("automatic publication failed unexpectedly", it) }
        }
    }

    /** Stops the background worker on shutdown. */
    @PreDestroy fun shutdown() {
        executor.shutdown()
    }

    /**
     * Evaluates a committed snapshot synchronously and records the result.
     *
     * Order: policy gate, classification decision, approval recording, preview with full dependency and
     * compatibility validation, no-change check against the latest manifest, publication.
     *
     * @param scanId Committed snapshot id.
     * @return The recorded outcome name, or null when the policy is MANUAL and nothing was recorded.
     */
    fun evaluate(scanId: String): String? {
        val serverId = inventory.committedSnapshot(scanId).serverId
        val policy = persistence.policy(serverId) ?: return null
        if (policy == PublicationPolicy.MANUAL) return null

        fun outcome(name: String, detail: String, revision: Long? = null): String {
            persistence.record(serverId, scanId, policy, name, revision, detail, SYSTEM_ACTOR)
            return name
        }
        val latest = manifests.latest(serverId)
        val approved = inventory.overridesFor(serverId).mapValues { Classification.valueOf(it.value.classification) }
        val trusted =
            latest
                ?.artifacts
                .orEmpty()
                .filter { it.classification != Classification.UNKNOWN }
                .associate { it.projectId to it.classification }
        val decision = decidePublication(policy, inventory.entries(scanId), approved, trusted, resolver::resolve)
        if (decision is PublicationDecision.ReviewRequired) return outcome(REVIEW_REQUIRED, "Needs review: " + decision.files.joinToString(", "))
        decision as PublicationDecision.Ready
        return try {
            decision.approvals.forEach { inventory.classify(SYSTEM_ACTOR, serverId, OverrideRequest(it.sha512, it.classification, it.reason)) }
            val candidate = manifests.preview(serverId, PublishRequest(scanId, decision.artifacts))
            if (latest != null && effectiveClientRequirements(candidate) == effectiveClientRequirements(latest)) {
                outcome(SKIPPED_NO_CHANGE, "Effective client requirements unchanged at revision ${latest.revision}", latest.revision)
            } else {
                val published = manifests.publish(SYSTEM_ACTOR, candidate, scanId)
                outcome(PUBLISHED, "Published automatically", published.revision)
            }
        } catch (error: ResponseStatusException) {
            outcome(if (error.statusCode.is4xxClientError) REVIEW_REQUIRED else FAILED, reasonOf(error.statusCode, error.reason))
        } catch (error: IllegalArgumentException) {
            outcome(REVIEW_REQUIRED, "Invalid manifest candidate: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Formats an HTTP-status failure for the history.
     *
     * @param status Response status.
     * @param reason Failure reason, if any.
     * @return Short description.
     */
    private fun reasonOf(status: HttpStatusCode, reason: String?) = "HTTP ${status.value()}: ${reason ?: "no detail"}"

    /** Outcome names recorded in the publication history. */
    companion object {
        const val PUBLISHED = "PUBLISHED"
        const val SKIPPED_NO_CHANGE = "SKIPPED_NO_CHANGE"
        const val REVIEW_REQUIRED = "REVIEW_REQUIRED"
        const val FAILED = "FAILED"
    }
}
