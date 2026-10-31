package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.sql.Timestamp

@Service
class Manifests(private val store: Store) {
    private val modrinth = Modrinth()
    fun validate(serverId: String, request: PublishRequest): Manifest {
        val server = store.one("SELECT * FROM minecraft_servers WHERE id=?", uuid(serverId))
        requireState(server["snapshot_id"] == request.scanId, "Publish requires latest committed snapshot")
        val row = store.one("SELECT * FROM inventory_snapshots WHERE id=? AND committed_at IS NOT NULL", uuid(request.scanId))
        val entries = store.entries(request.scanId)
        val overrides = store.rows("SELECT * FROM classification_overrides WHERE server_id=?", serverId).associateBy { it["sha512"].toString() }
        requireState(entries.all { overrides[it.sha512] != null }, "Every inventory artifact needs an explicit classification approval")
        val artifacts = request.artifacts.map { artifact ->
            require(validHash(artifact.sha512) && safeFileName(artifact.fileName) && artifact.classification != Classification.UNKNOWN)
            val scanned = entries.find { it.sha512 == artifact.sha512 }
            val classification = scanned?.let { Classification.valueOf(overrides.getValue(it.sha512)["classification"].toString()) } ?: artifact.classification
            val evidence = listOf(Evidence("administrator", scanned?.let { overrides.getValue(it.sha512)["reason"].toString() } ?: "Explicit dependency approval"))
            if (artifact.url != null) modrinth.artifact(artifact.sha512, classification, evidence)
            else {
                requireState(scanned != null && scanned.size == artifact.size, "Manual artifact must match scanned inventory")
                artifact.copy(classification = classification, evidence = evidence)
            }
        }
        requireState(entries.all { e -> artifacts.any { it.sha512 == e.sha512 } }, "Manifest must account for every scanned artifact")
        requireState(artifacts.map { it.projectId }.distinct().size == artifacts.size, "Duplicate project pins")
        val manifest = Manifest(serverId, (server["revision"] as Number).toLong()+1, server["minecraft_version"].toString(), server["loader"].toString(), server["loader_version"]?.toString(), artifacts, Instant.now().toString(), row["digest"].toString(), (server["policy_revision"] as Number).toLong())
        val validation = Resolver().plan(manifest, emptyList(), artifacts.map { ModEntry(it.fileName, it.sha512, it.size) }, emptyMap(), includeOptional=true)
        requireState(!validation.blocked, "Manifest has unresolved dependencies or compatibility conflicts")
        return manifest
    }
    @Transactional fun publish(actor: String, candidate: Manifest, scanId: String): Manifest {
        val server = store.one("SELECT * FROM minecraft_servers WHERE id=? FOR UPDATE", candidate.serverId)
        requireState((server["policy_revision"] as Number).toLong() == candidate.policyRevision, "Classification policy changed; preview again")
        requireState(server["snapshot_id"] == scanId && (server["revision"] as Number).toLong()+1 == candidate.revision, "Inventory or manifest changed; preview again")
        store.db.update("INSERT INTO server_manifests(server_id,latest_revision) VALUES (?,?) ON DUPLICATE KEY UPDATE latest_revision=VALUES(latest_revision)", candidate.serverId, candidate.revision)
        store.db.update("INSERT INTO manifest_revisions(server_id,revision,payload,created_at) VALUES (?,?,?,?)", candidate.serverId, candidate.revision, wire.encodeToString(candidate), Timestamp.from(Instant.parse(candidate.createdAt)))
        store.db.update("UPDATE minecraft_servers SET revision=? WHERE id=?", candidate.revision, candidate.serverId)
        store.audit(actor, "manifest-published", "${candidate.serverId}:${candidate.revision}")
        return candidate
    }
}
