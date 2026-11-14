package de.alexandermora.modsync.server.ui.api

import de.alexandermora.modsync.domain.PublicationPolicy
import kotlinx.serialization.Serializable

/**
 * The signed-in administrator.
 *
 * @property username Login name.
 * @property role Stored role of the account.
 */
@Serializable
data class SessionView(
    val username: String,
    val role: String
)

/**
 * Request body that changes a publication policy.
 *
 * @property policy Requested policy.
 */
@Serializable
data class PolicyRequest(
    val policy: PublicationPolicy
)

/**
 * Request body that creates a CLIENT account.
 *
 * @property username Requested login name.
 * @property password Initial clear password.
 * @property confirmation Password typed a second time.
 */
@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val confirmation: String
)

/**
 * Request body that resets a password.
 *
 * @property password New clear password.
 * @property confirmation Password typed a second time.
 */
@Serializable
data class ResetPasswordRequest(
    val password: String,
    val confirmation: String
)

/**
 * Request body of a destructive action that needs explicit confirmation.
 *
 * @property confirm Must be true for the action to run.
 */
@Serializable
data class ConfirmRequest(
    val confirm: Boolean
)

/**
 * Result of a user command that was applied.
 *
 * @property userId UUID of the affected account.
 */
@Serializable
data class CommandDone(
    val userId: String
)

/**
 * Refusal of a command; the message never echoes submitted values.
 *
 * @property code Stable machine-readable code.
 * @property message Text safe to show to an administrator.
 */
@Serializable
data class Problem(
    val code: String,
    val message: String
)

/** Empty acknowledgement of a command that changed no returned data. */
@Serializable
object Ack
