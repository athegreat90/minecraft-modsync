package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.*
import de.alexandermora.modsync.protocol.*
import de.alexandermora.modsync.resolver.*
import de.alexandermora.modsync.scanner.*

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

/**
 * Explicit game/loader target of an instance, stored in `.modsync/instance.json`.
 *
 * @property minecraftVersion Minecraft version.
 * @property loader Mod loader name.
 * @property loaderVersion Optional pinned loader version.
 */
@Serializable data class InstanceTarget(val minecraftVersion: String, val loader: String, val loaderVersion: String? = null)
/**
 * Ownership record of the files this tool installed, kept separate from verified hashes.
 *
 * @property hashes SHA-512 hashes of files the tool owns, keyed by file name.
 * @property manifest Last applied manifest, or null before the first synchronization.
 * @property verifiedHashes SHA-512 hashes expected on disk after the last apply, keyed by file name.
 */
@Serializable data class ManagedState(val hashes: Map<String, String> = emptyMap(), val manifest: Manifest? = null, val verifiedHashes: Map<String, String> = hashes)
/**
 * One file change recorded in the install journal.
 *
 * @property fileName Mod file name inside `mods`.
 * @property before SHA-512 before the change, or null when the file did not exist.
 * @property after SHA-512 after the change, or null when the file is removed.
 */
@Serializable data class JournalEntry(val fileName: String, val before: String?, val after: String?)
/**
 * Journal that lets an interrupted or failed apply be rolled back from its backups.
 *
 * @property id Session UUID naming the backup directory.
 * @property entries Planned file changes.
 * @property previous Managed state to restore on rollback.
 * @property complete True once the apply finished and was verified.
 */
@Serializable data class InstallJournal(val id: String, val entries: List<JournalEntry>, val previous: ManagedState, val complete: Boolean = false)
/**
 * Applies sync plans to one instance directory atomically, with backups, a journal and rollback.
 *
 * All operations stay inside the real instance path, symlinks are refused, JARs are never executed
 * or extracted, and unmanaged player files are never deleted.
 *
 * @param instance Game directory of the instance; resolved to its real path.
 * @param newHttp Creates the HTTP client used for each download; it must not follow redirects. Replaceable so tests need no network.
 */
