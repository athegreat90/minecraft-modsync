package de.alexandermora.modsync.server.common

import de.alexandermora.modsync.protocol.Failure
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Aborts with 409 Conflict.
 *
 * @param message Reason reported to the caller.
 * @return Never returns.
 */
fun reject(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)

/**
 * Aborts with 409 Conflict unless [ok].
 *
 * @param ok Condition that must hold.
 * @param message Reason reported when it does not.
 */
fun requireState(ok: Boolean, message: String) {
    if (!ok) reject(message)
}

/**
 * Aborts with 404 Not Found.
 *
 * @return Never returns.
 */
fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

/**
 * Aborts with 401 Unauthorized.
 *
 * @return Never returns.
 */
fun unauthorized(): Nothing = throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

/**
 * Aborts with 404 Not Found unless [ok]; used to hide inaccessible resources.
 *
 * @param ok Condition that must hold.
 */
fun found(ok: Boolean) {
    if (!ok) notFound()
}

/**
 * Names the authenticated principal of the current request.
 *
 * @return The principal name (user, device owner or agent UUID); aborts with 401 when unauthenticated.
 */
fun actor(): String = SecurityContextHolder.getContext().authentication?.name ?: unauthorized()

/**
 * Runs a command and answers with the `true` acknowledgement expected by the wire protocol.
 *
 * @param command Action to perform.
 * @return Always `true`.
 */
inline fun acknowledged(command: () -> Unit): Boolean {
    command()
    return true
}

/** Maps request errors to protocol failure bodies. */
@RestControllerAdvice
class Errors {
    /**
     * Maps malformed or invalid payloads to a 400 without echoing the offending input.
     *
     * @param request Failing request, whose correlation id is returned for support.
     * @return Failure body with code `INVALID_REQUEST`.
     */
    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(request: HttpServletRequest) =
        Failure("INVALID_REQUEST", "Invalid request payload", request.getAttribute("correlationId").toString())
}
