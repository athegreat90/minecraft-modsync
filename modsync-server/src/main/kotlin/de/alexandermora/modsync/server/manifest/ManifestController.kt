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

@RestController
@RequestMapping("/api/v1")
class ManifestController(private val manifests: ManifestService) {
    @GetMapping("/servers/{id}/manifest") fun manifest(@PathVariable id: String, @RequestParam(required = false) revision: Long?): Manifest =
        manifests.manifest(id, revision)

    @PostMapping("/admin/servers/{id}/preview") fun preview(@PathVariable id: String, @RequestBody request: PublishRequest): Manifest =
        manifests.preview(id, request)

    @PostMapping("/admin/servers/{id}/publish") fun publish(@PathVariable id: String, @RequestBody request: PublishRequest): Manifest =
        manifests.publish(actor(), manifests.preview(id, request), request.scanId)

    @PostMapping("/sync/reports") fun report(@RequestAttribute("deviceId") deviceId: String, @RequestBody report: SyncReport): Boolean =
        acknowledged { manifests.report(deviceId, report) }
}