class Installer(
    instance: Path,
    private val newHttp: () -> HttpClient = { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build() }
) {
    private val root = instance.toRealPath().also { require(Files.isDirectory(it) && !Files.isSymbolicLink(instance)) }
    /** Managed `mods` directory of the instance. */
    val mods: Path = root.resolve("mods")
    private val stateDir = root.resolve(".modsync")
    private val journalPath = stateDir.resolve("journal.json")
    init { directory(mods); directory(stateDir) }
    /**
     * Creates a directory that must not be a symlink and must stay inside the instance.
     *
     * @param path Directory to create.
     */
    private fun directory(path: Path) {
        require(!Files.isSymbolicLink(path)); Files.createDirectories(path); require(path.toRealPath().startsWith(root))
    }
    /**
     * Resolves a mod file name inside `mods`, refusing unsafe names and symlinks.
     *
     * @param name File name to resolve.
     * @return Path of the file inside `mods`.
     */
    private fun path(name: String): Path { require(safeFileName(name)); return mods.resolve(name).also { require(!Files.isSymbolicLink(it)) } }
/**
 * Records the game/loader target of the instance under the instance lock, written atomically.
 *
 * The target is never inferred; later planning stops when it differs from the manifest.
 *
 * @param target Explicit Minecraft version, loader and optional loader version.
 */
    fun configureTarget(target: InstanceTarget) = locked {
        require(target.minecraftVersion.isNotBlank() && target.loader.isNotBlank())
        secureWrite(stateDir.resolve("instance.json"),wire.encodeToString(target))
    }
    /**
     * Reads the explicitly configured game/loader target of the instance.
     *
     * @return The configured target.
     */
    fun target(): InstanceTarget {
        val p=stateDir.resolve("instance.json"); require(!Files.isSymbolicLink(p))
        return wire.decodeFromString<InstanceTarget>(Files.readString(p))
    }
/**
 * Requires the configured instance target to equal the manifest game/loader (and loader version when the manifest pins one).
 *
 * @param manifest Manifest whose game/loader the instance must match.
 * @throws IllegalArgumentException when the versions do not match.
 */
    fun validateTarget(manifest: Manifest) {
        val target=target()
        require(target.minecraftVersion == manifest.minecraftVersion && target.loader == manifest.loader && (manifest.loaderVersion == null || target.loaderVersion == manifest.loaderVersion)) { "Configured instance game/loader versions do not match manifest" }
    }
/**
 * Reads the managed ownership state (hashes of files this tool installed and the last applied manifest).
 *
 * Refuses a symlinked state file. Ownership is kept separate from verified hashes.
 *
 * @return The recorded state, or an empty state when nothing was synchronized yet.
 */
    fun state(): ManagedState { val p=stateDir.resolve("managed.json"); require(!Files.isSymbolicLink(p)); return if (Files.exists(p)) wire.decodeFromString(Files.readString(p)) else ManagedState() }
/**
 * Runs an action while holding the exclusive, non-blocking instance lock.
 *
 * Refuses a symlinked lock file and fails fast when another process holds the lock.
 *
 * @param action Action to run while the lock is held.
 * @return The result of the action.
 */
    fun <T> locked(action: () -> T): T {
        val p=stateDir.resolve("instance.lock"); require(!Files.isSymbolicLink(p))
        return FileChannel.open(p, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock=channel.tryLock() ?: error("Instance is already locked")
            lock.use { action() }
        }
    }
/**
 * Applies a previously generated plan to the instance atomically, rolling back on any failure.
 *
 * Under the instance lock this re-scans the local inventory, re-derives the plan and requires it to match,
 * downloads and validates every artifact into a session directory (JARs are only hashed and inspected as archives, never executed),
 * backs up replaced files with hash checks, writes the journal, then moves files into place atomically
 * and verifies final hashes before marking the journal complete. Blocked plans and interrupted installations are refused.
 *
 * @param plan Plan produced by the resolver for the current manifest and local inventory.
 */
    fun apply(plan: SyncPlan) = locked {
        require(!plan.blocked) { "Plan contains conflicts or manual actions" }
        validateTarget(plan.manifest)
        require(!Files.isSymbolicLink(journalPath))
        if (Files.exists(journalPath)) require(wire.decodeFromString<InstallJournal>(Files.readString(journalPath)).complete) { "Interrupted installation; rollback before applying" }
        val before=Scanner().scan(mods); require(inventoryDigest(before) == plan.localDigest) { "Local inventory changed; generate a new plan" }
        val old=state()
        val personal=plan.operations.filter { it.artifact?.classification == Classification.CLIENT_ONLY }.mapNotNull { it.artifact }
        val expected=Resolver().plan(plan.manifest, personal, before, old.hashes, plan.includeOptional, plan.approvedRemovals)
        require(expected == plan) { "Plan does not match current managed state or manifest" }
        val changes=plan.operations.filter { it.kind in FILE_CHANGES }
        val id=UUID.randomUUID().toString(); val session=stateDir.resolve(id); directory(session); val backups=session.resolve("backup"); directory(backups)
        val journal=InstallJournal(id, changes.map { JournalEntry(it.fileName, before.find { e -> e.fileName == it.fileName }?.sha512, it.artifact?.sha512) }, old)
        changes.forEach { op -> op.artifact?.let { download(it, session.resolve(op.fileName)) } }
        require(inventoryDigest(Scanner().scan(mods)) == plan.localDigest) { "Inventory changed during downloads" }
        requireNoDuplicateMods(session, before, changes)
        changes.forEach { operation ->
            val entry=journal.entries.first { it.fileName == operation.fileName }
            if (entry.before != null) { Files.copy(path(entry.fileName), backups.resolve(entry.fileName)); require(fileHash(backups.resolve(entry.fileName)) == entry.before) }
        }
        secureWrite(journalPath, wire.encodeToString(journal))
        runCatching {
            changes.forEach { operation ->
                val p=path(operation.fileName)
                val oldHash=journal.entries.first { it.fileName == operation.fileName }.before
                require(if (oldHash == null) !Files.exists(p, NOFOLLOW_LINKS) else Files.exists(p, NOFOLLOW_LINKS) && fileHash(p) == oldHash) { "File changed during apply" }
                if (operation.kind in REMOVALS) Files.delete(p)
                else Files.move(session.resolve(operation.fileName), p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            val verified=plan.operations.filter { it.kind in setOf(OperationKind.KEEP, OperationKind.INSTALL, OperationKind.UPDATE) }
            val expected=verified.associate { it.fileName to requireNotNull(it.artifact) { "Missing artifact for ${it.fileName}" }.sha512 }
            expected.forEach { (name, sha512) -> require(fileHash(path(name)) == sha512); validateArchive(path(name)) }
            val hashes=verified.filter { it.kind != OperationKind.KEEP || old.hashes[it.fileName] == expected.getValue(it.fileName) }.associate { it.fileName to expected.getValue(it.fileName) }
            secureWrite(stateDir.resolve("managed.json"), wire.encodeToString(ManagedState(hashes, plan.manifest, expected)))
            secureWrite(journalPath, wire.encodeToString(journal.copy(complete=true)))
        }.onFailure { rollbackLocked() }.getOrThrow()
    }
/**
 * Refuses an installation that would leave two files for the same mod id, whatever the manifest metadata says.
 *
 * Inspects the downloaded JARs themselves, so manifests without mod identity cannot sneak a second version in.
 * Runs before any file of `mods` changes, so a refusal needs no rollback.
 *
 * @param session Session directory that holds the downloaded JARs.
 * @param before Inventory of `mods` the plan was derived from.
 * @param changes File-changing operations of the plan.
 * @throws IllegalArgumentException when a mod id would be provided by more than one file.
 */
    private fun requireNoDuplicateMods(session: Path, before: List<ModEntry>, changes: List<PlanOperation>) {
        val incoming = changes.filter { it.artifact != null }.map { it.fileName }.toSet()
        if (incoming.isEmpty()) return
        val removed = changes.filter { it.artifact == null }.map { it.fileName }.toSet() + incoming
        val result = (before.filter { it.fileName !in removed } + Scanner().scan(session).filter { it.fileName in incoming })
            .flatMap { entry -> entry.modIds.map { it to entry.fileName } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.distinct().size > 1 }
        val listing = result.entries.joinToString("; ") { "${it.key} in ${it.value.joinToString()}" }
        require(result.isEmpty()) { "Installation would leave several files for the same mod: $listing; resolve the duplicates first" }
    }
/**
 * Lists mod ids that more than one installed file provides.
 *
 * Read-only and independent of any manifest, so it also covers files ModSync does not own.
 *
 * @return One explanation per duplicated mod id, empty when every mod id is provided by a single file.
 */
    fun unresolvedDuplicates(): List<String> = duplicateMods(Scanner().scan(mods))
/**
 * Restores the pre-apply state from the journal and its verified backups under the instance lock.
 *
 * Refuses to overwrite files modified after the interrupted apply.
 */
    fun rollback() = locked { rollbackLocked() }
    /**
     * Restores the pre-apply state from the journal; the caller must hold the instance lock.
     *
     * Validates every target and backup hash before changing any file, never overwrites a later local
     * edit, and restores files with atomic moves.
     */
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
/**
 * Checks that every recorded managed file still exists with its verified hash and that the target still matches the recorded manifest.
 *
 * @return True when all files match; offline, so it does not prove the manifest is current.
 */
    fun verify(): Boolean = locked { val state=state(); require(state.manifest != null) { "No recorded synchronization" }; validateTarget(state.manifest); state.verifiedHashes.all { (name, hash) -> Files.exists(path(name), NOFOLLOW_LINKS) && fileHash(path(name)) == hash } }
    /**
     * Downloads an artifact from the official Modrinth CDN into a new file and validates it.
     *
     * Accepts only HTTPS without redirects, enforces the declared size, and checks the SHA-512 and
     * archive integrity; the downloaded JAR is never executed.
     *
     * @param artifact Artifact with its URL, size and expected SHA-512.
     * @param target File to create; it must not exist.
     */
    private fun download(artifact: Artifact, target: Path) {
        val uri=URI(artifact.url ?: error("Manual artifact required"))
        require(uri.scheme == "https" && uri.host == "cdn.modrinth.com" && uri.userInfo == null && uri.port in setOf(-1,443) && uri.path.startsWith("/data/")) { "Download must use official HTTPS Modrinth CDN" }
        require(artifact.size in 1..536870912 && validHash(artifact.sha512))
        val client=newHttp()
        val response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).header("User-Agent","Minecraft-ModSync/0.4.1").build(), HttpResponse.BodyHandlers.ofInputStream())
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
/**
 * Checks that a downloaded file is an intact ZIP/JAR without extracting or executing it.
 *
 * Bounds the entry count and expanded size, and compares each entry size and CRC-32 against its header.
 *
 * @param path ZIP/JAR file to check.
 * @throws IllegalArgumentException when a limit is exceeded or integrity fails.
 */
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

private val REMOVALS = setOf(OperationKind.REMOVE_MANAGED, OperationKind.QUARANTINE_DUPLICATE)
private val FILE_CHANGES = REMOVALS + setOf(OperationKind.INSTALL, OperationKind.UPDATE)
