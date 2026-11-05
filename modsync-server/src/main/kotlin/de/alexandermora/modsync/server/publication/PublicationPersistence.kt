package de.alexandermora.modsync.server.publication

import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.protocol.PublicationRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** Plain JDBC persistence of per-server publication policy and publication history. */
@Repository
class PublicationPersistence(private val db: JdbcTemplate) {
    /**
     * Reads a server's publication policy.
     *
     * @param serverId Server UUID.
     * @return The policy, or null when the server does not exist.
     */
    fun policy(serverId: String): PublicationPolicy? =
        db.queryForList("SELECT publication_policy FROM minecraft_servers WHERE id = ?", String::class.java, serverId)
            .firstOrNull()?.let(PublicationPolicy::valueOf)

    /**
     * Stores a server's publication policy.
     *
     * @param serverId Server UUID.
     * @param policy New policy.
     * @return True when the server exists.
     */
    fun setPolicy(serverId: String, policy: PublicationPolicy): Boolean =
        db.update("UPDATE minecraft_servers SET publication_policy = ? WHERE id = ?", policy.name, serverId) > 0

    /**
     * Appends a publication attempt to the history.
     *
     * @param serverId Server UUID.
     * @param scanId Snapshot the attempt evaluated.
     * @param policy Policy in force.
     * @param outcome Outcome name such as PUBLISHED or REVIEW_REQUIRED.
     * @param revision Published revision, if any.
     * @param detail Explanation, truncated to the column size.
     * @param actor Actor that triggered the attempt.
     */
    fun record(serverId: String, scanId: String, policy: PublicationPolicy, outcome: String, revision: Long?, detail: String, actor: String) {
        db.update(
            "INSERT INTO publication_history (server_id, scan_id, policy, outcome, revision, detail, actor, created_at) VALUES (?,?,?,?,?,?,?,?)",
            serverId, scanId, policy.name, outcome, revision, detail.take(1000), actor, Timestamp.from(Instant.now())
        )
    }

    /**
     * Lists recent publication attempts, newest first.
     *
     * @param serverId Server UUID.
     * @param limit Maximum number of records.
     * @return Records in descending order.
     */
    fun history(serverId: String, limit: Int): List<PublicationRecord> = db.query(
        "SELECT id, scan_id, policy, outcome, revision, detail, actor, created_at FROM publication_history WHERE server_id = ? ORDER BY id DESC LIMIT ?",
        { rs, _ ->
            PublicationRecord(
                rs.getLong(1), rs.getString(2), PublicationPolicy.valueOf(rs.getString(3)), rs.getString(4),
                rs.getObject(5) as Long?, rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant().toString()
            )
        },
        serverId, limit
    )
}
