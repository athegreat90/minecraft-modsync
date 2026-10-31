package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

import kotlinx.serialization.Serializable
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.CRC32

@Serializable data class InstanceTarget(val minecraftVersion: String, val loader: String, val loaderVersion: String? = null)
@Serializable data class ManagedState(val hashes: Map<String, String> = emptyMap(), val manifest: Manifest? = null, val verifiedHashes: Map<String, String> = hashes)
@Serializable data class JournalEntry(val fileName: String, val before: String?, val after: String?)
@Serializable data class InstallJournal(val id: String, val entries: List<JournalEntry>, val previous: ManagedState, val complete: Boolean = false)
class Installer(instance: Path) {
    private val root = instance.toRealPath().also { require(Files.isDirectory(it) && !Files.isSymbolicLink(instance)) }
    val mods: Path = root.resolve("mods")
    private val stateDir = root.resolve(".modsync")
    private val journalPath = stateDir.resolve("journal.json")
    init { directory(mods); directory(stateDir) }
    private fun directory(path: Path) {
        require(!Files.isSymbolicLink(path)); Files.createDirectories(path); require(path.toRealPath().startsWith(root))
    }
    private fun path(name: String): Path { require(safeFileName(name)); return mods.resolve(name).also { require(!Files.isSymbolicLink(it)) } }
    fun configureTarget(target: InstanceTarget) = locked {
        require(target.minecraftVersion.isNotBlank() && target.loader.isNotBlank())
        secureWrite(stateDir.resolve("instance.json"),wire.encodeToString(target))
    }
    fun validateTarget(manifest: Manifest) {
        val p=stateDir.resolve("instance.json"); require(!Files.isSymbolicLink(p))
        val target=wire.decodeFromString<InstanceTarget>(Files.readString(p))
        require(target.minecraftVersion == manifest.minecraftVersion && target.loader == manifest.loader && (manifest.loaderVersion == null || target.loaderVersion == manifest.loaderVersion)) { "Configured instance game/loader versions do not match manifest" }
    }
    fun state(): ManagedState { val p=stateDir.resolve("managed.json"); require(!Files.isSymbolicLink(p)); return if (Files.exists(p)) wire.decodeFromString(Files.readString(p)) else ManagedState() }
    fun <T> locked(action: () -> T): T {
        val p=stateDir.resolve("instance.lock"); require(!Files.isSymbolicLink(p))
        return FileChannel.open(p, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock=channel.tryLock() ?: error("Instance is already locked")
            lock.use { action() }
        }
    }
    fun apply(plan: SyncPlan) = locked {
        require(!plan.blocked) { "Plan contains conflicts or manual actions" }
        validateTarget(plan.manifest)
        require(!Files.isSymbolicLink(journalPath))
        if (Files.exists(journalPath)) require(wire.decodeFromString<InstallJournal>(Files.readString(journalPath)).complete) { "Interrupted installation; rollback before applying" }
        val before=Scanner().scan(mods); require(inventoryDigest(before) == plan.localDigest) { "Local inventory changed; generate a new plan" }
        val old=state()
        val expected=Resolver().plan(plan.manifest, plan.operations.filter { it.artifact?.classification == Classification.CLIENT_ONLY }.mapNotNull { it.artifact }, before, old.hashes, plan.includeOptional)
        require(expected == plan) { "Plan does not match current managed state or manifest" }
        val changes=plan.operations.filter { it.kind in setOf(OperationKind.INSTALL, OperationKind.UPDATE, OperationKind.REMOVE_MANAGED) }
        val id=UUID.randomUUID().toString(); val session=stateDir.resolve(id); directory(session); val backups=session.resolve("backup"); directory(backups)
        val journal=InstallJournal(id, changes.map { JournalEntry(it.fileName, before.find { e -> e.fileName == it.fileName }?.sha512, it.artifact?.sha512) }, old)
        changes.filter { it.artifact != null }.forEach { download(it.artifact!!, session.resolve(it.fileName)) }
        require(inventoryDigest(Scanner().scan(mods)) == plan.localDigest) { "Inventory changed during downloads" }
        changes.forEach { operation ->
            val entry=journal.entries.first { it.fileName == operation.fileName }
            if (entry.before != null) { Files.copy(path(entry.fileName), backups.resolve(entry.fileName)); require(fileHash(backups.resolve(entry.fileName)) == entry.before) }
        }
        secureWrite(journalPath, wire.encodeToString(journal))
        try {
            changes.forEach { operation ->
                val p=path(operation.fileName)
                val oldHash=journal.entries.first { it.fileName == operation.fileName }.before
                require(if (oldHash == null) !Files.exists(p, NOFOLLOW_LINKS) else Files.exists(p, NOFOLLOW_LINKS) && fileHash(p) == oldHash) { "File changed during apply" }
                if (operation.kind == OperationKind.REMOVE_MANAGED) Files.delete(p)
                else Files.move(session.resolve(operation.fileName), p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            val verified=plan.operations.filter { it.kind in setOf(OperationKind.KEEP, OperationKind.INSTALL, OperationKind.UPDATE) }
            verified.forEach { require(fileHash(path(it.fileName)) == it.artifact!!.sha512); validateArchive(path(it.fileName)) }
            val hashes=verified.filter { it.kind != OperationKind.KEEP || old.hashes[it.fileName] == it.artifact!!.sha512 }.associate { it.fileName to it.artifact!!.sha512 }
            secureWrite(stateDir.resolve("managed.json"), wire.encodeToString(ManagedState(hashes, plan.manifest, verified.associate { it.fileName to it.artifact!!.sha512 })))
            secureWrite(journalPath, wire.encodeToString(journal.copy(complete=true)))
        } catch (e: Exception) { rollbackLocked(); throw e }
    }
    fun rollback() = locked { rollbackLocked() }
    private fun rollbackLocked() {
        require(!Files.isSymbolicLink(journalPath)); val journal=wire.decodeFromString<InstallJournal>(Files.readString(journalPath)); uuid(journal.id)
        val backup=stateDir.resolve(journal.id).resolve("backup"); require(!Files.isSymbolicLink(backup) && !Files.isSymbolicLink(backup.parent))
        // Validate every target before changing any file; never overwrite a later local edit.
        journal.entries.forEach { entry ->
            val target=path(entry.fileName)
            val hash=if (Files.exists(target, NOFOLLOW_LINKS)) fileHash(target) else null
            require(hash == null || hash == entry.before || hash == entry.after) { "Rollback would overwrite a locally modified file" }
            if (entry.before != null) require(!Files.isSymbolicLink(backup.resolve(entry.fileName)) && fileHash(backup.resolve(entry.fileName)) == entry.before) { "Backup integrity failure" }
        }
        journal.entries.forEach { entry ->
            val target=path(entry.fileName)
            if (entry.before == null) Files.deleteIfExists(target)
            else {
                val temp=Files.createTempFile(mods, ".rollback-", ".tmp")
                Files.copy(backup.resolve(entry.fileName), temp, StandardCopyOption.REPLACE_EXISTING)
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        secureWrite(stateDir.resolve("managed.json"), wire.encodeToString(journal.previous)); Files.delete(journalPath)
    }
    fun verify(): Boolean = locked { val state=state(); require(state.manifest != null) { "No recorded synchronization" }; validateTarget(state.manifest); state.verifiedHashes.all { (name, hash) -> Files.exists(path(name), NOFOLLOW_LINKS) && fileHash(path(name)) == hash } }
    private fun download(artifact: Artifact, target: Path) {
        val uri=URI(artifact.url ?: error("Manual artifact required"))
        require(uri.scheme == "https" && uri.host == "cdn.modrinth.com" && uri.userInfo == null && uri.port in setOf(-1,443) && uri.path.startsWith("/data/")) { "Download must use official HTTPS Modrinth CDN" }
        require(artifact.size in 1..536870912 && validHash(artifact.sha512))
        val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
        val response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).header("User-Agent","Minecraft-ModSync/0.1.0").build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            require(response.statusCode() == 200) { "Download failed: HTTP ${response.statusCode()}" }
            Files.newOutputStream(target, CREATE_NEW, WRITE).use { output ->
                val buffer=ByteArray(65536); var total=0L
                while (true) { val n=input.read(buffer); if (n < 0) break; total += n; require(total <= artifact.size); output.write(buffer,0,n) }
                require(total == artifact.size) { "Truncated artifact" }
            }
        }
        require(fileHash(target) == artifact.sha512) { "Artifact hash mismatch" }
        validateArchive(target)
    }
}
fun validateArchive(path: Path) {
    ZipFile(path.toFile()).use { zip ->
        require(zip.size() <= 100000); var expanded=0L
        val entries=zip.entries()
        while (entries.hasMoreElements()) {
            val entry=entries.nextElement(); if (entry.isDirectory) continue
            require(entry.size in 0..1073741824)
            val crc=CRC32(); var size=0L
            zip.getInputStream(entry).use { input ->
                val buffer=ByteArray(65536)
                while (true) { val n=input.read(buffer); if (n<0) break; size+=n; expanded+=n; require(expanded<=1073741824); crc.update(buffer,0,n) }
            }
            require(size == entry.size && crc.value == entry.crc) { "Archive integrity failure" }
        }
    }
}
