package de.alexandermora.modsync.agent

import de.alexandermora.modsync.domain.*
import de.alexandermora.modsync.protocol.*
import de.alexandermora.modsync.scanner.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import picocli.CommandLine
import picocli.CommandLine.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.system.exitProcess

private val logger =
    run {
        System.setProperty("kotlin-logging.logStartupMessage", "false") // keep CLI stderr free of the library banner
        KotlinLogging.logger {}
    }

/**
 * Persistent agent configuration stored in the state directory with owner-only permissions.
 *
 * @property endpoint Base HTTPS URL of the server, without a trailing slash.
 * @property controlPlaneId Control-plane UUID that was explicitly trusted during setup.
 * @property agentId Stable agent UUID presented at enrollment.
 * @property credentials Issued agent credentials, or null before enrollment.
 * @property mappings Local `mods` directory real paths keyed by server-assigned alias.
 */
@Serializable data class AgentConfig(val endpoint: String, val controlPlaneId: String, val agentId: String, val credentials: Credentials? = null, val mappings: Map<String, String> = emptyMap())

/**
 * Snapshot persisted before delivery so a failed upload is retried with the identical inventory.
 *
 * @property begin Snapshot header sent when opening the upload.
 * @property entries Scanned mod entries delivered one per batch.
 */
@Serializable data class PendingInventory(val begin: SnapshotBegin, val entries: List<ModEntry>)

/**
 * Last server-acknowledged snapshot of one assignment, used to skip unchanged inventories.
 *
 * @property generation Assignment generation the snapshot belongs to.
 * @property sequence Acknowledged snapshot sequence number.
 * @property digest Inventory digest the server acknowledged.
 * @property acknowledgedAt ISO-8601 time the server committed the snapshot.
 */
@Serializable data class AgentProgress(val generation: Long, val sequence: Long, val digest: String, val acknowledgedAt: String)

/**
 * Picocli root command of the outbound-only agent; its subcommands are the annotated member functions.
 *
 * The agent only reads the local `mods` directories (never executing or extracting JARs, refusing
 * symlinks) and only makes outbound HTTPS calls to the server.
 */
@Command(name = "modsync-agent", mixinStandardHelpOptions = true, description = ["Outbound-only Minecraft inventory scanner"])
class Agent : Runnable {
    /** Directory holding configuration, pending snapshots, progress files and the scan lock. */
    @Option(names = ["--state-dir"], defaultValue = $$"${env:MODSYNC_STATE_DIR:-.modsync-agent}")
    lateinit var stateDir: Path

    /** Location of the agent configuration file inside [stateDir]. */
    private val configFile get() = stateDir.resolve("config.json")

    /**
     * Reads the persisted agent configuration.
     *
     * @return The decoded [AgentConfig].
     */
    private fun config() = wire.decodeFromString<AgentConfig>(Files.readString(configFile))

    /**
     * Writes the agent configuration atomically with owner-only permissions.
     *
     * @param config Configuration to persist.
     */
    private fun save(config: AgentConfig) = secureWrite(configFile, wire.encodeToString(config))

    /**
     * Builds an authenticated API client after re-verifying the pinned control-plane identity.
     *
     * @param config Agent configuration that must contain credentials.
     * @return Authenticated [Api] client.
     */
    private fun api(config: AgentConfig): Api {
        val api = Api(config.endpoint, config.credentials?.token ?: error("Agent is not registered"), "agent")
        require(Api(config.endpoint, component = "agent").capabilities().controlPlaneId == config.controlPlaneId) { "Control-plane identity changed; verify before setup" }
        return api
    }

    /** Prints the usage help when no subcommand is given. */
    override fun run() {
        CommandLine(this).usage(System.out)
    }

    /**
     * Configures the endpoint after the server identity was explicitly trusted.
     *
     * An existing agent identity and mappings are kept; credentials are kept only when the control-plane identity is unchanged.
     *
     * @param endpoint Base HTTPS URL of the server.
     * @param trust Control-plane UUID verified out of band.
     */
    @Command(name = "setup")
    fun setup(
        @Option(names = ["--server-url"], required = true, defaultValue = $$"${env:MODSYNC_SERVER_URL}") endpoint: String,
        @Option(names = ["--trust-server-id"], required = true) trust: String
    ) {
        val capabilities = Api(endpoint, component = "agent").capabilities()
        require(capabilities.controlPlaneId == uuid(trust)) { "Server identity does not match explicit trust" }
        val old = if (Files.exists(configFile)) config() else null
        save(AgentConfig(endpoint.trimEnd('/'), capabilities.controlPlaneId, old?.agentId ?: UUID.randomUUID().toString(), old?.takeIf { it.controlPlaneId == capabilities.controlPlaneId }?.credentials, old?.mappings ?: emptyMap()))
        println("Endpoint configured; identity preserved")
    }

