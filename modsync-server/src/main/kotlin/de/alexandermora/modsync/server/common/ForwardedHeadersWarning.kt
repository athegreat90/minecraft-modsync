package de.alexandermora.modsync.server.common

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

private const val FORWARDED_PROTO = "X-Forwarded-Proto"

private const val MESSAGE =
    "Requests carry $FORWARDED_PROTO but MODSYNC_FORWARD_HEADERS is not set, so redirects, session cookies and single sign-on " +
        "callbacks are built from the proxy-facing http:// address. Behind a TLS reverse proxy set MODSYNC_FORWARD_HEADERS=framework " +
        "(see docs/security/SSO_SETUP.md)."

/**
 * Warns once when a reverse proxy sends forwarded headers that the server is configured to ignore.
 *
 * With the default strategy the server builds every redirect from its own plain-HTTP connection, which silently breaks sign-in
 * behind a TLS proxy. The filter never changes the request or the response.
 *
 * @param strategy Value of `server.forward-headers-strategy`; empty and `none` mean the headers are ignored.
 * @property warn Receives the warning text; defaults to the application log.
 */
class ForwardedHeadersWarningFilter(
    strategy: String,
    private val warn: (String) -> Unit = { logger.warn { it } }
) : OncePerRequestFilter() {
    private val ignored = strategy.isBlank() || strategy.equals("none", ignoreCase = true)
    private val warned = AtomicBoolean(false)

    /**
     * Reports the first request that carries a forwarded protocol header while the headers are ignored, then continues the chain.
     *
     * @param request Incoming request.
     * @param response Response, untouched.
     * @param filterChain Remaining filter chain.
     */
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (ignored && request.getHeader(FORWARDED_PROTO) != null && warned.compareAndSet(false, true)) {
            warn(MESSAGE)
        }
        filterChain.doFilter(request, response)
    }
}

/** Registers [ForwardedHeadersWarningFilter] in front of every other servlet filter. */
@Configuration
class ForwardedHeadersWarningConfiguration {
    /**
     * Creates the filter with the configured forwarded-headers strategy.
     *
     * @param strategy Value of `server.forward-headers-strategy`, `none` when unset.
     * @return The warning filter.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun forwardedHeadersWarningFilter(
        @Value($$"${server.forward-headers-strategy:none}") strategy: String
    ): ForwardedHeadersWarningFilter = ForwardedHeadersWarningFilter(strategy)
}
