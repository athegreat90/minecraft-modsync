package de.alexandermora.modsync.server.manifest

import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.protocol.PublishRequest
import de.alexandermora.modsync.protocol.SyncReport
import de.alexandermora.modsync.server.common.acknowledged
import de.alexandermora.modsync.server.common.actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for manifest retrieval, preview, publication and synchronization reports.
 *
 * @property manifests Manifest service.
 */
@RestController
@RequestMapping("/api/v1")
class ManifestController(private val manifests: ManifestService) {
    /**
     * Returns a published manifest of a server.
     *
     * Requires the CLIENT or ADMIN role.
     *
     * @param id Server UUID (path).
     * @param revision Revision number (query); the latest one when omitted.
     * @return The manifest; 404 when the server or revision does not exist.
     */
    @GetMapping("/servers/{id}/manifest")
    fun manifest(
        @PathVariable id: String,
        @RequestParam(required = false) revision: Long?
    ): Manifest =
        manifests.manifest(id, revision)

    /**
     * Builds and validates the next manifest without storing it.
     *
     * Requires the ADMIN role.
     *
     * @param id Server UUID (path).
     * @param request Scan id and artifact selection to publish.
     * @return The candidate manifest; 409 when approvals, pins or dependencies are invalid.
     */
    @PostMapping("/admin/servers/{id}/preview")
    fun preview(
        @PathVariable id: String,
        @RequestBody request: PublishRequest
    ): Manifest =
        manifests.preview(id, request)

    /**
     * Publishes the next immutable manifest revision.
     *
     * Requires the ADMIN role. The preview is recomputed and rejected when the policy, snapshot or revision changed.
     *
     * @param id Server UUID (path).
     * @param request Scan id and artifact selection to publish.
     * @return The published manifest.
     */
    @PostMapping("/admin/servers/{id}/publish")
    fun publish(
        @PathVariable id: String,
        @RequestBody request: PublishRequest
    ): Manifest =
        manifests.publish(actor(), manifests.preview(id, request), request.scanId)

    /**
     * Records the outcome of applying a manifest revision on a client.
     *
     * Requires the CLIENT or ADMIN role.
     *
     * @param deviceId Device UUID resolved from the bearer credential.
     * @param report Server, revision and status (`SUCCESS`, `FAILED` or `ROLLED_BACK`).
     * @return Always `true`; 404 for an unknown revision.
     */
    @PostMapping("/sync/reports")
    fun report(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody report: SyncReport
    ): Boolean =
        acknowledged { manifests.report(deviceId, report) }
}