    /**
     * Enrolls this agent once with a single-use enrollment token and stores the issued credentials.
     *
     * Refuses to run when credentials exist (use rotation for renewal) or the control-plane identity changed.
     *
     * @param tokenFile File containing the enrollment token; it is never passed on the command line.
     */
    @Command(name = "register")
    fun register(
        @Option(names = ["--enrollment-token-file"], required = true) tokenFile: Path
    ) {
        val c = config()
        require(c.credentials == null) { "Already enrolled; use credential rotation for routine renewal" }
        require(Api(c.endpoint, component = "agent").capabilities().controlPlaneId == c.controlPlaneId)
        val response = Api(c.endpoint).request("/api/v1/enroll", "POST", wire.encodeToString(Enrollment(Files.readString(tokenFile).trim(), c.agentId)))
        save(c.copy(credentials = wire.decodeFromString(response)))
        println("Agent enrolled: ${c.agentId}")
    }

    /**
     * Maps a server-assigned alias to a local `mods` directory.
     *
     * Symbolic links are refused; the real path is stored so later scans cannot be redirected.
     *
     * @param alias Mapping name matching `[A-Za-z0-9_-]{1,100}`.
     * @param mods Existing local mods directory.
     */
    @Command(name = "map")
    fun map(
        @Parameters(index = "0") alias: String,
        @Parameters(index = "1") mods: Path
    ) {
        require(alias.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(Files.isDirectory(mods) && !Files.isSymbolicLink(mods))
        val c = config()
        save(c.copy(mappings = c.mappings + (alias to mods.toRealPath().toString())))
        println("Local mapping configured")
    }

    /**
     * Performs one scan-and-upload pass under an exclusive lock.
     *
     * @param serverId Restrict the pass to one assigned server; all assignments otherwise.
     */
    @Command(name = "scan")
    fun scan(
        @Option(names = ["--server"]) serverId: String?
    ) {
        scanOnce(serverId)
    }

    /**
     * Runs one scan pass while holding an exclusive lock file in the state directory.
     *
     * The lock file is opened without following symlinks, and a second concurrent scan fails immediately.
     *
     * @param serverId Restrict the pass to one assigned server; all assignments otherwise.
     */
    private fun scanOnce(serverId: String?) {
        Files.createDirectories(stateDir)
        val lockPath = stateDir.resolve("agent.lock")
        require(!Files.isSymbolicLink(lockPath))
        java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = channel.tryLock() ?: error("Another scan is running")
            lock.use { scanUnlocked(serverId) }
        }
    }

