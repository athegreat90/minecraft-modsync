package de.alexandermora.modsync.server.users

/** Number of accounts shown per page of the user list. */
const val USERS_PAGE_SIZE = 25

/**
 * Search and paging criteria of the user list, taken from query parameters and normalized by the service.
 *
 * @property query Username fragment, or null for no text filter.
 * @property role `ADMIN` or `CLIENT`, or null for any role; other values are ignored.
 * @property status `enabled` or `disabled`, or null for any status; other values are ignored.
 * @property page Zero-based page index; negative values are treated as the first page.
 */
data class UserFilter(
    val query: String? = null,
    val role: String? = null,
    val status: String? = null,
    val page: Int = 0
)

/**
 * Account data safe to render: it never carries the password hash or any credential.
 *
 * @property id User UUID.
 * @property username Login name.
 * @property role `ADMIN` or `CLIENT`.
 * @property enabled Whether the account may authenticate.
 * @property manageable Whether the dashboard may change this account (only CLIENT accounts).
 */
data class UserSummary(
    val id: String,
    val username: String,
    val role: String,
    val enabled: Boolean,
    val manageable: Boolean
)

/**
 * One page of the user list.
 *
 * @property users Accounts on this page, ordered by username.
 * @property page Zero-based index of this page.
 * @property totalPages Number of pages for the current filter.
 * @property total Number of accounts matching the filter.
 */
data class UserPage(
    val users: List<UserSummary>,
    val page: Int,
    val totalPages: Int,
    val total: Long
)

/**
 * Details shown on the account page.
 *
 * @property user The account.
 * @property activeDevices Number of devices that have not been revoked.
 */
data class UserDetail(
    val user: UserSummary,
    val activeDevices: Long
)

/**
 * Expected reasons for which a user-management command is refused.
 *
 * @property message Text shown to the administrator; it never echoes submitted values.
 */
enum class UserProblem(
    val message: String
) {
    INVALID_USERNAME("Usernames use 1 to 100 letters, digits, '_', '.' or '-'."),
    INVALID_PASSWORD("Passwords need 16 to 256 characters."),
    PASSWORD_MISMATCH("The password and its confirmation do not match."),
    USERNAME_TAKEN("That username is already in use."),
    NOT_FOUND("The account does not exist."),
    NOT_MANAGEABLE("Only CLIENT accounts can be changed from the dashboard.")
}

/** Outcome of a user-management command. */
sealed interface UserCommandResult {
    /**
     * The command was applied.
     *
     * @property userId UUID of the affected account.
     */
    data class Done(
        val userId: String
    ) : UserCommandResult

    /**
     * The command was refused and nothing changed.
     *
     * @property problem Reason for the refusal.
     */
    data class Failed(
        val problem: UserProblem
    ) : UserCommandResult
}
