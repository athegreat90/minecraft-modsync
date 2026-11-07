package de.alexandermora.modsync.server.profile

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.Length
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.io.Serializable

/**
 * Personal profile row owned by one user.
 *
 * @property id Profile UUID.
 * @property userId Owning user UUID.
 * @property name Display name.
 * @property payload Serialized profile.
 */
@Entity
@Table(name = "personal_profiles")
class PersonalProfileEntity(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", nullable = false, length = 36) var userId: String = "",
    @Column(nullable = false, length = 200) var name: String = "",
    @Column(nullable = false, length = Length.LONG32) var payload: String = ""
)

/**
 * Composite key of [PersonalProfileMod].
 *
 * @property profileId Profile UUID.
 * @property projectId Modrinth project id.
 */
class ProfileModId(var profileId: String = "", var projectId: String = "") : Serializable {
    override fun equals(other: Any?) = other is ProfileModId && other.profileId == profileId && other.projectId == projectId

    override fun hashCode() = 31 * profileId.hashCode() + projectId.hashCode()
}

/**
 * Artifact row of a personal profile, kept to authorize downloads of client-only artifacts.
 *
 * @property profileId Profile UUID.
 * @property projectId Modrinth project id.
 * @property payload Serialized artifact.
 */
@Entity
@Table(name = "personal_profile_mods")
@IdClass(ProfileModId::class)
class PersonalProfileMod(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "profile_id", length = 36) var profileId: String = "",
    @Id @Column(name = "project_id", length = 128) var projectId: String = "",
    @Column(nullable = false, length = Length.LONG32) var payload: String = ""
)

/** Repository of [PersonalProfileEntity] rows. */
@Repository interface PersonalProfileRepository : JpaRepository<PersonalProfileEntity, String> {
    /**
     * Row-locks a profile so concurrent saves of the same profile serialize.
     *
     * @param id Profile UUID.
     * @return The locked profile, or null.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PersonalProfileEntity p where p.id = :id")
    fun lockById(id: String): PersonalProfileEntity?

    /**
     * Lists a user's profiles sorted by name.
     *
     * @param userId Owner UUID.
     * @return The user's profiles.
     */
    fun findByUserIdOrderByName(userId: String): List<PersonalProfileEntity>
}

/** Repository of [PersonalProfileMod] rows. */
@Repository interface PersonalProfileModRepository : JpaRepository<PersonalProfileMod, ProfileModId> {
    /**
     * Deletes all artifact rows of a profile ahead of re-inserting its new artifact set.
     *
     * @param profile Profile UUID.
     * @return Number of rows removed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PersonalProfileMod m where m.profileId = :profile")
    fun deleteByProfile(profile: String): Int
}
