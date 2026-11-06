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

@RestController
@RequestMapping("/api/v1")
class InventoryController(private val inventory: InventoryService, private val autoPublication: AutoPublicationService) {
    @GetMapping("/servers") fun servers(): List<ServerInfo> = inventory.listServers()

    // The protocol answers with a bare JSON string, hence JsonPrimitive instead of a wrapper object.
    @PostMapping("/admin/servers") fun register(@RequestBody request: ServerRegistration): JsonPrimitive =
        JsonPrimitive(inventory.registerServer(actor(), request))

    @GetMapping("/admin/servers/{id}/inventory") fun inventory(@PathVariable id: String): SnapshotView = inventory.inventory(id)

    @PutMapping("/admin/servers/{id}/classification") fun classify(@PathVariable id: String, @RequestBody request: OverrideRequest): Boolean =
        acknowledged { inventory.classify(actor(), id, request) }

    @PostMapping("/agent/snapshots") fun begin(@RequestBody begin: SnapshotBegin): SnapshotBegin = inventory.begin(actor(), begin)

    @PutMapping("/agent/snapshots/{id}/batches") fun batch(@PathVariable id: String, @RequestBody batch: SnapshotBatch): Boolean =
        acknowledged { inventory.batch(actor(), id, batch) }

    @PostMapping("/agent/snapshots/{id}/commit") fun commit(@PathVariable id: String): SnapshotAck =
        inventory.commit(actor(), id).also { autoPublication.onSnapshotCommitted(it.scanId) }
}
