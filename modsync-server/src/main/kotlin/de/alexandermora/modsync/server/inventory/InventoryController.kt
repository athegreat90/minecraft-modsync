package de.alexandermora.modsync.server.inventory

import kotlinx.serialization.json.JsonPrimitive
import de.alexandermora.modsync.protocol.OverrideRequest
import de.alexandermora.modsync.protocol.ServerInfo
import de.alexandermora.modsync.protocol.ServerRegistration
import de.alexandermora.modsync.protocol.SnapshotAck
import de.alexandermora.modsync.protocol.SnapshotBatch
import de.alexandermora.modsync.protocol.SnapshotBegin
import de.alexandermora.modsync.protocol.SnapshotView
import de.alexandermora.modsync.server.common.acknowledged
import de.alexandermora.modsync.server.common.actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import de.alexandermora.modsync.server.publication.AutoPublicationService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for server registration, inventory snapshots and artifact classification.
 *
 * Administrator routes live under `/api/v1/admin`, agent upload routes under `/api/v1/agent`.
 *
 * @property inventory Inventory service.
 * @property autoPublication Service notified when a snapshot is committed.
 */
@RestController
@RequestMapping("/api/v1")
class InventoryController(private val inventory: InventoryService, private val autoPublication: AutoPublicationService) {
    /**
     * Lists the servers known to the inventory.
     *
     * Requires the CLIENT or ADMIN role.
     *
     * @return Server summaries sorted by name.
     */
    @GetMapping("/servers") fun servers(): List<ServerInfo> = inventory.listServers()

    /**
     * Registers a server without an agent assignment.
     *
     * Requires the ADMIN role. The protocol answers with a bare JSON string, hence [JsonPrimitive].
     *
     * @param request Server name, Minecraft version, loader and optional loader version.
     * @return The new server's UUID as a JSON string.
     */
    @PostMapping("/admin/servers") fun register(@RequestBody request: ServerRegistration): JsonPrimitive =
        JsonPrimitive(inventory.registerServer(actor(), request))

    /**
     * Returns the server's accepted inventory snapshot.
     *
     * Requires the ADMIN role.
     *
     * @param id Server UUID (path).
     * @return Snapshot header and entries; 409 when no inventory was accepted yet.
     */
    @GetMapping("/admin/servers/{id}/inventory") fun inventory(@PathVariable id: String): SnapshotView = inventory.inventory(id)

    /**
     * Approves or replaces the classification of an artifact on a server.
     *
     * Requires the ADMIN role. Bumps the server policy revision, invalidating earlier publication previews.
     *
     * @param id Server UUID (path).
     * @param request Artifact SHA-512, classification (not UNKNOWN) and mandatory reason.
     * @return Always `true`.
     */
    @PutMapping("/admin/servers/{id}/classification") fun classify(@PathVariable id: String, @RequestBody request: OverrideRequest): Boolean =
        acknowledged { inventory.classify(actor(), id, request) }

    /**
     * Starts or idempotently retries an inventory snapshot upload.
     *
     * Requires the AGENT role. The snapshot stays invisible until committed.
     *
     * @param begin Snapshot header with server, generation, sequence, count and digest.
     * @return The accepted header; 409 when the agent assignment is no longer authoritative.
     */
    @PostMapping("/agent/snapshots") fun begin(@RequestBody begin: SnapshotBegin): SnapshotBegin = inventory.begin(actor(), begin)

    /**
     * Uploads one batch of entries for an uncommitted snapshot.
     *
     * Requires the AGENT role. Identical retries are no-ops.
     *
     * @param id Snapshot (scan) UUID (path).
     * @param batch Batch index and 1-100 entries.
     * @return Always `true`.
     */
    @PutMapping("/agent/snapshots/{id}/batches") fun batch(@PathVariable id: String, @RequestBody batch: SnapshotBatch): Boolean =
        acknowledged { inventory.batch(actor(), id, batch) }

    /**
     * Commits a fully uploaded snapshot as the server's accepted inventory.
     *
     * Requires the AGENT role. Triggers automatic publication handling after the commit; commits are idempotent.
     *
     * @param id Snapshot (scan) UUID (path).
     * @return Acknowledgement with sequence, digest and commit time.
     */
    @PostMapping("/agent/snapshots/{id}/commit") fun commit(@PathVariable id: String): SnapshotAck =
        inventory.commit(actor(), id).also { autoPublication.onSnapshotCommitted(it.scanId) }
}
