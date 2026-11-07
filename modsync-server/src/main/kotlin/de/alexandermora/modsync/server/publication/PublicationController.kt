package de.alexandermora.modsync.server.publication

import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.protocol.PublicationPolicyRequest
import de.alexandermora.modsync.protocol.PublicationRecord
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.acknowledged
import de.alexandermora.modsync.server.common.actor
import de.alexandermora.modsync.server.common.found
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private const val HISTORY_LIMIT = 100

/**
 * Administrator endpoints for publication policy and history.
 *
 * Requires the ADMIN role.
 *
 * @property persistence Policy and history storage.
 * @property audit Audit log writer.
 */
@RestController
@RequestMapping("/api/v1/admin")
class PublicationController(private val persistence: PublicationPersistence, private val audit: AuditService) {
    /**
     * Sets the publication policy of a server.
     *
     * Requires the ADMIN role; the change is audited.
     *
     * @param id Server UUID (path).
     * @param request Requested policy.
     * @return True when applied; 404 when the server does not exist.
     */
    @PutMapping("/servers/{id}/publication-policy")
    fun setPolicy(
        @PathVariable id: String,
        @RequestBody request: PublicationPolicyRequest
    ): Boolean =
        acknowledged {
            val server = uuid(id)
            found(persistence.setPolicy(server, request.policy))
            audit.record(actor(), "publication-policy-set:${request.policy}", server)
        }

    /**
     * Lists recent publication attempts of a server.
     *
     * Requires the ADMIN role.
     *
     * @param id Server UUID (path).
     * @return Newest-first history.
     */
    @GetMapping("/servers/{id}/publications")
    fun history(
        @PathVariable id: String
    ): List<PublicationRecord> =
        persistence.history(uuid(id), HISTORY_LIMIT)
}
