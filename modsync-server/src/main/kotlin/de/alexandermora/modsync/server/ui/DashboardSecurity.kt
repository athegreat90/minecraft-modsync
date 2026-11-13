package de.alexandermora.modsync.server.ui

import de.alexandermora.modsync.server.auth.UserRepository
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.session.SessionRegistryImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.session.SessionLimit
import org.springframework.security.web.session.HttpSessionEventPublisher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

private const val MAX_LOGIN_ATTEMPTS_PER_MINUTE = 10
private const val MAX_TRACKED_ADDRESSES = 10_000
private const val CONTENT_SECURITY_POLICY = "default-src 'self'; style-src 'self'; script-src 'none'; frame-ancestors 'none'; form-action 'self'"

/**
 * Decides whether a request belongs to the dashboard chain.
 *
 * Error dispatches are matched by the originally requested path so that dashboard errors keep their
 * real status instead of being re-authorized by the API chain.
 *
 * @param request Incoming request.
 * @return True for dashboard pages, login, logout and errors raised below them.
 */
fun isDashboardRequest(request: HttpServletRequest): Boolean {
    val path = if (request.dispatcherType == DispatcherType.ERROR) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String ?: "" else request.requestURI
    return path == "/login" || path == "/logout" || path == "/ui" || path.startsWith("/ui/")
}

/**
 * Loads accounts for the dashboard login; the authentication name is the user id, matching API audit actors.
 *
 * @property users Account repository.
 */
class DashboardUserDetailsService(private val users: UserRepository) : UserDetailsService {
    /**
     * Loads an account by username.
     *
     * @param username Submitted username.
     * @return Details whose name is the user UUID and whose single authority is the stored role; disabled accounts are
     * returned with the disabled flag set and rejected after the password check by [rejectDisabledAccount].
     * @throws UsernameNotFoundException when the account does not exist.
     */
    override fun loadUserByUsername(username: String): UserDetails {
        val user = users.findByUsername(username) ?: throw UsernameNotFoundException("Unknown user")
        return User
            .withUsername(user.id)
            .password(user.passwordHash)
            .roles(user.role)
            .disabled(!user.enabled)
            .build()
    }
}

/**
 * Rejects a disabled account once its password has been verified.
 *
 * Spring's default pre-authentication check would throw before the (expensive) password comparison, which makes disabled
 * accounts answer measurably faster than wrong passwords. Running the check afterwards keeps the response time and the
 * generic `/login?error` redirect identical for every failure.
 *
 * @param details Account whose password was just verified.
 * @throws DisabledException when the account is disabled.
 */
fun rejectDisabledAccount(details: UserDetails) {
    if (!details.isEnabled) throw DisabledException("Account is disabled")
}

/**
 * Limits login submissions per remote address to slow down password guessing.
 *
 * The counter is in memory and bounded; it complements, not replaces, the password hash cost.
 */
class LoginThrottleFilter : OncePerRequestFilter() {
    private val attempts = ConcurrentHashMap<String, Pair<Long, Int>>()

    /**
     * Rejects login posts above the per-minute limit with 429.
     *
     * @param request Incoming request.
     * @param response Response used for the rejection.
     * @param chain Remaining filter chain.
     */
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.method == "POST" && request.requestURI == "/login") {
            val minute = System.currentTimeMillis() / 60_000
            if (attempts.size > MAX_TRACKED_ADDRESSES) attempts.entries.removeIf { it.value.first < minute }
            val counter = attempts.compute(request.remoteAddr) { _, old -> if (old?.first == minute) minute to old.second + 1 else minute to 1 }
            if (counter == null || counter.second > MAX_LOGIN_ATTEMPTS_PER_MINUTE) {
                response.sendError(429)
                return
            }
        }
        chain.doFilter(request, response)
    }
}

/**
 * Session-based, CSRF-protected security for the administration dashboard.
 *
 * The stateless API chain is unaffected; this configuration only wires [dashboardChain].
 */
@Configuration
class DashboardSecurityConfiguration {
    /**
     * Builds the dashboard filter chain.
     *
     * Only administrators may use the dashboard paths under /ui. CSRF protection stays enabled, cookies are HTTP-only, sessions are
     * rotated on login and a restrictive content security policy forbids scripts and foreign resources.
     *
     * Every session is tracked in [sessionRegistry] (with no concurrency limit) so that disabling an account or resetting its
     * password can expire its live sessions.
     *
     * @param http Security builder.
     * @param users Account repository.
     * @param passwords Password encoder used to verify submitted passwords.
     * @param sessionRegistry Registry of live dashboard sessions.
     * @return The dashboard chain, ordered before the API chain.
     */
    @Bean
    @Order(1)
    fun dashboardChain(http: HttpSecurity, users: UserRepository, passwords: PasswordEncoder, sessionRegistry: SessionRegistry): SecurityFilterChain {
        val provider =
            DaoAuthenticationProvider(DashboardUserDetailsService(users)).apply {
                setPasswordEncoder(passwords)
                setPreAuthenticationChecks { }
                setPostAuthenticationChecks(::rejectDisabledAccount)
            }
        http
            .securityMatcher(RequestMatcher(::isDashboardRequest))
            .authenticationProvider(provider)
            .authorizeHttpRequests {
                it
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/login", "/ui/assets/**")
                    .permitAll()
                    .anyRequest()
                    .hasRole("ADMIN")
            }.formLogin {
                it
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/ui", true)
                    .permitAll()
            }.logout { it.logoutUrl("/logout").logoutSuccessUrl("/login?logout") }
            .sessionManagement {
                it.sessionFixation { fixation -> fixation.migrateSession() }
                it.sessionConcurrency { concurrency -> concurrency.maximumSessions(SessionLimit.UNLIMITED).sessionRegistry(sessionRegistry) }
            }.headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
                headers.frameOptions { it.deny() }
            }.addFilterBefore(LoginThrottleFilter(), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    /**
     * Creates the registry that tracks live dashboard sessions per account.
     *
     * @return An in-memory registry; sessions are lost on restart, like the sessions themselves.
     */
    @Bean
    fun sessionRegistry(): SessionRegistry = SessionRegistryImpl()

    /**
     * Publishes servlet session lifecycle events so [SessionRegistry] forgets ended sessions.
     *
     * @return The publisher, registered as a servlet listener by Spring Boot.
     */
    @Bean
    fun httpSessionEventPublisher(): HttpSessionEventPublisher = HttpSessionEventPublisher()
}
