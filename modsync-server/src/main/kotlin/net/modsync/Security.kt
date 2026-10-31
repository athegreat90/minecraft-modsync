package net.modsync

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
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

@Configuration
class SecurityConfiguration {
    @Bean fun chain(http: HttpSecurity, db: JdbcTemplate, @Value("\${modsync.maintenance}") maintenance: Boolean): SecurityFilterChain {
        http.csrf { it.disable() }.cors { it.disable() }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/info", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/enroll", "/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/admin/**", "/actuator/prometheus").hasRole("ADMIN")
                    .requestMatchers("/api/v1/agent/**").hasRole("AGENT")
                    .requestMatchers("/api/v1/**").hasAnyRole("CLIENT", "ADMIN").anyRequest().denyAll()
            }.exceptionHandling { it.authenticationEntryPoint { _, res, _ -> res.sendError(401) } }
            .addFilterBefore(Guard(db, maintenance), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
class Guard(private val db: JdbcTemplate, private val maintenance: Boolean): OncePerRequestFilter() {
    private val rate = ConcurrentHashMap<String, Pair<Long, Int>>()
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val correlation=UUID.randomUUID().toString()
        res.setHeader("X-Correlation-ID",correlation); req.setAttribute("correlationId",correlation)
        org.slf4j.MDC.put("correlationId",correlation)
        try { process(req,res,chain) }
        finally {
            org.slf4j.LoggerFactory.getLogger(Guard::class.java).info("http method={} status={} correlationId={}",req.method,res.status,correlation)
            org.slf4j.MDC.remove("correlationId")
        }
    }
    private fun process(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        if (maintenance && req.method !in setOf("GET", "HEAD")) { res.sendError(503); return }
        if (req.requestURI in setOf("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/enroll")) {
            val now = System.currentTimeMillis() / 60000
            if (rate.size > 10000) rate.entries.removeIf { it.value.first < now }
            if (rate.size > 10000) { res.sendError(429); return }
            val counter = rate.compute(req.remoteAddr) { _, old -> if (old?.first == now) now to old.second + 1 else now to 1 }!!
            if (counter.second > 20) { res.sendError(429); return }
        }
        if (req.requestURI.startsWith("/api/v1/") && req.requestURI != "/api/v1/info" && (req.getHeader("X-ModSync-Protocol") != PROTOCOL.toString() || !versionAtLeast(req.getHeader("X-ModSync-Version") ?: "", COMPONENT_VERSION))) { res.sendError(426); return }
        req.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") && it.length <= 512 }?.substring(7)?.let { token ->
            val rows = db.queryForList("SELECT * FROM credentials WHERE token_hash=? AND expires_at>CURRENT_TIMESTAMP(6) AND kind='access'", sha512(token.toByteArray()))
            rows.singleOrNull()?.let { row ->
                val subject = row["subject_id"].toString(); val role = row["role"].toString(); val device = row["device_id"]?.toString()
                val active = if (role == "AGENT") db.queryForObject("SELECT COUNT(*) FROM scanner_agents WHERE id=? AND revoked=FALSE", Long::class.java, subject) == 1L
                    else device != null && db.queryForObject("SELECT COUNT(*) FROM client_devices WHERE id=? AND revoked=FALSE", Long::class.java, device) == 1L
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
