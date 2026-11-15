package de.alexandermora.modsync.server.ui

import de.alexandermora.modsync.server.auth.UserRepository
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.sso.LINKED_MARKER
import de.alexandermora.modsync.server.sso.SsoAccounts
import de.alexandermora.modsync.server.sso.SsoUserServices
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
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
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.session.SessionLimit
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.session.HttpSessionEventPublisher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

private const val MAX_LOGIN_ATTEMPTS_PER_MINUTE = 10
private const val MAX_TRACKED_ADDRESSES = 10_000
private const val MAX_AUDITED_CODE = 60
private const val API_PREFIX = "/ui/api/"
private const val CONTENT_SECURITY_POLICY = "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; form-action 'self'"

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
    return path == "/login" || path == "/logout" || path == "/ui" || path.startsWith("/ui/") || path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/")
}

/**
 * Answers a denied dashboard request.
 *
 * Calls below `/ui/api/` keep the plain 403 that script clients expect. Browsers are sent back to the login page with a reason
 * (`csrf` for a missing or stale CSRF token, `denied` for a signed-in account without the ADMIN role) so they see a message
 * instead of an error page. The target is relative on purpose, so it stays on the public scheme and host behind a reverse proxy.
 */
class DashboardAccessDeniedHandler : AccessDeniedHandler {
    private val plain = AccessDeniedHandlerImpl()

    /**
     * Rejects the request with 403 for the API, or redirects the browser to the login page with the reason.
     *
     * @param request Denied request.
     * @param response Response that receives the status and the `Location` header.
     * @param accessDeniedException Why the request was denied.
     */
    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        if (request.requestURI.startsWith(API_PREFIX)) {
            plain.handle(request, response, accessDeniedException)
            return
        }
        val reason = if (accessDeniedException is CsrfException) "csrf" else "denied"
        response.status = HttpServletResponse.SC_FOUND
        response.setHeader("Location", "/ui/login?$reason")
    }
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
 * Forces the deferred CSRF token to load so the `XSRF-TOKEN` cookie is written on every response that needs it.
 *
 * Spring Security 6 and later generate the token lazily; without this filter a single-page client would never receive
 * the cookie on pages that do not render the token themselves.
 */
