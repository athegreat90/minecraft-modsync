package de.alexandermora.modsync.server.common

import jakarta.servlet.http.HttpServletRequest
import de.alexandermora.modsync.protocol.Failure
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

fun reject(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
fun requireState(ok: Boolean, message: String) { if (!ok) reject(message) }
fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)
fun unauthorized(): Nothing = throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
fun found(ok: Boolean) { if (!ok) notFound() }

fun actor(): String = SecurityContextHolder.getContext().authentication?.name ?: unauthorized()

/** Runs a command and answers with the `true` acknowledgement expected by the wire protocol. */
inline fun acknowledged(command: () -> Unit): Boolean { command(); return true }

@RestControllerAdvice
class Errors {
    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(request: HttpServletRequest) =
        Failure("INVALID_REQUEST", "Invalid request payload", request.getAttribute("correlationId").toString())
}
