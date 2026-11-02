package de.alexandermora.modsync.server.profile

import de.alexandermora.modsync.domain.PersonalProfile
import de.alexandermora.modsync.server.common.actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/personal")
class ProfileController(private val profiles: ProfileService) {
    @GetMapping fun list(): List<PersonalProfile> = profiles.list(actor())

    @PutMapping("/{id}") fun save(@PathVariable id: String, @RequestBody profile: PersonalProfile): PersonalProfile =
        profiles.save(actor(), id, profile)
}
