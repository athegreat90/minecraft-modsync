package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.PersonalProfile
import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.profile.PersonalProfileEntity
import de.alexandermora.modsync.server.profile.PersonalProfileModRepository
import de.alexandermora.modsync.server.profile.PersonalProfileRepository
import de.alexandermora.modsync.server.profile.ProfileService
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies personal profile ownership and validation without a database. */
class ProfileServiceTest {
    private val id = "11111111-1111-1111-1111-111111111111"
    private val profiles = Mockito.mock(PersonalProfileRepository::class.java)
    private val service = ProfileService(profiles, Mockito.mock(PersonalProfileModRepository::class.java), Mockito.mock(Modrinth::class.java), Mockito.mock(AuditService::class.java))

    /** The id in the body must equal the path id. */
    @Test
    fun saveRejectsIdMismatch() {
        assertFailsWith<IllegalArgumentException> { service.save("user", id, PersonalProfile("22222222-2222-2222-2222-222222222222", "p", emptyList())) }
    }

    /** Names must be 1-200 characters. */
    @Test
    fun saveRejectsEmptyName() {
        assertFailsWith<IllegalArgumentException> { service.save("user", id, PersonalProfile(id, "", emptyList())) }
    }

    /** A profile owned by another user is a conflict, not an overwrite. */
    @Test
    fun saveRejectsForeignProfile() {
        Mockito.`when`(profiles.lockById(id)).thenReturn(PersonalProfileEntity(id = id, userId = "someone-else"))
        val error = assertFailsWith<ResponseStatusException> { service.save("user", id, PersonalProfile(id, "p", emptyList())) }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
    }

    /** Listing an empty account yields no profiles. */
    @Test
    fun listIsEmptyForNewUsers() {
        assertEquals(emptyList(), service.list("user"))
    }
}
