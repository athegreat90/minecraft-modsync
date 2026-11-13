package de.alexandermora.modsync.server.auth

/** Role name of administrators, who may use the dashboard and the admin API. */
const val ADMIN_ROLE = "ADMIN"

/** Role name of players and other client accounts. */
const val CLIENT_ROLE = "CLIENT"

/** Shortest accepted password, in characters. */
const val MIN_PASSWORD_LENGTH = 16

/** Longest accepted password, in characters. */
const val MAX_PASSWORD_LENGTH = 256

private val USERNAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")

/**
 * Checks a username against the account naming rule shared by the API and the dashboard.
 *
 * @param username Candidate login name.
 * @return True for 1-100 letters, digits, `_`, `.` or `-`.
 */
fun isValidUsername(username: String): Boolean = username.matches(USERNAME_PATTERN)

/**
 * Checks a password against the length rule shared by account creation and password reset.
 *
 * @param password Candidate clear password.
 * @return True for 16-256 characters.
 */
fun isValidPassword(password: String): Boolean = password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH
