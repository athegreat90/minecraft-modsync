package de.alexandermora.modsync.server.ui

import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.protocol.OverrideRequest
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.MaintenanceMode
import de.alexandermora.modsync.server.common.actor
import de.alexandermora.modsync.server.common.found
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.publication.AutoPublicationService
import de.alexandermora.modsync.server.publication.PublicationPersistence
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
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
 *
 * @property dashboard Builds the page view models.
 * @property inventory Inventory service used for classification.
 * @property persistence Publication policy storage.
 * @property autoPublication Service re-triggered by administrators.
 * @property audit Audit log writer.
 * @property maintenance Maintenance switch that rejects state changes.
 */
@Controller
class DashboardController(
    private val dashboard: DashboardService,
    private val inventory: InventoryService,
    private val persistence: PublicationPersistence,
    private val autoPublication: AutoPublicationService,
    private val audit: AuditService,
    private val maintenance: MaintenanceMode
) {
    /**
     * Renders the login form.
     *
     * Public page served by the dashboard chain.
     *
     * @return View name.
     */
    @Suppress("FunctionOnlyReturningConstant") // Spring MVC handler: the view name is the response
    @GetMapping("/login")
    fun login(): String = "login"

    /**
     * Renders the server overview.
     *
     * Requires an ADMIN session.
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
     * Requires an ADMIN session.
     *
     * @param id Server UUID (path).
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
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited.
     *
     * @param id Server UUID (path).
     * @param policy Requested policy (form parameter).
     * @return Redirect to the server page; 404 when the server does not exist.
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
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited.
     *
     * @param id Server UUID (path).
     * @param sha512 Exact artifact hash (form parameter).
     * @param classification Approved classification (form parameter).
     * @param reason Reason stored as approval provenance (form parameter).
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
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited. The evaluation runs asynchronously.
     *
     * @param id Server UUID (path).
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
     * @return Unit when changes are allowed.
     * @throws org.springframework.web.server.ResponseStatusException with 409 during maintenance.
     */
    private fun mutable() = maintenance.requireWritable()
}

/** Maps malformed dashboard input to a plain 400 instead of the API's JSON failure body. */
@ControllerAdvice(assignableTypes = [DashboardController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class DashboardErrors {
    /**
     * Rejects invalid form input.
     *
     * Applies only to [DashboardController].
     *
     * @param response Response used to send the 400 status.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(response: HttpServletResponse) {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST)
    }
}
