package de.alexandermora.modsync.server.ui

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Sends the bare host to the administration dashboard.
 *
 * Without it the root URL and the favicon request every browser makes fall through to the API chain and answer 401 with an
 * error page. The redirect targets are relative on purpose, so they keep the public scheme and host behind a reverse proxy.
 */
@Controller
class RootRedirectController {
    /**
     * Redirects the root URL to the dashboard shell.
     *
     * @return A 302 redirect to `/ui/`.
     */
    @GetMapping("/")
    fun root(): ResponseEntity<Void> = redirect("/ui/")

    /**
     * Redirects the icon request browsers make at the host root to the dashboard icon.
     *
     * @return A 302 redirect to `/ui/favicon.ico`.
     */
    @GetMapping("/favicon.ico")
    fun favicon(): ResponseEntity<Void> = redirect("/ui/favicon.ico")

    private fun redirect(target: String): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target).build()
}
