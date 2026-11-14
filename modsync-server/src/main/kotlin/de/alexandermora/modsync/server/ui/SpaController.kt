package de.alexandermora.modsync.server.ui

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Serves the Angular single-page application for its client-side routes.
 *
 * Only the routes the application defines are listed, so the fallback can never capture everything under `/ui/api/` or a static asset.
 * Access control is not decided here: the dashboard security chain has already authorized the request, and only the
 * sign-in page and the static files are public.
 */
@Controller
class SpaController {
    /**
     * Forwards a client-side route to the application shell.
     *
     * @return The forward to `index.html`, which the static resource handler serves.
     */
    @Suppress("FunctionOnlyReturningConstant") // Spring MVC handler: the view name is the response
    @GetMapping("/ui", "/ui/", "/ui/login", "/ui/account", "/ui/servers/{id}", "/ui/users", "/ui/users/new", "/ui/users/{id}")
    fun shell(): String = "forward:/ui/index.html"

    /**
     * Sends the former server-rendered login address to the Angular sign-in page.
     *
     * @return A redirect to `/ui/login`.
     */
    @Suppress("FunctionOnlyReturningConstant") // Spring MVC handler: the view name is the response
    @GetMapping("/login")
    fun legacyLogin(): String = "redirect:/ui/login"
}
