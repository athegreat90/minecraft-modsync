package de.alexandermora.modsync.server.profile

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Evidence
import de.alexandermora.modsync.domain.PersonalProfile
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.requireState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val MAX_PROFILE_ARTIFACTS = 200

@Service
class ProfileService(
    private val profiles: PersonalProfileRepository,
    private val mods: PersonalProfileModRepository,
    private val modrinth: Modrinth,
    private val audit: AuditService,
) {
    private val logger = LoggerFactory.getLogger(ProfileService::class.java)

    fun list(user: String): List<PersonalProfile> =
        profiles.findByUserIdOrderByName(user).map { wire.decodeFromString<PersonalProfile>(it.payload) }

    @Transactional fun save(user: String, id: String, requested: PersonalProfile): PersonalProfile {
        require(requested.id == uuid(id) && requested.name.length in 1..200 && requested.artifacts.size <= MAX_PROFILE_ARTIFACTS)
        val existing = profiles.lockById(id)
        requireState(existing == null || existing.userId == user, "Profile ownership mismatch")
        val profile = requested.copy(artifacts = clientOnlyArtifacts(requested))
        val entity = existing ?: PersonalProfileEntity(id = id, userId = user)
        entity.name = profile.name
        entity.payload = wire.encodeToString(profile)
        profiles.saveAndFlush(entity)
        mods.deleteByProfile(id)
        profile.artifacts.forEach { mods.saveAndFlush(PersonalProfileMod(id, it.projectId, wire.encodeToString(it))) }
        audit.record(user, "profile-saved", id)
        return profile
    }

    private fun clientOnlyArtifacts(profile: PersonalProfile) = try {
        profile.artifacts.map { requested ->
            val serverSide = modrinth.project(requested.projectId)["server_side"]?.jsonPrimitive?.contentOrNull
            requireState(serverSide == "unsupported", "Personal profile requires confirmed client-only Modrinth projects")
            modrinth.artifact(requested.sha512, Classification.CLIENT_ONLY, listOf(Evidence("modrinth", "Server environment unsupported")))
                .also { requireState(it.projectId == requested.projectId, "Project/hash mismatch") }
        }
    } catch (error: Exception) {
        logger.warn("profile metadata validation failed", error)
        throw error
    }
}
