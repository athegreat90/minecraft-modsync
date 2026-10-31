package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

import kotlinx.serialization.Serializable
import picocli.CommandLine
import picocli.CommandLine.*
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.system.exitProcess

@Serializable data class AgentConfig(val endpoint: String, val controlPlaneId: String, val agentId: String, val credentials: Credentials? = null, val mappings: Map<String, String> = emptyMap())
@Serializable data class PendingInventory(val begin: SnapshotBegin, val entries: List<ModEntry>)
@Serializable data class AgentProgress(val generation: Long, val sequence: Long, val digest: String, val acknowledgedAt: String)
@Command(name="modsync-agent", mixinStandardHelpOptions=true, description=["Outbound-only Minecraft inventory scanner"])
class Agent : Runnable {
    @Option(names=["--state-dir"], defaultValue="\${env:MODSYNC_STATE_DIR:-.modsync-agent}") lateinit var stateDir: Path
    private val configFile get() = stateDir.resolve("config.json")
    private fun config() = wire.decodeFromString<AgentConfig>(Files.readString(configFile))
    private fun save(config: AgentConfig) = secureWrite(configFile, wire.encodeToString(config))
    private fun api(config: AgentConfig): Api {
        val api = Api(config.endpoint, config.credentials?.token ?: error("Agent is not registered"), "agent")
        require(Api(config.endpoint, component="agent").capabilities().controlPlaneId == config.controlPlaneId) { "Control-plane identity changed; verify before setup" }
        return api
    }
    override fun run() { CommandLine(this).usage(System.out) }
    @Command(name="setup") fun setup(@Option(names=["--server-url"], required=true, defaultValue="\${env:MODSYNC_SERVER_URL}") endpoint: String, @Option(names=["--trust-server-id"], required=true) trust: String) {
        val capabilities = Api(endpoint, component="agent").capabilities(); require(capabilities.controlPlaneId == uuid(trust)) { "Server identity does not match explicit trust" }
        val old = if (Files.exists(configFile)) config() else null
        save(AgentConfig(endpoint.trimEnd('/'), capabilities.controlPlaneId, old?.agentId ?: UUID.randomUUID().toString(), old?.takeIf { it.controlPlaneId == capabilities.controlPlaneId }?.credentials, old?.mappings ?: emptyMap()))
        println("Endpoint configured; identity preserved")
    }
    @Command(name="register") fun register(@Option(names=["--enrollment-token-file"], required=true) tokenFile: Path) {
        val c = config(); require(c.credentials == null) { "Already enrolled; use credential rotation for routine renewal" }
        require(Api(c.endpoint, component="agent").capabilities().controlPlaneId == c.controlPlaneId)
        val response = Api(c.endpoint).request("/api/v1/enroll", "POST", wire.encodeToString(Enrollment(Files.readString(tokenFile).trim(), c.agentId)))
        save(c.copy(credentials=wire.decodeFromString(response))); println("Agent enrolled: ${c.agentId}")
    }
    @Command(name="map") fun map(@Parameters(index="0") alias: String, @Parameters(index="1") mods: Path) {
        require(alias.matches(Regex("[A-Za-z0-9_-]{1,100}"))); require(Files.isDirectory(mods) && !Files.isSymbolicLink(mods))
        val c=config(); save(c.copy(mappings=c.mappings + (alias to mods.toRealPath().toString()))); println("Local mapping configured")
    }
    @Command(name="scan") fun scan(@Option(names=["--server"]) serverId: String?) { scanOnce(serverId) }
    private fun scanOnce(serverId: String?) {
        Files.createDirectories(stateDir)
        val lockPath=stateDir.resolve("agent.lock"); require(!Files.isSymbolicLink(lockPath))
        java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock=channel.tryLock() ?: error("Another scan is running")
            lock.use { scanUnlocked(serverId) }
        }
    }
    private fun scanUnlocked(serverId: String?) {
        var c = config(); var api = api(c)
        if (Instant.parse(c.credentials!!.expiresAt).isBefore(Instant.now().plusSeconds(86400))) {
            val credential = wire.decodeFromString<Credentials>(api.request("/api/v1/agent/credentials/rotate", "POST"))
            c = c.copy(credentials=credential); save(c); api = api(c)
        }
        api.request("/api/v1/agent/heartbeat", "POST", wire.encodeToString(Heartbeat()))
        val assignments = wire.decodeFromString<List<Assignment>>(api.request("/api/v1/agent/assignments"))
        if (serverId != null) require(assignments.any { it.serverId == uuid(serverId) }) { "Server not assigned" }
        val confirmations=mutableListOf<ScanConfirmation>()
        for (assignment in assignments.filter { serverId == null || it.serverId == serverId }) {
            val mods = Path.of(c.mappings[assignment.mapping] ?: error("Assigned mapping is not configured locally"))
            val pendingFile = stateDir.resolve("pending-${assignment.serverId}.json")
            val progressFile = stateDir.resolve("progress-${assignment.serverId}.json")
            val progress = if (Files.exists(progressFile)) wire.decodeFromString<AgentProgress>(Files.readString(progressFile)) else null
            var pending = if (Files.exists(pendingFile)) wire.decodeFromString<PendingInventory>(Files.readString(pendingFile)) else null
            if (pending != null && pending.begin.generation != assignment.generation) { Files.delete(pendingFile); pending=null }
            if (pending == null) {
                val entries = Scanner().scan(mods); val digest = inventoryDigest(entries)
                if (progress?.generation == assignment.generation && progress.digest == digest) { confirmations += ScanConfirmation(assignment.serverId, assignment.generation, digest); println("Unchanged inventory: ${assignment.serverId}"); continue }
                pending = PendingInventory(SnapshotBegin(assignment.serverId, UUID.randomUUID().toString(), assignment.generation,
                    if (progress?.generation == assignment.generation) progress.sequence+1 else 1, Instant.now().toString(), digest, entries.size), entries)
                secureWrite(pendingFile, wire.encodeToString(pending))
            }
            api.request("/api/v1/agent/snapshots", "POST", wire.encodeToString(pending.begin))
            val delivery = pending
            delivery.entries.forEachIndexed { index, entry -> api.request("/api/v1/agent/snapshots/${delivery.begin.scanId}/batches", "PUT", wire.encodeToString(SnapshotBatch(index, listOf(entry)))) }
            val ack = wire.decodeFromString<SnapshotAck>(api.request("/api/v1/agent/snapshots/${pending.begin.scanId}/commit", "POST"))
            require(ack.scanId == pending.begin.scanId && ack.digest == pending.begin.digest && ack.sequence == pending.begin.sequence)
            secureWrite(progressFile, wire.encodeToString(AgentProgress(assignment.generation, ack.sequence, ack.digest, ack.committedAt)))
            confirmations += ScanConfirmation(assignment.serverId, assignment.generation, ack.digest)
            Files.deleteIfExists(pendingFile); println("Committed inventory: ${assignment.serverId} revision ${ack.sequence}")
        }
        api.request("/api/v1/agent/heartbeat", "POST", wire.encodeToString(Heartbeat(scans=confirmations)))
        secureWrite(stateDir.resolve("healthy-at"), Instant.now().toString())
    }
    @Command(name="run") fun loop(@Option(names=["--interval"], defaultValue="\${env:MODSYNC_SCAN_INTERVAL:-300}") interval: Long) {
        require(interval in 30..86400)
        var failures=0
        while (!Thread.currentThread().isInterrupted) {
            try { scanOnce(null); failures=0 } catch (e: Exception) {
                if (e is InterruptedException) { Thread.currentThread().interrupt(); return }
                failures++; System.err.println("Scan or delivery failed; persisted inventory retained (attempt $failures)")
            }
            if (failures >= 8) error("Retry budget exhausted; inspect credentials and connectivity before restarting")
            val delay = if (failures == 0) interval*1000 else minOf(300000L, 1000L shl failures)
            Thread.sleep(delay + Random.nextLong(0, maxOf(1L, delay/10)))
        }
    }
    @Command(name="status") fun status() {
        val at=Instant.parse(Files.readString(stateDir.resolve("healthy-at"))); require(at.isAfter(Instant.now().minusSeconds(maxOf(900L,(System.getenv("MODSYNC_SCAN_INTERVAL")?.toLongOrNull() ?: 300L)*3)))) { "Agent heartbeat is stale" }; println("Last healthy cycle: $at")
    }
    @Command(name="doctor") fun doctor() { val c=config(); api(c).request("/api/v1/agent/assignments"); c.mappings.values.forEach { require(Files.isDirectory(Path.of(it))) }; println("TLS, identity, authentication and configured mappings passed") }
}
fun main(args: Array<String>) { val cli=CommandLine(Agent()); cli.setExecutionExceptionHandler { ex, _, _ -> System.err.println("Agent command failed: ${ex.javaClass.simpleName}; inspect configuration and server status"); 1 }; exitProcess(cli.execute(*args)) }
