package de.alexandermora.modsync.server.users

import de.alexandermora.modsync.server.common.actor
import de.alexandermora.modsync.server.common.notFound
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.util.UriComponentsBuilder

private const val USERS_PATH = "/ui/users"
private const val NOTICE = "notice"
private const val ERROR = "error"
private const val CONFIRMED = "yes"

/**
 * Server-rendered user administration pages of the dashboard.
 *
 * Every route lives under `/ui/users` and therefore requires an ADMIN session; the dashboard chain denies everyone
 * else. Posts also require a CSRF token, are rejected during maintenance and are audited by the service. Handlers
 * bind explicit request parameters only and never expose a password hash or credential. Failed commands are
 * reported as flash messages (Post/Redirect/Get), except for the create form, which is shown again with its error.
 *
 * @property management Business rules for listing, creating and changing accounts.
 */
@Controller
class UserManagementController(
    private val management: UserManagementService
) {
    /**
     * Renders the paged, filterable user list.
     *
     * Requires an ADMIN session.
     *
     * @param q Username fragment to search for; blank for none.
     * @param role `ADMIN` or `CLIENT`, or blank for any role.
     * @param status `enabled` or `disabled`, or blank for any status.
     * @param page Zero-based page index.
     * @param model View model with the page of accounts and the normalized filter.
     * @return View name.
     */
    @GetMapping(USERS_PATH)
    fun list(
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "") role: String,
        @RequestParam(defaultValue = "") status: String,
        @RequestParam(defaultValue = "0") page: Int,
        model: Model
    ): String {
        val filter = UserFilter(q.trim(), role, status, page)
        model.addAttribute("filter", filter)
        model.addAttribute("result", management.list(filter))
        return "users"
    }

    /**
     * Renders the empty create-account form.
     *
     * Requires an ADMIN session.
     *
     * @param model View model.
     * @return View name.
     */
    @GetMapping("$USERS_PATH/new")
    fun newUser(model: Model): String {
        model.addAttribute("username", "")
        return "user-new"
    }

    /**
     * Creates a CLIENT account.
     *
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited. On success the browser is
     * redirected to the new account; on a validation failure the form is shown again (status 422) with the username
     * but never the passwords.
     *
     * @param username Requested login name.
     * @param password Initial password.
     * @param confirmation Password typed a second time.
     * @param model View model for the form shown again on failure.
     * @param response Response whose status is set to 422 on failure.
     * @param redirect Flash attributes carried to the account page.
     * @return A redirect to the account page, or the form view name.
     */
    @PostMapping(USERS_PATH)
    fun create(
        @RequestParam username: String,
        @RequestParam password: String,
        @RequestParam confirmation: String,
        model: Model,
        response: HttpServletResponse,
        redirect: RedirectAttributes
    ): String =
        when (val result = management.create(actor(), username, password, confirmation)) {
            is UserCommandResult.Done -> {
                redirect.addFlashAttribute(NOTICE, "Account created.")
                "redirect:${accountPath(result.userId)}"
            }

            is UserCommandResult.Failed -> {
                response.status = HttpStatus.UNPROCESSABLE_CONTENT.value()
                model.addAttribute("username", username)
                model.addAttribute(ERROR, result.problem.message)
                "user-new"
            }
        }

    /**
     * Renders one account with its available actions.
     *
     * Requires an ADMIN session; unknown ids answer 404.
     *
     * @param id User UUID.
     * @param model View model with the account details.
     * @return View name.
     */
    @GetMapping("$USERS_PATH/{id}")
    fun detail(
        @PathVariable id: String,
        model: Model
    ): String {
        model.addAttribute("detail", management.detail(id) ?: notFound())
        return "user"
    }

    /**
     * Disables an account and ends its credentials and sessions.
     *
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited.
     *
     * @param id User UUID.
     * @param redirect Flash attributes carried to the account page.
     * @return Redirect to the account page.
     */
    @PostMapping("$USERS_PATH/{id}/disable")
    fun disable(
        @PathVariable id: String,
        redirect: RedirectAttributes
    ): String = respond(management.disable(actor(), id), id, "Account disabled. Its sessions and tokens were ended.", redirect)

    /**
     * Re-enables an account.
     *
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited.
     *
     * @param id User UUID.
     * @param redirect Flash attributes carried to the account page.
     * @return Redirect to the account page.
     */
    @PostMapping("$USERS_PATH/{id}/enable")
    fun enable(
        @PathVariable id: String,
        redirect: RedirectAttributes
    ): String = respond(management.enable(actor(), id), id, "Account enabled.", redirect)

    /**
     * Resets an account's password and ends its credentials and sessions.
     *
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited without the password.
     *
     * @param id User UUID.
     * @param password New password.
     * @param confirmation Password typed a second time.
     * @param redirect Flash attributes carried to the account page.
     * @return Redirect to the account page.
     */
    @PostMapping("$USERS_PATH/{id}/password")
    fun resetPassword(
        @PathVariable id: String,
        @RequestParam password: String,
        @RequestParam confirmation: String,
        redirect: RedirectAttributes
    ): String = respond(management.resetPassword(actor(), id, password, confirmation), id, "Password changed. Existing sessions and tokens were ended.", redirect)

    /**
     * Ends an account's API credentials and dashboard sessions while keeping its devices valid.
     *
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited.
     *
     * @param id User UUID.
     * @param redirect Flash attributes carried to the account page.
     * @return Redirect to the account page.
     */
    @PostMapping("$USERS_PATH/{id}/revoke-sessions")
    fun revokeSessions(
        @PathVariable id: String,
        redirect: RedirectAttributes
    ): String = respond(management.revokeSessions(actor(), id), id, "Sessions and tokens ended. Clients must sign in again.", redirect)

    /**
     * Permanently revokes all devices of an account; the explicit confirmation is enforced here, not only in the page.
     *
     * Requires an ADMIN session and a CSRF token; rejected during maintenance and audited.
     *
     * @param id User UUID.
     * @param confirm Must be `yes`; the form sends it through a required checkbox.
     * @param redirect Flash attributes carried to the account page.
     * @return Redirect to the account page.
     */
    @PostMapping("$USERS_PATH/{id}/revoke-devices")
    fun revokeDevices(
        @PathVariable id: String,
        @RequestParam(defaultValue = "") confirm: String,
        redirect: RedirectAttributes
    ): String {
        if (confirm != CONFIRMED) {
            redirect.addFlashAttribute(ERROR, "Tick the confirmation box to revoke all devices.")
            return "redirect:${accountPath(id)}"
        }
        return respond(management.revokeDevices(actor(), id), id, "All devices revoked. Clients must register again with a new device id.", redirect)
    }

    /**
     * Turns a command result into a flash message and a redirect to the account page.
     *
     * @param result Outcome of the command.
     * @param id User UUID taken from the request path.
     * @param success Message shown when the command was applied.
     * @param redirect Flash attributes carried to the account page.
     * @return Redirect to the account page; unknown accounts answer 404 instead.
     */
    private fun respond(
        result: UserCommandResult,
        id: String,
        success: String,
        redirect: RedirectAttributes
    ): String {
        when (result) {
            is UserCommandResult.Done -> {
                redirect.addFlashAttribute(NOTICE, success)
            }

            is UserCommandResult.Failed -> {
                if (result.problem == UserProblem.NOT_FOUND) notFound()
                redirect.addFlashAttribute(ERROR, result.problem.message)
            }
        }
        return "redirect:${accountPath(id)}"
    }

    /**
     * Builds the encoded path of an account page, so a crafted id can never alter the redirect target.
     *
     * @param id User UUID or untrusted path segment.
     * @return Percent-encoded path below `/ui/users`.
     */
    private fun accountPath(id: String): String =
        UriComponentsBuilder
            .fromPath("$USERS_PATH/{id}")
            .buildAndExpand(id)
            .encode()
            .toUriString()
}
