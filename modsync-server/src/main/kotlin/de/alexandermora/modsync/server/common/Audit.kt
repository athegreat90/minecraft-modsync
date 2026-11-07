package de.alexandermora.modsync.server.common

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.MDC
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Audit trail row.
 *
 * @property id Generated identifier.
 * @property actor User, agent or system actor.
 * @property action Action name.
 * @property target Affected object.
 * @property correlationId Request correlation id, when available.
 * @property createdAt Event time.
 */
@Entity
@Table(name = "audit_events")
class AuditEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false, length = 100) var actor: String = "",
    @Column(nullable = false, length = 100) var action: String = "",
    @Column(nullable = false, length = 200) var target: String = "",
    @Column(name = "correlation_id", length = 36) var correlationId: String? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
)

/** Repository of [AuditEvent] rows. */
@Repository
interface AuditEventRepository : JpaRepository<AuditEvent, Long>

/**
 * Writes audit events.
 *
 * @property events Audit repository.
 */
@Service
class AuditService(private val events: AuditEventRepository) {
    /**
     * Appends an audit event stamped with the current request's correlation id; participates in the caller's transaction.
     *
     * @param actor Actor performing the action.
     * @param action Action name.
     * @param target Affected object.
     */
    fun record(actor: String, action: String, target: String) {
        events.saveAndFlush(AuditEvent(actor = actor, action = action, target = target, correlationId = MDC.get("correlationId")))
    }
}
