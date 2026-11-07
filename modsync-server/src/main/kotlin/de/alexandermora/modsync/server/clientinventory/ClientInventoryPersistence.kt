package de.alexandermora.modsync.server.clientinventory

import de.alexandermora.modsync.domain.ClientActivity
import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.ClientModEntry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/**
 * Latest stored metadata of a client instance.
 *
 * @property instanceId Instance UUID.
 * @property name Instance display name.
 * @property minecraftVersion Reported Minecraft version.
 * @property loader Reported loader name.
 * @property activity Reported client activity.
 * @property appliedRevision Manifest revision applied by the instance, or null.
 * @property verifiedAt Server time at which the inventory was accepted.
 */
data class StoredInstance(
    val instanceId: String,
    val name: String,
    val minecraftVersion: String,
    val loader: String,
    val activity: ClientActivity,
    val appliedRevision: Long?,
    val verifiedAt: Instant
)

/**
 * Plain JDBC persistence for registered client instances and their verified mod inventories.
 *
 * @property db JDBC access.
 */
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
     * Loads every instance that selected the given server together with its latest inventory metadata.
     *
     * @param serverId Server UUID.
     * @return One row per instance; mods are loaded separately by [modsForServer] to avoid per-row queries.
     */
    fun instancesForServer(serverId: String): List<StoredInstance> =
        db.query(
            "SELECT i.instance_id, c.name, i.minecraft_version, i.loader, i.activity, i.applied_revision, i.verified_at " +
                "FROM client_instance_inventory i JOIN client_instances c ON c.id = i.instance_id WHERE i.server_id = ? ORDER BY c.name, i.instance_id",
            { rs, _ ->
                StoredInstance(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    ClientActivity.valueOf(rs.getString(5)),
                    rs.getObject(6) as Long?,
                    rs.getTimestamp(7).toInstant()
                )
            },
            serverId
        )

    /**
     * Loads the installed mods of all instances that selected the given server in a single query.
     *
     * @param serverId Server UUID.
     * @return Installed mods grouped by instance UUID.
     */
    fun modsForServer(serverId: String): Map<String, List<ClientModEntry>> {
        val rows =
            db.query(
                "SELECT m.instance_id, m.sha512, m.file_name, m.project_id, m.version_id, m.managed FROM client_instance_mods m " +
                    "JOIN client_instance_inventory i ON i.instance_id = m.instance_id WHERE i.server_id = ?",
                { rs, _ -> rs.getString(1) to ClientModEntry(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6)) },
                serverId
            )
        return rows.groupBy({ it.first }, { it.second })
    }

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
            "INSERT INTO client_instance_inventory (instance_id, server_id, minecraft_version, loader, loader_version, " +
                "sync_policy, activity, applied_revision, sequence, mod_count, verified_at, profile_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            report.instanceId,
            report.serverId,
            report.minecraftVersion,
            report.loader,
            report.loaderVersion,
            report.syncPolicy.name,
            report.activity.name,
            report.appliedRevision,
            report.sequence,
            report.mods.size,
            Timestamp.from(verifiedAt),
            report.profileId
        )
        db.batchUpdate(
            "INSERT INTO client_instance_mods (instance_id, sha512, file_name, project_id, version_id, managed) VALUES (?,?,?,?,?,?)",
            report.mods.map { mod: ClientModEntry ->
                arrayOf<Any?>(report.instanceId, mod.sha512, mod.fileName, mod.projectId, mod.versionId, mod.managed)
            }
        )
    }
}
