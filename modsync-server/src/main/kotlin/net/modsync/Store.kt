package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.time.Instant
import java.sql.Timestamp
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

fun reject(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
fun requireState(ok: Boolean, message: String) { if (!ok) reject(message) }
@Service
class Store(val db: JdbcTemplate, private val passwords: Argon2PasswordEncoder) {
    fun rows(sql: String, vararg args: Any?): List<Map<String, Any?>> = db.queryForList(sql, *args)
    fun one(sql: String, vararg args: Any?) = rows(sql, *args).singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    fun audit(actor: String, action: String, target: String) { db.update("INSERT INTO audit_events(actor,action,target,correlation_id,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(6))", actor, action, target, org.slf4j.MDC.get("correlationId")) }
    private fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun credential(subject: String, role: String, device: String? = null): Credentials {
        val token = secret(); val expires = Instant.now().plusSeconds(if (role == "AGENT") 30L*86400 else 3600)
        db.update("INSERT INTO credentials(token_hash,subject_id,role,device_id,expires_at) VALUES (?,?,?,?,?)", sha512(token.toByteArray()), subject, role, device, Timestamp.from(expires))
        val refresh = if (role == "AGENT") null else secret().also {
            db.update("INSERT INTO credentials(token_hash,subject_id,role,device_id,expires_at,kind) VALUES (?,?,?,?,?,'refresh')", sha512(it.toByteArray()), subject, role, device, Timestamp.from(Instant.now().plusSeconds(30L*86400)))
        }
        return Credentials(subject, token, expires.toString(), refresh)
    }
    @Transactional fun refresh(token: String): Credentials {
        require(token.length <= 128)
        val row = rows("SELECT * FROM credentials WHERE token_hash=? AND kind='refresh' AND expires_at>CURRENT_TIMESTAMP(6) FOR UPDATE", sha512(token.toByteArray())).singleOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val device = row["device_id"].toString()
        one("SELECT * FROM client_devices WHERE id=? AND revoked=FALSE", device)
        db.update("DELETE FROM credentials WHERE device_id=?", device)
        audit(row["subject_id"].toString(), "session-refreshed", device)
        return credential(row["subject_id"].toString(), row["role"].toString(), device)
    }
    @Transactional fun login(request: Login): Credentials {
        require(request.protocol == PROTOCOL && request.username.length <= 100 && request.password.length <= 256)
        val user = rows("SELECT * FROM users WHERE username=?", request.username).singleOrNull()
        // Always perform the expensive comparison to reduce account enumeration timing differences.
        val encoded = user?.get("password_hash")?.toString() ?: dummyPassword
        if (!passwords.matches(request.password, encoded) || user == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val id = uuid(request.deviceId); val uid = user["id"].toString()
        val device = rows("SELECT * FROM client_devices WHERE id=? FOR UPDATE", id).singleOrNull()
        if (device == null) db.update("INSERT INTO client_devices(id,user_id) VALUES (?,?)", id, uid)
        else requireState(device["user_id"] == uid && device["revoked"].toString() in setOf("false", "0"), "Device belongs to another user or is revoked")
        audit(uid, "login", id)
        return credential(uid, user["role"].toString(), id)
    }
    private val dummyPassword by lazy { passwords.encode(UUID.randomUUID().toString()) }
    @Transactional fun enrollment(actor: String, seconds: Long): EnrollmentToken {
        require(seconds in 60..3600)
        val token = secret(); val expires = Instant.now().plusSeconds(seconds)
        db.update("INSERT INTO agent_enrollments(token_hash,expires_at) VALUES (?,?)", sha512(token.toByteArray()), Timestamp.from(expires))
        audit(actor, "enrollment-created", "agent")
        return EnrollmentToken(token, expires.toString())
    }
    @Transactional fun enroll(request: Enrollment): Credentials {
        require(request.protocol == PROTOCOL && request.token.length <= 128)
        val id = uuid(request.agentId)
        val token = rows("SELECT * FROM agent_enrollments WHERE token_hash=? AND expires_at>CURRENT_TIMESTAMP(6) AND revoked=FALSE FOR UPDATE", sha512(request.token.toByteArray())).singleOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (token["used_by"] != null) {
            requireState(token["used_by"] == id, "Enrollment already consumed by another agent")
            one("SELECT id FROM scanner_agents WHERE id=? AND revoked=FALSE", id)
            db.update("DELETE FROM credentials WHERE subject_id=?", id)
            audit(id, "enrollment-delivery-retried", id)
            return credential(id, "AGENT")
        }
        requireState(rows("SELECT id FROM scanner_agents WHERE id=?", id).isEmpty(), "Agent already registered")
        val host = UUID.randomUUID().toString()
        db.update("INSERT INTO minecraft_hosts(id,name) VALUES (?,?)", host, "Host $host")
        db.update("INSERT INTO scanner_agents(id,host_id,created_at) VALUES (?,?,CURRENT_TIMESTAMP(6))", id, host)
        db.update("UPDATE agent_enrollments SET used_by=? WHERE token_hash=?", id, token["token_hash"])
        audit(id, "agent-enrolled", host)
        return credential(id, "AGENT")
    }
    @Transactional fun assign(actor: String, server: String, transfer: Transfer): Assignment {
        require(transfer.mapping.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        one("SELECT * FROM minecraft_servers WHERE id=? FOR UPDATE", uuid(server))
        one("SELECT * FROM scanner_agents WHERE id=? AND revoked=FALSE", uuid(transfer.agentId))
        db.update("UPDATE minecraft_servers SET agent_id=?,mapping=?,generation=generation+1,sequence=0 WHERE id=?", transfer.agentId, transfer.mapping, server)
        val row = one("SELECT generation FROM minecraft_servers WHERE id=?", server)
        audit(actor, "assignment-transferred", server)
        return Assignment(server, transfer.agentId, (row["generation"] as Number).toLong(), transfer.mapping)
    }
    fun ownership(agent: String, begin: SnapshotBegin) {
        val row = one("SELECT * FROM minecraft_servers WHERE id=? FOR UPDATE", begin.serverId)
        requireState(row["agent_id"] == agent && (row["generation"] as Number).toLong() == begin.generation, "Agent assignment is no longer authoritative")
    }
    @Transactional fun begin(agent: String, begin: SnapshotBegin): SnapshotBegin {
        uuid(begin.scanId); uuid(begin.serverId)
        require(begin.protocol == PROTOCOL && begin.count in 0..10000 && begin.sequence > 0 && validHash(begin.digest))
        require(Instant.parse(begin.scannedAt).isBefore(Instant.now().plusSeconds(300)))
        ownership(agent, begin)
        val old = rows("SELECT payload FROM inventory_snapshots WHERE id=?", begin.scanId).singleOrNull()
        if (old != null) { requireState(wire.decodeFromString<SnapshotBegin>(old["payload"].toString()) == begin, "Conflicting scan retry"); return begin }
        val latest = one("SELECT sequence FROM minecraft_servers WHERE id=?", begin.serverId)
        requireState(begin.sequence > (latest["sequence"] as Number).toLong(), "Outdated scan")
        db.update("INSERT INTO inventory_snapshots(id,server_id,agent_id,generation,sequence,digest,entry_count,scanned_at,payload) VALUES (?,?,?,?,?,?,?,?,?)",
            begin.scanId, begin.serverId, agent, begin.generation, begin.sequence, begin.digest, begin.count, Timestamp.from(Instant.parse(begin.scannedAt)), wire.encodeToString(begin))
        return begin
    }
    @Transactional fun batch(agent: String, scan: String, batch: SnapshotBatch) {
        val initial = one("SELECT * FROM inventory_snapshots WHERE id=?", uuid(scan))
        val begin = wire.decodeFromString<SnapshotBegin>(initial["payload"].toString()); ownership(agent, begin)
        val row = one("SELECT * FROM inventory_snapshots WHERE id=? FOR UPDATE", scan)
        requireState(row["agent_id"] == agent, "Agent mismatch")
        require(batch.index in 0..9999 && batch.entries.size in 1..100 && batch.entries.all { safeFileName(it.fileName) && validHash(it.sha512) && it.size in 0..536870912 })
        val payload = wire.encodeToString(batch)
        val old = rows("SELECT payload FROM inventory_entries WHERE snapshot_id=? AND batch_index=?", scan, batch.index).singleOrNull()
        if (old != null) { requireState(old["payload"] == payload, "Conflicting batch retry"); return }
        requireState(row["committed_at"] == null, "Snapshot already committed")
        val total = db.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM inventory_entries WHERE snapshot_id=?", Long::class.java, scan) ?: 0
        require(total + payload.toByteArray().size < 6 * 1024 * 1024) { "Snapshot payload exceeds 6 MiB" }
        db.update("INSERT INTO inventory_entries(snapshot_id,batch_index,payload) VALUES (?,?,?)", scan, batch.index, payload)
    }
    fun entries(scan: String): List<ModEntry> {
        val batches = rows("SELECT payload FROM inventory_entries WHERE snapshot_id=? ORDER BY batch_index", scan).map { wire.decodeFromString<SnapshotBatch>(it["payload"].toString()) }
        requireState(batches.map { it.index } == batches.indices.toList(), "Missing snapshot batches")
        return batches.flatMap { it.entries }
    }
    @Transactional fun commit(agent: String, scan: String): SnapshotAck {
        // Lock server before snapshot, matching transfer/begin lock order.
        val initial = one("SELECT * FROM inventory_snapshots WHERE id=?", uuid(scan))
        val begin = wire.decodeFromString<SnapshotBegin>(initial["payload"].toString()); ownership(agent, begin)
        val row = one("SELECT * FROM inventory_snapshots WHERE id=? FOR UPDATE", scan)
        requireState(row["agent_id"] == agent, "Agent mismatch")
        row["committed_at"]?.let { return SnapshotAck(scan, begin.sequence, begin.digest, (it as Timestamp).toInstant().toString()) }
        val entries = entries(scan)
        requireState(entries.size == begin.count && entries.map { it.fileName.lowercase() }.distinct().size == entries.size && inventoryDigest(entries) == begin.digest, "Incomplete or invalid inventory")
        val server = one("SELECT * FROM minecraft_servers WHERE id=?", begin.serverId)
        requireState(begin.sequence > (server["sequence"] as Number).toLong(), "Outdated inventory")
        val committed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        db.update("UPDATE inventory_snapshots SET committed_at=? WHERE id=?", Timestamp.from(committed), scan)
        db.update("UPDATE minecraft_servers SET snapshot_id=?,sequence=?,inventory_at=? WHERE id=?", scan, begin.sequence, Timestamp.from(committed), begin.serverId)
        audit(agent, "inventory-committed", scan)
        return SnapshotAck(scan, begin.sequence, begin.digest, committed.toString())
    }
}
