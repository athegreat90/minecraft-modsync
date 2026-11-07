package de.alexandermora.modsync.server.profile

import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Evidence
import de.alexandermora.modsync.domain.PersonalProfile
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.requireState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val MAX_PROFILE_ARTIFACTS = 200

/**
 * Stores and validates users' personal client-only profiles.
 *
 * @property profiles Profile repository.
 * @property mods Profile artifact repository.
 * @property modrinth Modrinth client used to confirm client-only projects.
 * @property audit Audit log writer.
 */
@Service
class ProfileService(
    private val profiles: PersonalProfileRepository,
    private val mods: PersonalProfileModRepository,
    private val modrinth: Modrinth,
    private val audit: AuditService
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Lists the user's personal profiles.
     *
     * @param user Owner UUID.
     * @return Profiles sorted by name.
     */
    fun list(user: String): List<PersonalProfile> =
        profiles.findByUserIdOrderByName(user).map { wire.decodeFromString<PersonalProfile>(it.payload) }

    /**
     * Creates or replaces one of the user's profiles.
     *
     * Every artifact must be a Modrinth project confirmed client-only and match its hash, so profiles can never
     * smuggle server-side mods. The profile row is locked and owned by one user; its artifact rows are rewritten
     * within the same transaction.
     *
     * @param user Owner UUID.
     * @param id Profile UUID, which must match the id in [requested].
     * @param requested Profile as submitted, with at most 200 artifacts.
     * @return The profile as stored, with artifacts resolved from Modrinth.
     */
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

    /**
     * Resolves every requested artifact against Modrinth and enforces the client-only rule.
     *
     * @param profile Profile whose artifacts are verified.
     * @return Artifacts rebuilt from Modrinth metadata; aborts with 409 on non-client-only projects or hash mismatches.
     */
    private fun clientOnlyArtifacts(profile: PersonalProfile) =
        runCatching {
            profile.artifacts.map { requested ->
                val serverSide = modrinth.project(requested.projectId)["server_side"]?.jsonPrimitive?.contentOrNull
                requireState(serverSide == "unsupported", "Personal profile requires confirmed client-only Modrinth projects")
                modrinth
                    .artifact(requested.sha512, Classification.CLIENT_ONLY, listOf(Evidence("modrinth", "Server environment unsupported")))
                    .also { requireState(it.projectId == requested.projectId, "Project/hash mismatch") }
            }
        }.onFailure { logger.warn(it) { "profile metadata validation failed" } }.getOrThrow()
}