    /**
     * Scans and uploads every assigned mapping; the caller must hold the scan lock.
     *
     * Rotates credentials that expire within a day, re-delivers a persisted pending snapshot of the same
     * generation, skips unchanged inventories, and only reports a scan as confirmed after the server
     * acknowledged the matching digest. Local files are only read, never executed or modified.
     *
     * @param serverId Restrict the pass to one assigned server; all assignments otherwise.
     */
    private fun scanUnlocked(serverId: String?) {
        var c = config()
        var api = api(c)
        if (Instant.parse(checkNotNull(c.credentials) { "Agent credentials are missing" }.expiresAt).isBefore(Instant.now().plusSeconds(86400))) {
            val credential = wire.decodeFromString<Credentials>(api.request("/api/v1/agent/credentials/rotate", "POST"))
            c = c.copy(credentials = credential)
            save(c)
            api = api(c)
        }
        api.request("/api/v1/agent/heartbeat", "POST", wire.encodeToString(Heartbeat()))
        val assignments = wire.decodeFromString<List<Assignment>>(api.request("/api/v1/agent/assignments"))
        if (serverId != null) require(assignments.any { it.serverId == uuid(serverId) }) { "Server not assigned" }
        val confirmations = mutableListOf<ScanConfirmation>()
        for (assignment in assignments.filter { serverId == null || it.serverId == serverId }) {
            val mods = Path.of(c.mappings[assignment.mapping] ?: error("Assigned mapping is not configured locally"))
            val pendingFile = stateDir.resolve("pending-${assignment.serverId}.json")
            val progressFile = stateDir.resolve("progress-${assignment.serverId}.json")
            val progress = if (Files.exists(progressFile)) wire.decodeFromString<AgentProgress>(Files.readString(progressFile)) else null
            var pending = if (Files.exists(pendingFile)) wire.decodeFromString<PendingInventory>(Files.readString(pendingFile)) else null
            if (pending != null && pending.begin.generation != assignment.generation) {
                Files.delete(pendingFile)
                pending = null
            }
            if (pending == null) {
                val entries = Scanner().scan(mods)
                val digest = inventoryDigest(entries)
                if (progress?.generation == assignment.generation && progress.digest == digest) {
                    confirmations += ScanConfirmation(assignment.serverId, assignment.generation, digest)
                    logger.info { "Unchanged inventory: ${assignment.serverId}" }
                    continue
                }
                pending =
                    PendingInventory(
                        SnapshotBegin(
                            assignment.serverId,
                            UUID.randomUUID().toString(),
                            assignment.generation,
                            if (progress?.generation == assignment.generation) progress.sequence + 1 else 1,
                            Instant.now().toString(),
                            digest,
                            entries.size
                        ),
                        entries
                    )
                secureWrite(pendingFile, wire.encodeToString(pending))
            }
            api.request("/api/v1/agent/snapshots", "POST", wire.encodeToString(pending.begin))
            val delivery = pending
            delivery.entries.forEachIndexed { index, entry -> api.request("/api/v1/agent/snapshots/${delivery.begin.scanId}/batches", "PUT", wire.encodeToString(SnapshotBatch(index, listOf(entry)))) }
            val ack = wire.decodeFromString<SnapshotAck>(api.request("/api/v1/agent/snapshots/${pending.begin.scanId}/commit", "POST"))
            require(ack.scanId == pending.begin.scanId && ack.digest == pending.begin.digest && ack.sequence == pending.begin.sequence)
            secureWrite(progressFile, wire.encodeToString(AgentProgress(assignment.generation, ack.sequence, ack.digest, ack.committedAt)))
            confirmations += ScanConfirmation(assignment.serverId, assignment.generation, ack.digest)
            Files.deleteIfExists(pendingFile)
            logger.info { "Committed inventory: ${assignment.serverId} revision ${ack.sequence}" }
        }
        api.request("/api/v1/agent/heartbeat", "POST", wire.encodeToString(Heartbeat(scans = confirmations)))
        secureWrite(stateDir.resolve("healthy-at"), Instant.now().toString())
    }

    /**
     * Scans repeatedly until interrupted, retrying failed passes with capped exponential backoff and jitter.
     *
     * Pending inventories stay persisted between attempts, so retries re-deliver the same snapshot.
     * Gives up after eight consecutive failures so a supervisor can surface the problem.
     *
     * @param interval Seconds between successful passes, within 30..86400.
     */
    @Command(name = "run")
    fun loop(
        @Option(names = ["--interval"], defaultValue = $$"${env:MODSYNC_SCAN_INTERVAL:-300}") interval: Long
    ) {
        require(interval in 30..86400)
        var failures = 0
        while (!Thread.currentThread().isInterrupted) {
            try {
                scanOnce(null)
                failures = 0
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                failures++
                logger.warn { "Scan or delivery failed; persisted inventory retained (attempt $failures)" }
            }
            if (failures >= 8) error("Retry budget exhausted; inspect credentials and connectivity before restarting")
            val delay = if (failures == 0) interval * 1000 else minOf(300000L, 1000L shl failures)
            Thread.sleep(delay + Random.nextLong(0, maxOf(1L, delay / 10)))
        }
    }

    /** Fails when the last healthy scan cycle is older than the staleness window; intended for container health checks. */
    @Command(name = "status")
    fun status() {
        val at = Instant.parse(Files.readString(stateDir.resolve("healthy-at")))
        require(at.isAfter(Instant.now().minusSeconds(maxOf(900L, (System.getenv("MODSYNC_SCAN_INTERVAL")?.toLongOrNull() ?: 300L) * 3)))) { "Agent heartbeat is stale" }
        println("Last healthy cycle: $at")
    }

    /** Verifies identity pinning, authentication and that every configured mapping is an existing directory, without uploading anything. */
    @Command(name = "doctor")
    fun doctor() {
        val c = config()
        api(c).request("/api/v1/agent/assignments")
        c.mappings.values.forEach { require(Files.isDirectory(Path.of(it))) }
        println("TLS, identity, authentication and configured mappings passed")
    }
}

/**
 * Runs the agent command line; failures are logged without details and mapped to exit code 1.
 *
 * @param args Command-line arguments passed to the Picocli parser.
 */
fun main(args: Array<String>) {
    val cli = CommandLine(Agent())
    cli.setExecutionExceptionHandler { ex, _, _ ->
        logger.error { "Agent command failed: ${ex.javaClass.simpleName}; inspect configuration and server status" }
        1
    }
    exitProcess(cli.execute(*args))
}
