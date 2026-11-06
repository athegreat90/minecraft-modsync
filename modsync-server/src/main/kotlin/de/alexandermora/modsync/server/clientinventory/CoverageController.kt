package de.alexandermora.modsync.server.clientinventory

import de.alexandermora.modsync.protocol.ServerCoverage
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Administrator view of server/client mod coverage. */
@RestController
@RequestMapping("/api/v1/admin")
class CoverageController(private val coverage: CoverageService) {
    /**
     * Returns the coverage matrix of one server.
     *
     * @param id Server UUID.
     * @return Per-instance coverage against the latest published manifest.
     */
    @GetMapping("/servers/{id}/coverage")
    fun coverage(
        @PathVariable id: String
    ): ServerCoverage = coverage.forServer(id)
}
