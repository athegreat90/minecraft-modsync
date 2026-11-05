package de.alexandermora.modsync.server.clientinventory

import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.ClientModEntry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** Plain JDBC persistence for registered client instances and their verified mod inventories. */
@Repository
class ClientInventoryPersistence(private val db: JdbcTemplate) {
    /**
     * Registers the instance for the device when it is unknown and locks its row for the current transaction.
     *
     * @param instanceId Instance UUID.
     * @param deviceId Device UUID that is reporting.
     * @param name Instance display name; stored only on first registration.
     * @return The UUID of the device that owns the instance, which may differ from [deviceId].
     */
    fun registerAndLock(instanceId: String, deviceId: String, name: String): String {
        db.update("INSERT IGNORE INTO client_instances (id, device_id, name) VALUES (?, ?, ?)", instanceId, deviceId, name)
        return db.queryForObject("SELECT device_id FROM client_instances WHERE id = ? FOR UPDATE", String::class.java, instanceId)!!
    }

    /**
     * Reads the last accepted sequence number of an instance.
     *
     * @param instanceId Instance UUID.
     * @return The stored sequence, or null when no inventory was ever accepted.
     */
    fun sequence(instanceId: String): Long? =
        db.queryForList("SELECT sequence FROM client_instance_inventory WHERE instance_id = ?", Long::class.java, instanceId).firstOrNull()

    /**
     * Reports whether a Minecraft server with the supplied id exists.
     *
     * @param serverId Server UUID.
     * @return True when the server is registered.
     */
    fun serverExists(serverId: String): Boolean =
        db.queryForObject("SELECT COUNT(*) FROM minecraft_servers WHERE id = ?", Int::class.java, serverId)!! > 0

    /**
     * Replaces the stored inventory of an instance with the report content.
     *
     * Must run inside the transaction that holds the instance lock obtained by [registerAndLock].
     *
     * @param report Validated report.
     * @param verifiedAt Server time at which the inventory was accepted.
     */
    fun replace(report: ClientInventoryReport, verifiedAt: Instant) {
        db.update("DELETE FROM client_instance_mods WHERE instance_id = ?", report.instanceId)
        db.update("DELETE FROM client_instance_inventory WHERE instance_id = ?", report.instanceId)
        db.update(
            "INSERT INTO client_instance_inventory (instance_id, server_id, minecraft_version, loader, loader_version, sync_policy, activity, applied_revision, sequence, mod_count, verified_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            report.instanceId, report.serverId, report.minecraftVersion, report.loader, report.loaderVersion,
            report.syncPolicy.name, report.activity.name, report.appliedRevision, report.sequence, report.mods.size, Timestamp.from(verifiedAt)
        )
        db.batchUpdate(
            "INSERT INTO client_instance_mods (instance_id, sha512, file_name, project_id, version_id, managed) VALUES (?,?,?,?,?,?)",
            report.mods.map { mod: ClientModEntry ->
                arrayOf<Any?>(report.instanceId, mod.sha512, mod.fileName, mod.projectId, mod.versionId, mod.managed)
            }
        )
    }
}
