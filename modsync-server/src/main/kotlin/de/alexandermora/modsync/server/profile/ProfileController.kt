package de.alexandermora.modsync.server.profile

import de.alexandermora.modsync.domain.PersonalProfile
import de.alexandermora.modsync.server.common.actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST endpoints for the caller's personal client-side profiles.
 *
 * Requires the CLIENT or ADMIN role; profiles are only visible to their owner.
 *
 * @property profiles Profile service.
 */
@RestController
@RequestMapping("/api/v1/personal")
class ProfileController(private val profiles: ProfileService) {
    /**
     * Lists the caller's personal profiles.
     *
     * @return Profiles sorted by name.
     */
    @GetMapping fun list(): List<PersonalProfile> = profiles.list(actor())

    /**
     * Creates or replaces the caller's profile with the given id.
     *
     * @param id Profile UUID (path); must equal the id in the body.
     * @param profile Profile with at most 200 confirmed client-only artifacts.
     * @return The stored profile; 409 when owned by another user or an artifact is not client-only.
     */
    @PutMapping("/{id}")
    fun save(
        @PathVariable id: String,
        @RequestBody profile: PersonalProfile
    ): PersonalProfile =
        profiles.save(actor(), id, profile)
}
