package de.alexandermora.modsync.server.clientinventory

import de.alexandermora.modsync.domain.safeFileName
import de.alexandermora.modsync.domain.uuid
import de.alexandermora.modsync.domain.validHash
import de.alexandermora.modsync.protocol.ClientInventoryAck
import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.notFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val INSTANCE_NAME = Regex("[A-Za-z0-9_-]{1,80}")
private val PLATFORM_TOKEN = Regex("[A-Za-z0-9._+-]{1,80}")
private const val MAX_MODS = 2000
private const val MAX_ID_LENGTH = 128

/**
 * Accepts authenticated, revisioned and idempotent client inventory reports.
 *
 * @property persistence Inventory persistence.
 * @property audit Audit log writer.
 */
@Service
class ClientInventoryService(
    private val persistence: ClientInventoryPersistence,
    private val audit: AuditService
) {
    /**
     * Stores the inventory of one client instance.
     *
     * Reports carry a per-instance sequence: a higher sequence replaces the stored inventory, an equal
     * sequence is an idempotent retry and changes nothing, and a lower sequence is rejected as stale
     * without overwriting newer data.
     *
     * @param deviceId Authenticated device UUID taken from the credential, never from the payload.
     * @param report Report to validate and store.
     * @return Acknowledgement stating whether this report became the current inventory.
     * @throws IllegalArgumentException when the payload is malformed.
     * @throws org.springframework.web.server.ResponseStatusException with 404 when the instance belongs to another
     * device or the referenced server does not exist.
     */
    @Transactional fun report(deviceId: String, report: ClientInventoryReport): ClientInventoryAck {
        val device = uuid(deviceId)
        validate(report)
        val owner = persistence.registerAndLock(report.instanceId, device, report.instanceName)
        if (owner != device) notFound()
        report.serverId?.let { if (!persistence.serverExists(it)) notFound() }
        val current = persistence.sequence(report.instanceId)
        val now = Instant.now()
        if (current != null && report.sequence <= current) {
            return ClientInventoryAck(report.instanceId, current, accepted = report.sequence == current, verifiedAt = now.toString())
        }
        persistence.replace(report, now)
        audit.record(device, "client-inventory-reported", report.instanceId)
        return ClientInventoryAck(report.instanceId, report.sequence, accepted = true, verifiedAt = now.toString())
    }

    /**
     * Validates identity, platform and mod entries of a report.
     *
     * @param report Report received from a client.
     * @throws IllegalArgumentException when any field violates the protocol limits.
     */
    private fun validate(report: ClientInventoryReport) {
        uuid(report.instanceId)
        report.serverId?.let { uuid(it) }
        report.profileId?.let { uuid(it) }
        require(report.instanceName.matches(INSTANCE_NAME))
        require(report.minecraftVersion.matches(PLATFORM_TOKEN) && report.loader.matches(PLATFORM_TOKEN))
        require(report.loaderVersion?.matches(PLATFORM_TOKEN) ?: true)
        require(report.sequence >= 0 && (report.appliedRevision ?: 0) >= 0)
        require(
            report.mods.size <= MAX_MODS && report.mods
                .map { it.sha512 }
                .toSet()
                .size == report.mods.size
        )
        report.mods.forEach { mod ->
            require(validHash(mod.sha512) && safeFileName(mod.fileName))
            require((mod.projectId?.length ?: 0) <= MAX_ID_LENGTH && (mod.versionId?.length ?: 0) <= MAX_ID_LENGTH)
        }
    }
}
