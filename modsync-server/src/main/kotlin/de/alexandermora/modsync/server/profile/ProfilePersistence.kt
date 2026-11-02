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

@Entity @Table(name = "personal_profiles")
class PersonalProfileEntity(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) var id: String = "",
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", nullable = false, length = 36) var userId: String = "",
    @Column(nullable = false, length = 200) var name: String = "",
    @Column(nullable = false, length = Length.LONG32) var payload: String = "",
)

class ProfileModId(var profileId: String = "", var projectId: String = "") : Serializable {
    override fun equals(other: Any?) = other is ProfileModId && other.profileId == profileId && other.projectId == projectId
    override fun hashCode() = 31 * profileId.hashCode() + projectId.hashCode()
}

@Entity @Table(name = "personal_profile_mods") @IdClass(ProfileModId::class)
class PersonalProfileMod(
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "profile_id", length = 36) var profileId: String = "",
    @Id @Column(name = "project_id", length = 128) var projectId: String = "",
    @Column(nullable = false, length = Length.LONG32) var payload: String = "",
)

@Repository interface PersonalProfileRepository : JpaRepository<PersonalProfileEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PersonalProfileEntity p where p.id = :id")
    fun lockById(id: String): PersonalProfileEntity?

    fun findByUserIdOrderByName(userId: String): List<PersonalProfileEntity>
}

@Repository interface PersonalProfileModRepository : JpaRepository<PersonalProfileMod, ProfileModId> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PersonalProfileMod m where m.profileId = :profile")
    fun deleteByProfile(profile: String): Int
}
