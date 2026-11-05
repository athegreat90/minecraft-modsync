package de.alexandermora.modsync.server.clientinventory

import de.alexandermora.modsync.protocol.ClientInventoryAck
import de.alexandermora.modsync.protocol.ClientInventoryReport
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Receives client inventory reports from authenticated devices. */
@RestController
@RequestMapping("/api/v1/client")
class ClientInventoryController(private val inventories: ClientInventoryService) {
    /**
     * Stores the reporting instance's verified mod inventory.
     *
     * @param deviceId Device UUID resolved from the bearer credential.
     * @param report Revisioned inventory report.
     * @return Acknowledgement of the accepted or stale report.
     */
    @PostMapping("/inventory")
    fun report(
        @RequestAttribute("deviceId") deviceId: String,
        @RequestBody report: ClientInventoryReport
    ): ClientInventoryAck =
        inventories.report(deviceId, report)
}