class CsrfCookieFilter : OncePerRequestFilter() {
    /**
     * Touches the token, then continues the chain.
     *
     * @param request Incoming request.
     * @param response Response that receives the cookie.
     * @param chain Remaining filter chain.
     */
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        chain.doFilter(request, response)
    }
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
        val loginAttempt = request.method == "POST" && request.requestURI == "/login"
        val providerCallback = request.method == "GET" && request.requestURI.startsWith("/login/oauth2/code/")
        if (loginAttempt || providerCallback) {
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
 * Adds OAuth2 login (Google and GitHub) to the dashboard chain.
 *
 * Authorization-code flow with `state` validation, PKCE for both providers and the OpenID Connect `nonce`. The account is always
 * chosen by [SsoUserServices], so a provider can only ever sign in an account that was explicitly linked.
 *
 * @param http Dashboard security builder.
 * @param repository Configured providers.
 * @param accounts Mapping between provider identities and accounts.
 * @param audit Audit log writer.
 */
private fun configureSso(http: HttpSecurity, repository: ClientRegistrationRepository, accounts: SsoAccounts, audit: AuditService) {
    val services = SsoUserServices(accounts)
    http.oauth2Login {
        it.loginPage("/ui/login")
        it.authorizationEndpoint { endpoint -> endpoint.authorizationRequestResolver(pkceResolver(repository)) }
        it.userInfoEndpoint { user ->
            user.userService { request -> services.loadOAuth2(request) }
            user.oidcUserService { request -> services.loadOidc(request) }
        }
        it.successHandler(
            AuthenticationSuccessHandler { request, response, authentication ->
                val provider = (authentication as OAuth2AuthenticationToken).authorizedClientRegistrationId
                val session = request.getSession(false)
                val linked = session?.getAttribute(LINKED_MARKER) != null
                session?.removeAttribute(LINKED_MARKER)
                audit.record(authentication.name, (if (linked) "sso-linked:" else "sso-login:") + provider, authentication.name)
                response.sendRedirect(request.contextPath + if (linked) "/ui/account" else "/ui")
            }
        )
        it.failureHandler(
            AuthenticationFailureHandler { request, response, exception ->
                val code = (exception as? OAuth2AuthenticationException)?.error?.errorCode ?: "sso_failed"
                audit.record("anonymous", "sso-denied:" + code.take(MAX_AUDITED_CODE), "-")
                response.sendRedirect(request.contextPath + "/ui/login?sso=denied")
            }
        )
    }
}

/**
 * Builds the authorization request resolver; every request carries a PKCE challenge (S256) next to `state`.
 *
 * Both Google and GitHub accept PKCE for web applications, so the authorization code cannot be redeemed by anyone who only
 * saw the redirect.
 *
 * @param repository Configured providers.
 * @return The resolver used by the authorization endpoint.
 */
private fun pkceResolver(repository: ClientRegistrationRepository): OAuth2AuthorizationRequestResolver =
    DefaultOAuth2AuthorizationRequestResolver(repository, "/oauth2/authorization").apply {
        setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
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
    fun dashboardChain(
        http: HttpSecurity,
        users: UserRepository,
        passwords: PasswordEncoder,
        sessionRegistry: SessionRegistry,
        registrations: ObjectProvider<ClientRegistrationRepository>,
        ssoAccounts: SsoAccounts,
        audit: AuditService
    ): SecurityFilterChain {
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
                    .requestMatchers("/login", "/ui/login", "/ui/index.html", "/ui/*.js", "/ui/*.css", "/ui/*.ico", "/ui/*.txt", "/ui/api/sso/providers", "/oauth2/**", "/login/oauth2/**")
                    .permitAll()
                    .anyRequest()
                    .hasRole("ADMIN")
            }.formLogin {
                it
                    .loginPage("/ui/login")
                    .loginProcessingUrl("/login")
                    .failureUrl("/ui/login?error")
                    .defaultSuccessUrl("/ui", true)
                    .permitAll()
            }.logout { it.logoutUrl("/logout").logoutSuccessUrl("/ui/login?logout") }
            .exceptionHandling {
                // Script clients get a plain 401; browsers are sent to the login page.
                val api = RequestMatcher { request -> request.requestURI.startsWith(API_PREFIX) }
                val entryPoint = DelegatingAuthenticationEntryPoint(linkedMapOf<RequestMatcher, AuthenticationEntryPoint>(api to HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                entryPoint.setDefaultEntryPoint(LoginUrlAuthenticationEntryPoint("/ui/login"))
                it.authenticationEntryPoint(entryPoint)
                it.accessDeniedHandler(DashboardAccessDeniedHandler())
            }.csrf {
                // The Angular client reads the token from the XSRF-TOKEN cookie and echoes it in X-XSRF-TOKEN. The token is never
                // rendered into HTML bodies, so the plain handler is safe here and also serves the form-based login page.
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                it.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
            }.addFilterAfter(CsrfCookieFilter(), BasicAuthenticationFilter::class.java)
            .sessionManagement {
                it.sessionFixation { fixation -> fixation.migrateSession() }
                it.sessionConcurrency { concurrency -> concurrency.maximumSessions(SessionLimit.UNLIMITED).sessionRegistry(sessionRegistry).expiredUrl("/ui/login?expired") }
            }.headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
                headers.frameOptions { it.deny() }
            }.addFilterBefore(LoginThrottleFilter(), UsernamePasswordAuthenticationFilter::class.java)
        registrations.ifAvailable?.let { configureSso(http, it, ssoAccounts, audit) }
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
