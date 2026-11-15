package de.alexandermora.modsync.server.ui

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Duration

/**
 * Caches the fingerprinted files of the administration UI for a year.
 *
 * The Angular build names its scripts and styles after their content (`main-XXXXXXXX.js`, `chunk-XXXXXXXX.js`, `styles-XXXXXXXX.css`),
 * so a given name never changes and browsers can reuse it without asking the server.
 * Every other file under `/ui/` (the shell, the icon, the single-page-application fallback) is not matched here and keeps the
 * default `no-cache` policy from `spring.web.resources.cache.cachecontrol`, so a release is picked up on the next visit.
 */
@Configuration
class StaticAssets : WebMvcConfigurer {
    /**
     * Registers the long-lived cache policy for the fingerprinted files only.
     *
     * @param registry Registry of resource handlers; more specific patterns win over the catch-all default handler.
     */
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/ui/main-????????.js", "/ui/chunk-????????.js", "/ui/styles-????????.css")
            .addResourceLocations("classpath:/static/ui/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
    }
}
