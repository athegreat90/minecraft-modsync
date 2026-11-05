package de.alexandermora.modsync.server.ui

import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.protocol.OverrideRequest
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.actor
import de.alexandermora.modsync.server.common.found
import de.alexandermora.modsync.server.common.requireState
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.publication.AutoPublicationService
import de.alexandermora.modsync.server.publication.PublicationPersistence
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Server-rendered administration dashboard.
 *
 * Every page is read from persisted data. State-changing posts require an administrator session and a
 * valid CSRF token, are rejected during maintenance, and are audited. The dashboard offers no command
 * execution of any kind.
 */
@Controller
class DashboardController(
    private val dashboard: DashboardService,
    private val inventory: InventoryService,
    private val persistence: PublicationPersistence,
    private val autoPublication: AutoPublicationService,
    private val audit: AuditService,
    @Value($$"${modsync.maintenance}") private val maintenance: Boolean
) {
    /**
     * Renders the login form.
     *
     * @return View name.
     */
    @Suppress("FunctionOnlyReturningConstant") // Spring MVC handler: the view name is the response
    @GetMapping("/login")
    fun login(): String = "login"

    /**
     * Renders the server overview.
     *
     * @param model View model.
     * @return View name.
     */
    @GetMapping("/ui")
    fun overview(model: Model): String {
        model.addAttribute("servers", dashboard.overview())
        return "overview"
    }

    /**
     * Renders one server with inventory, manifest, coverage matrix and publication history.
     *
     * @param id Server UUID.
     * @param model View model.
     * @return View name.
     */
    @GetMapping("/ui/servers/{id}")
    fun server(
        @PathVariable id: String,
        model: Model
    ): String {
        model.addAttribute("page", dashboard.server(id))
        model.addAttribute("policies", PublicationPolicy.entries)
        model.addAttribute("classifications", Classification.entries.filter { it != Classification.UNKNOWN })
        return "server"
    }

    /**
     * Changes the automatic publication policy of a server.
     *
     * @param id Server UUID.
     * @param policy Requested policy.
     * @return Redirect to the server page.
     */
    @PostMapping("/ui/servers/{id}/publication-policy")
    fun setPolicy(
        @PathVariable id: String,
        @RequestParam policy: PublicationPolicy
    ): String {
        mutable()
        val server = uuid(id)
        found(persistence.setPolicy(server, policy))
        audit.record(actor(), "publication-policy-set:$policy", server)
        return "redirect:/ui/servers/$server"
    }

    /**
     * Approves the classification of one scanned artifact.
     *
     * @param id Server UUID.
     * @param sha512 Exact artifact hash.
     * @param classification Approved classification.
     * @param reason Reason stored as approval provenance.
     * @return Redirect to the server page.
     */
    @PostMapping("/ui/servers/{id}/classify")
    fun classify(
        @PathVariable id: String,
        @RequestParam sha512: String,
        @RequestParam classification: Classification,
        @RequestParam reason: String
    ): String {
        mutable()
        val server = uuid(id)
        inventory.classify(actor(), server, OverrideRequest(sha512, classification, reason.trim()))
        return "redirect:/ui/servers/$server"
    }

    /**
     * Requests re-evaluation of automatic publication for the latest accepted snapshot.
     *
     * @param id Server UUID.
     * @return Redirect to the server page.
     */
    @PostMapping("/ui/servers/{id}/reevaluate")
    fun reevaluate(
        @PathVariable id: String
    ): String {
        mutable()
        val server = uuid(id)
        autoPublication.onSnapshotCommitted(inventory.acceptedSnapshot(server))
        audit.record(actor(), "publication-reevaluation-requested", server)
        return "redirect:/ui/servers/$server"
    }

    /**
     * Rejects state changes while the system is in maintenance mode.
     *
     * @throws org.springframework.web.server.ResponseStatusException with 409 during maintenance.
     */
    private fun mutable() = requireState(!maintenance, "Maintenance mode is active")
}
