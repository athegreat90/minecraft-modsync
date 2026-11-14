package de.alexandermora.modsync.server.ui.api

import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.protocol.OverrideRequest
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.MaintenanceMode
import de.alexandermora.modsync.server.common.actor
import de.alexandermora.modsync.server.common.found
import de.alexandermora.modsync.server.common.notFound
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.publication.AutoPublicationService
import de.alexandermora.modsync.server.publication.PublicationPersistence
import de.alexandermora.modsync.server.ui.DashboardService
import de.alexandermora.modsync.server.ui.ServerPage
import de.alexandermora.modsync.server.ui.ServerRow
import de.alexandermora.modsync.server.users.UserCommandResult
import de.alexandermora.modsync.server.users.UserDetail
import de.alexandermora.modsync.server.users.UserFilter
import de.alexandermora.modsync.server.users.UserManagementService
import de.alexandermora.modsync.server.users.UserPage
import de.alexandermora.modsync.server.users.UserProblem
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * JSON administration API consumed by the Angular interface.
 *
 * Every route lives under `/ui/api`, is served by the dashboard security chain (ADMIN session, CSRF token on writes), and
 * delegates to the same services as the previous server-rendered pages. It holds no business rules of its own: it only
 * translates between HTTP and those services, rejects writes during maintenance, and audits changes the same way.
 *
 * @property dashboard Builds the server pages.
 * @property inventory Inventory service used for classification.
 * @property persistence Publication policy storage.
 * @property autoPublication Service re-triggered by administrators.
 * @property audit Audit log writer.
 * @property maintenance Maintenance switch that rejects state changes.
 * @property management User-management service.
 */
