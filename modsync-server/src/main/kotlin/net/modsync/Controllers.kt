package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID

inline fun <reified T> json(value: T): ResponseEntity<String> = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(wire.encodeToString(value))
fun actor(): String = SecurityContextHolder.getContext().authentication?.name
    ?: throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED)
@RestController
@RequestMapping("/api/v1")
class Controllers(private val store: Store, private val manifests: Manifests, private val passwords: Argon2PasswordEncoder) {
    @GetMapping("/openapi.json") fun openapi(): ResponseEntity<String> = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(org.springframework.core.io.ClassPathResource("openapi.json").inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
    @GetMapping("/info") fun info() = json(Capabilities(store.one("SELECT value FROM system_settings WHERE name='control_plane_id'")["value"].toString()))
    @PostMapping("/auth/login") fun login(@RequestBody body: String) = json(store.login(wire.decodeFromString(body)))
    @PostMapping("/auth/refresh") fun refresh(@RequestBody body: String) = json(store.refresh(wire.decodeFromString<RefreshRequest>(body).token))
    @PostMapping("/enroll") fun enroll(@RequestBody body: String) = json(store.enroll(wire.decodeFromString(body)))
    @PostMapping("/admin/enrollments") fun enrollment(@RequestBody body: String) = json(store.enrollment(actor(), wire.decodeFromString<EnrollmentRequest>(body).ttlSeconds))
    @GetMapping("/admin/agents") fun agents() = json(store.rows("SELECT a.id,h.seen_at FROM scanner_agents a LEFT JOIN agent_heartbeats h ON a.id=h.agent_id WHERE a.revoked=FALSE").map {
        mapOf("id" to it["id"].toString(), "heartbeatAt" to ((it["seen_at"] as? Timestamp)?.toInstant()?.toString() ?: "never"), "stale" to ((it["seen_at"] as? Timestamp)?.toInstant()?.isBefore(Instant.now().minusSeconds(900)) ?: true).toString())
    })
    @GetMapping("/admin/servers/{id}/identified") fun identified(@PathVariable id: String): ResponseEntity<String> {
        val scan=store.one("SELECT snapshot_id FROM minecraft_servers WHERE id=?",uuid(id))["snapshot_id"]?.toString() ?: reject("No accepted inventory")
        val identified=Modrinth().identify(store.entries(scan).map { it.sha512 })
        return json(identified)
    }
    @GetMapping("/mods/{project}/updates") fun updates(@PathVariable project: String, @RequestParam minecraft: String, @RequestParam loader: String) = json(Modrinth().compatibleVersions(project,minecraft,loader))
    @PostMapping("/admin/enrollments/revoke") @Transactional fun revokeEnrollments(): ResponseEntity<String> {
        store.db.update("UPDATE agent_enrollments SET revoked=TRUE WHERE used_by IS NULL"); store.audit(actor(), "enrollments-revoked", "pending"); return json(true)
    }
    @PostMapping("/admin/users") @Transactional fun user(@RequestBody body: String): ResponseEntity<String> {
        val user = wire.decodeFromString<UserRegistration>(body); require(user.username.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && user.password.length in 16..256)
        val id = UUID.randomUUID().toString(); store.db.update("INSERT INTO users(id,username,password_hash,role) VALUES (?,?,?,'CLIENT')", id, user.username, passwords.encode(user.password))
        store.audit(actor(), "user-created", id); return json(id)
    }
    @PostMapping("/admin/servers") @Transactional fun server(@RequestBody body: String): ResponseEntity<String> {
        val request = wire.decodeFromString<ServerRegistration>(body); require(request.name.length in 1..200 && request.minecraftVersion.length in 1..80 && request.loader.length in 1..80)
        val id = UUID.randomUUID().toString()
        store.db.update("INSERT INTO minecraft_servers(id,name,minecraft_version,loader,loader_version) VALUES (?,?,?,?,?)", id, request.name, request.minecraftVersion, request.loader, request.loaderVersion)
        store.audit(actor(), "server-created", id); return json(id)
    }
    @PutMapping("/admin/servers/{id}/assignment") fun assign(@PathVariable id: String, @RequestBody body: String) = json(store.assign(actor(), id, wire.decodeFromString(body)))
    @PostMapping("/admin/agents/{id}/revoke") @Transactional fun revokeAgent(@PathVariable id: String): ResponseEntity<String> {
        store.db.update("UPDATE scanner_agents SET revoked=TRUE WHERE id=?", uuid(id)); store.db.update("DELETE FROM credentials WHERE subject_id=?", id); store.audit(actor(), "agent-revoked", id); return json(true)
    }
    @GetMapping("/agent/assignments") fun assignments() = json(store.rows("SELECT * FROM minecraft_servers WHERE agent_id=?", actor()).map { Assignment(it["id"].toString(), actor(), (it["generation"] as Number).toLong(), it["mapping"].toString()) })
    @PostMapping("/agent/heartbeat") @Transactional fun heartbeat(@RequestBody body: String): ResponseEntity<String> {
        val heartbeat=wire.decodeFromString<Heartbeat>(body); require(heartbeat.protocol == PROTOCOL && heartbeat.scans.size <= 1000)
        heartbeat.scans.forEach { scan ->
            require(validHash(scan.digest))
            store.db.update("UPDATE minecraft_servers s JOIN inventory_snapshots i ON s.snapshot_id=i.id SET s.inventory_at=CURRENT_TIMESTAMP(6) WHERE s.id=? AND s.agent_id=? AND s.generation=? AND i.digest=?", uuid(scan.serverId), actor(), scan.generation, scan.digest)
        }
        store.db.update("INSERT INTO agent_heartbeats(agent_id,seen_at) VALUES (?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE seen_at=VALUES(seen_at)", actor()); return json(true)
    }
    @PostMapping("/agent/credentials/rotate") @Transactional fun rotate(): ResponseEntity<String> {
        store.db.update("UPDATE credentials SET expires_at=LEAST(expires_at,?) WHERE subject_id=?", Timestamp.from(Instant.now().plusSeconds(300)), actor())
        store.audit(actor(), "agent-credential-rotated", actor()); return json(store.credential(actor(), "AGENT"))
    }
    @PostMapping("/agent/snapshots") fun begin(@RequestBody body: String) = json(store.begin(actor(), wire.decodeFromString(body)))
    @PutMapping("/agent/snapshots/{id}/batches") fun batch(@PathVariable id: String, @RequestBody body: String): ResponseEntity<String> { store.batch(actor(), id, wire.decodeFromString(body)); return json(true) }
    @PostMapping("/agent/snapshots/{id}/commit") fun commit(@PathVariable id: String) = json(store.commit(actor(), id))
    @GetMapping("/servers") fun servers() = json(store.rows("SELECT * FROM minecraft_servers ORDER BY name").map {
        val at=(it["inventory_at"] as? Timestamp)?.toInstant()
        ServerInfo(it["id"].toString(),it["name"].toString(),it["minecraft_version"].toString(),it["loader"].toString(),
            it["loader_version"]?.toString(),at?.toString(),(it["revision"] as Number).toLong().takeIf { rev -> rev > 0 },
            inventoryStale=at == null || at.isBefore(Instant.now().minusSeconds(900)))
    })
    @GetMapping("/servers/{id}/manifest") fun manifest(@PathVariable id: String, @RequestParam(required=false) revision: Long?): ResponseEntity<String> {
        val rev = revision ?: (store.one("SELECT latest_revision FROM server_manifests WHERE server_id=?", uuid(id))["latest_revision"] as Number).toLong()
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(store.one("SELECT payload FROM manifest_revisions WHERE server_id=? AND revision=?", uuid(id), rev)["payload"].toString())
    }
    @GetMapping("/admin/servers/{id}/inventory") fun inventory(@PathVariable id: String): ResponseEntity<String> {
        val server = store.one("SELECT snapshot_id FROM minecraft_servers WHERE id=?", uuid(id)); val scan = server["snapshot_id"]?.toString() ?: reject("No accepted inventory")
        val begin = wire.decodeFromString<SnapshotBegin>(store.one("SELECT payload FROM inventory_snapshots WHERE id=?", scan)["payload"].toString())
        return json(SnapshotView(begin, store.entries(scan)))
    }
    @PutMapping("/admin/servers/{id}/classification") @Transactional fun classify(@PathVariable id: String, @RequestBody body: String): ResponseEntity<String> {
        val request = wire.decodeFromString<OverrideRequest>(body); require(validHash(request.sha512) && request.reason.length in 1..1000 && request.classification != Classification.UNKNOWN)
        store.db.update("INSERT INTO classification_overrides(server_id,sha512,classification,reason) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE classification=VALUES(classification),reason=VALUES(reason)", uuid(id), request.sha512, request.classification.name, request.reason)
        store.db.update("UPDATE minecraft_servers SET policy_revision=policy_revision+1 WHERE id=?",id)
        store.audit(actor(), "classification-approved", id); return json(true)
    }
    @PostMapping("/admin/servers/{id}/preview") fun preview(@PathVariable id: String, @RequestBody body: String) = json(manifests.validate(id, wire.decodeFromString(body)))
    @PostMapping("/admin/servers/{id}/publish") fun publish(@PathVariable id: String, @RequestBody body: String): ResponseEntity<String> {
        val request = wire.decodeFromString<PublishRequest>(body); return json(manifests.publish(actor(), manifests.validate(id, request), request.scanId))
    }
    @GetMapping("/personal") fun profiles() = json(store.rows("SELECT payload FROM personal_profiles WHERE user_id=? ORDER BY name", actor()).map { wire.decodeFromString<PersonalProfile>(it["payload"].toString()) })
    @PutMapping("/personal/{id}") @Transactional fun profile(@PathVariable id: String, @RequestBody body: String): ResponseEntity<String> {
        val p = wire.decodeFromString<PersonalProfile>(body); require(p.id == uuid(id) && p.name.length in 1..200 && p.artifacts.size <= 200)
        val logger = org.slf4j.LoggerFactory.getLogger(Controllers::class.java)
        val old = store.rows("SELECT user_id FROM personal_profiles WHERE id=? FOR UPDATE", id).singleOrNull(); requireState(old == null || old["user_id"] == actor(), "Profile ownership mismatch")
        val modrinth = Modrinth()
        val canonical = try { p.artifacts.map { a ->
            val project = modrinth.project(a.projectId)
            val serverSide = project["server_side"]?.jsonPrimitive?.contentOrNull
            requireState(serverSide == "unsupported", "Personal profile requires confirmed client-only Modrinth projects")
            modrinth.artifact(a.sha512, Classification.CLIENT_ONLY, listOf(Evidence("modrinth", "Server environment unsupported"))).also { requireState(it.projectId == a.projectId, "Project/hash mismatch") }
        }
        } catch (error: Exception) {
            logger.warn("profile metadata validation failed", error)
            throw error
        }
        val profile = p.copy(artifacts = canonical)
        store.db.update("INSERT INTO personal_profiles(id,user_id,name,payload) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),payload=VALUES(payload)", id, actor(), p.name, wire.encodeToString(profile))
        store.db.update("DELETE FROM personal_profile_mods WHERE profile_id=?", id)
        canonical.forEach { store.db.update("INSERT INTO personal_profile_mods(profile_id,project_id,payload) VALUES (?,?,?)", id, it.projectId, wire.encodeToString(it)) }
        store.audit(actor(), "profile-saved", id)
        return json(profile)
    }
    @GetMapping("/devices") fun devices() = json(store.rows("SELECT id FROM client_devices WHERE user_id=? AND revoked=FALSE", actor()).map { it["id"].toString() })
    @PostMapping("/devices/{id}/revoke") @Transactional fun revokeDevice(@PathVariable id: String): ResponseEntity<String> {
        store.db.update("UPDATE client_devices SET revoked=TRUE WHERE id=? AND user_id=?", uuid(id), actor()); store.db.update("DELETE FROM credentials WHERE device_id=? AND subject_id=?", id, actor()); store.audit(actor(), "device-revoked", id); return json(true)
    }
    @PostMapping("/sync/reports") fun report(@RequestBody body: String, request: HttpServletRequest): ResponseEntity<String> {
        val report = wire.decodeFromString<SyncReport>(body); require(report.status in setOf("SUCCESS", "FAILED", "ROLLED_BACK"))
        store.one("SELECT revision FROM manifest_revisions WHERE server_id=? AND revision=?", uuid(report.serverId), report.revision)
        store.db.update("INSERT INTO synchronization_history(id,device_id,server_id,revision,status,created_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(), request.getAttribute("deviceId"), report.serverId, report.revision, report.status); return json(true)
    }
}
@RestControllerAdvice
class Errors {
    @ExceptionHandler(IllegalArgumentException::class) fun invalid(request: HttpServletRequest): ResponseEntity<String> = ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(wire.encodeToString(Failure("INVALID_REQUEST", "Invalid request payload", request.getAttribute("correlationId").toString())))
}
