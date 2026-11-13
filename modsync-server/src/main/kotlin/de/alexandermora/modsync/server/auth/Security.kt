package de.alexandermora.modsync.server.auth

import de.alexandermora.modsync.domain.sha512
import de.alexandermora.modsync.protocol.COMPONENT_VERSION
import de.alexandermora.modsync.protocol.PROTOCOL
import de.alexandermora.modsync.protocol.versionAtLeast

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.beans.factory.annotation.Value
import java.util.UUID
import java.io.ByteArrayInputStream
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequestWrapper
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Wires the stateless API security chain.
 *
 * Role rules: everything below `/api/v1/admin` and the Prometheus endpoint need ADMIN, everything below `/api/v1/agent` needs AGENT, other
 * `/api/v1` routes need CLIENT or ADMIN, and everything unmatched is denied.
 */
@Configuration
class SecurityConfiguration {
    /**
     * Builds the API filter chain.
     *
     * Only health, info, login, refresh and enrollment are public. CSRF, CORS and sessions are disabled because
     * authentication is bearer-token based; [Guard] runs first to resolve the credential and enforce limits.
     * Ordered last so the dashboard chain gets the first chance to match.
     *
     * @param http Security builder.
     * @param db JDBC access used by [Guard] to look up credentials.
     * @param maintenance Whether the server rejects all non-read requests with 503.
     * @return The API chain.
     */
    @Bean @Order(Ordered.LOWEST_PRECEDENCE) fun chain(http: HttpSecurity, db: JdbcTemplate, @Value($$"${modsync.maintenance}") maintenance: Boolean): SecurityFilterChain {
        http.csrf { it.disable() }.cors { it.disable() }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll() // keep real error statuses (404, 409...) instead of re-authorizing /error
                    .requestMatchers("/api/v1/info", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/enroll", "/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/admin/**", "/actuator/prometheus").hasRole("ADMIN")
                    .requestMatchers("/api/v1/agent/**").hasRole("AGENT")
                    .requestMatchers("/api/v1/**").hasAnyRole("CLIENT", "ADMIN").anyRequest().denyAll()
            }.exceptionHandling { it.authenticationEntryPoint { _, res, _ -> res.sendError(401) } }
            .addFilterBefore(Guard(db, maintenance), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
/**
 * Front filter of the API chain: correlation id, maintenance mode, rate limiting, protocol checks, bearer authentication and body limits.
 *
 * Credentials are looked up by SHA-512 hash and must belong to an unrevoked agent, or to an unrevoked device of an enabled user. Request bodies
 * above 2 MiB are rejected with 413 before reaching controllers.
 *
 * @property db JDBC access for credential, agent and device lookups.
 * @property maintenance Whether non-GET/HEAD requests are rejected with 503.
 */
class Guard(private val db: JdbcTemplate, private val maintenance: Boolean): OncePerRequestFilter() {
    private val rate = ConcurrentHashMap<String, Pair<Long, Int>>()

    /**
     * Assigns a correlation id to the request and logs its outcome.
     *
     * @param req Incoming request.
     * @param res Response, which receives the `X-Correlation-ID` header.
     * @param chain Remaining filter chain.
     */
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val correlation=UUID.randomUUID().toString()
        res.setHeader("X-Correlation-ID",correlation); req.setAttribute("correlationId",correlation)
        org.slf4j.MDC.put("correlationId",correlation)
        try { process(req,res,chain) }
        finally {
            logger.info { "http method=${req.method} status=${res.status} correlationId=$correlation" }
            org.slf4j.MDC.remove("correlationId")
        }
    }
    /**
     * Applies the request checks in order and continues the chain.
     *
     * Order: maintenance (503), per-address limit on login/refresh/enroll (429, max 20 per minute), protocol and
     * version headers (426), bearer authentication, then the 2 MiB body limit (413). The body is buffered so
     * controllers can still read it.
     *
     * @param req Incoming request.
     * @param res Response used for rejections.
     * @param chain Remaining filter chain.
     */
    private fun process(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        if (maintenance && req.method !in setOf("GET", "HEAD")) { res.sendError(503); return }
        if (req.requestURI in setOf("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/enroll")) {
            val now = System.currentTimeMillis() / 60000
            if (rate.size > 10000) rate.entries.removeIf { it.value.first < now }
            if (rate.size > 10000) { res.sendError(429); return }
            val counter = rate.compute(req.remoteAddr) { _, old -> if (old?.first == now) now to old.second + 1 else now to 1 } ?: error("Rate counter missing")
            if (counter.second > 20) { res.sendError(429); return }
        }
        if (req.requestURI.startsWith("/api/v1/") && req.requestURI != "/api/v1/info" && (req.getHeader("X-ModSync-Protocol") != PROTOCOL.toString() || !versionAtLeast(req.getHeader("X-ModSync-Version") ?: "", COMPONENT_VERSION))) { res.sendError(426); return }
        req.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") && it.length <= 512 }?.substring(7)?.let { token ->
            val rows = db.queryForList("SELECT * FROM credentials WHERE token_hash=? AND expires_at>CURRENT_TIMESTAMP(6) AND kind='access'", sha512(token.toByteArray()))
            rows.singleOrNull()?.let { row ->
                val subject = row["subject_id"].toString(); val role = row["role"].toString(); val device = row["device_id"]?.toString()
                val active = if (role == "AGENT") db.queryForObject("SELECT COUNT(*) FROM scanner_agents WHERE id=? AND revoked=FALSE", Long::class.java, subject) == 1L
                    else device != null && db.queryForObject(
                        "SELECT COUNT(*) FROM client_devices d JOIN users u ON u.id=d.user_id " +
                            "WHERE d.id=? AND d.revoked=FALSE AND u.enabled=TRUE", Long::class.java, device) == 1L
                if (active) {
                    SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(subject, null, listOf(SimpleGrantedAuthority("ROLE_$role")))
                    req.setAttribute("deviceId", device)
                }
            }
        }
        if (req.contentLengthLong > 2 * 1024 * 1024) { res.sendError(413); return }
        if (req.method in setOf("POST", "PUT", "PATCH")) {
            val bytes = req.inputStream.readNBytes(2 * 1024 * 1024 + 1)
            if (bytes.size > 2 * 1024 * 1024) { res.sendError(413); return }
            val wrapped = object: HttpServletRequestWrapper(req) {
                override fun getInputStream(): ServletInputStream {
                    val input = ByteArrayInputStream(bytes)
                    return object: ServletInputStream() {
                        override fun read() = input.read()
                        override fun isFinished() = input.available() == 0
                        override fun isReady() = true
                        override fun setReadListener(listener: ReadListener?) { throw UnsupportedOperationException() }
                    }
                }
            }
            chain.doFilter(wrapped, res)
        } else chain.doFilter(req, res)
    }
}