@RestController
@RequestMapping("/ui/api")
class UiApiController(
    private val dashboard: DashboardService,
    private val inventory: InventoryService,
    private val persistence: PublicationPersistence,
    private val autoPublication: AutoPublicationService,
    private val audit: AuditService,
    private val maintenance: MaintenanceMode,
    private val management: UserManagementService
) {
    /**
     * Describes the signed-in administrator.
     *
     * @return The username and role of the current session.
     */
    @GetMapping("/session")
    fun session(): SessionView {
        val account = management.detail(actor())?.user ?: notFound()
        return SessionView(account.username, account.role)
    }

    /**
     * Lists all servers with their publication policies.
     *
     * @return Rows ordered by server name.
     */
    @GetMapping("/overview")
    fun overview(): List<ServerRow> = dashboard.overview()

    /**
     * Returns one server with inventory, manifest, coverage and publication history.
     *
     * @param id Server UUID (path).
     * @return The server page; 404 when the server does not exist.
     */
    @GetMapping("/servers/{id}")
    fun server(
        @PathVariable id: String
    ): ServerPage = dashboard.server(id)

    /**
     * Changes the automatic publication policy of a server.
     *
     * @param id Server UUID (path).
     * @param request Requested policy.
     * @return Acknowledgement; 404 when the server does not exist, 409 during maintenance.
     */
    @PutMapping("/servers/{id}/publication-policy")
    fun setPolicy(
        @PathVariable id: String,
        @RequestBody request: PolicyRequest
    ): Ack {
        maintenance.requireWritable()
        val server = uuid(id)
        found(persistence.setPolicy(server, request.policy))
        audit.record(actor(), "publication-policy-set:${request.policy}", server)
        return Ack
    }

    /**
     * Approves the classification of one scanned artifact.
     *
     * @param id Server UUID (path).
     * @param request Artifact hash, classification and reason.
     * @return Acknowledgement; 409 during maintenance.
     */
    @PutMapping("/servers/{id}/classification")
    fun classify(
        @PathVariable id: String,
        @RequestBody request: OverrideRequest
    ): Ack {
        maintenance.requireWritable()
        inventory.classify(actor(), uuid(id), request.copy(reason = request.reason.trim()))
        return Ack
    }

    /**
     * Requests re-evaluation of automatic publication for the latest accepted snapshot.
     *
     * @param id Server UUID (path).
     * @return Acknowledgement; 409 during maintenance.
     */
    @PostMapping("/servers/{id}/reevaluate")
    fun reevaluate(
        @PathVariable id: String
    ): Ack {
        maintenance.requireWritable()
        val server = uuid(id)
        autoPublication.onSnapshotCommitted(inventory.acceptedSnapshot(server))
        audit.record(actor(), "publication-reevaluation-requested", server)
        return Ack
    }

    /**
     * Searches the user list.
     *
     * @param q Username fragment.
     * @param role `ADMIN` or `CLIENT`.
     * @param status `enabled` or `disabled`.
     * @param page Zero-based page index.
     * @return One page of accounts.
     */
    @GetMapping("/users")
    fun users(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int
    ): UserPage = management.list(UserFilter(q, role, status, page))

    /**
     * Returns one account.
     *
     * @param id User UUID (path).
     * @return The account; 404 when it does not exist.
     */
    @GetMapping("/users/{id}")
    fun user(
        @PathVariable id: String
    ): UserDetail = management.detail(id) ?: notFound()

    /**
     * Creates a CLIENT account.
     *
     * @param request Username, password and confirmation.
     * @return 201 with the new id, or the reason for the refusal.
     */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(
        @RequestBody request: CreateUserRequest
    ): CommandDone = completed(management.create(actor(), request.username, request.password, request.confirmation))

    /**
     * Disables an account and ends its sessions.
     *
     * @param id User UUID (path).
     * @return Acknowledgement or the reason for the refusal.
     */
    @PostMapping("/users/{id}/disable")
    fun disable(
        @PathVariable id: String
    ): CommandDone = completed(management.disable(actor(), id))

    /**
     * Enables an account again.
     *
     * @param id User UUID (path).
     * @return Acknowledgement or the reason for the refusal.
     */
    @PostMapping("/users/{id}/enable")
    fun enable(
        @PathVariable id: String
    ): CommandDone = completed(management.enable(actor(), id))

    /**
     * Resets an account's password and ends its credentials and sessions.
     *
     * @param id User UUID (path).
     * @param request New password and confirmation.
     * @return Acknowledgement or the reason for the refusal.
     */
    @PostMapping("/users/{id}/password")
    fun resetPassword(
        @PathVariable id: String,
        @RequestBody request: ResetPasswordRequest
    ): CommandDone = completed(management.resetPassword(actor(), id, request.password, request.confirmation))

    /**
     * Ends an account's API credentials and dashboard sessions while keeping its devices valid.
     *
     * @param id User UUID (path).
     * @return Acknowledgement or the reason for the refusal.
     */
    @PostMapping("/users/{id}/revoke-sessions")
    fun revokeSessions(
        @PathVariable id: String
    ): CommandDone = completed(management.revokeSessions(actor(), id))

    /**
     * Permanently revokes all devices of an account; the explicit confirmation is enforced here, not only in the page.
     *
     * @param id User UUID (path).
     * @param request Must carry `confirm = true`.
     * @return Acknowledgement or the reason for the refusal.
     */
    @PostMapping("/users/{id}/revoke-devices")
    fun revokeDevices(
        @PathVariable id: String,
        @RequestBody request: ConfirmRequest
    ): CommandDone {
        if (!request.confirm) throw UiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "CONFIRMATION_REQUIRED", "Confirm to revoke all devices.")
        return completed(management.revokeDevices(actor(), id))
    }

    /**
     * Unwraps a command result: a refusal becomes a [UiProblem] and is answered by [UiApiProblems].
     *
     * @param result Outcome of the command.
     * @return The acknowledgement of an applied command.
     * @throws UiProblem when the command was refused.
     */
    private fun completed(result: UserCommandResult): CommandDone =
        when (result) {
            is UserCommandResult.Done -> CommandDone(result.userId)
            is UserCommandResult.Failed -> throw UiProblem(statusOf(result.problem), result.problem.name, result.problem.message)
        }

    /**
     * Maps a refusal reason to its HTTP status.
     *
     * @param problem Reason for the refusal.
     * @return 404 for unknown accounts, 409 for a taken username, 403 for protected accounts and 422 otherwise.
     */
    private fun statusOf(problem: UserProblem): HttpStatus =
        when (problem) {
            UserProblem.NOT_FOUND -> HttpStatus.NOT_FOUND
            UserProblem.USERNAME_TAKEN -> HttpStatus.CONFLICT
            UserProblem.NOT_MANAGEABLE -> HttpStatus.FORBIDDEN
            else -> HttpStatus.UNPROCESSABLE_ENTITY
        }
}

/**
 * A refused administration command, carried as an exception so controllers keep a single typed return value.
 *
 * @property status HTTP status of the refusal.
 * @property code Stable machine-readable code.
 * @property text Text safe to show to an administrator; it never echoes submitted values.
 */
class UiProblem(
    val status: HttpStatus,
    val code: String,
    val text: String
) : RuntimeException(text)

/** Answers [UiProblem] refusals raised by [UiApiController] with a [Problem] body. */
@RestControllerAdvice(assignableTypes = [UiApiController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class UiApiProblems {
    /**
     * Builds the response of a refused command.
     *
     * @param problem The refusal.
     * @return A [Problem] body with the refusal's status.
     */
    @ExceptionHandler(UiProblem::class)
    fun refused(problem: UiProblem): ResponseEntity<Problem> = ResponseEntity.status(problem.status).body(Problem(problem.code, problem.text))
}
