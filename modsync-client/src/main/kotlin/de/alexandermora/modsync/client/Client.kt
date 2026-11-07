package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.*
import de.alexandermora.modsync.protocol.*
import de.alexandermora.modsync.resolver.*
import de.alexandermora.modsync.scanner.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

import kotlinx.serialization.Serializable
import picocli.CommandLine
import picocli.CommandLine.*
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess
private val logger = run {
    System.setProperty("kotlin-logging.logStartupMessage", "false") // keep CLI stderr free of the library banner
    KotlinLogging.logger {}
}

/**
 * Persistent client configuration stored in the state directory with owner-only permissions.
 *
 * @property endpoint Base HTTPS URL of the server, without a trailing slash.
 * @property controlPlaneId Control-plane UUID that was explicitly trusted during setup.
 * @property deviceId Stable device UUID presented at login.
 * @property credentials Issued user credentials, or null before login.
 * @property instances Real paths of registered game directories keyed by instance name.
 */
@Serializable data class ClientConfig(val endpoint: String, val controlPlaneId: String, val deviceId: String, val credentials: Credentials? = null, val instances: Map<String, String> = emptyMap())
/**
 * Picocli root command of the lightweight client; its subcommands are the annotated member functions and the command groups.
 *
 * Installations are planned deterministically, verified online right before apply, and applied
 * atomically with backups by [Installer]. JARs are never executed and symlinks are refused.
 */
@Command(name="modsync", mixinStandardHelpOptions=true, description=["Minecraft ModSync lightweight client"])
class Client: Runnable {
    /** Directory holding configuration, cached manifests, automatic synchronization settings and credentials. */
    @Option(names=["--state-dir"], defaultValue= $$"${env:MODSYNC_STATE_DIR:-${sys:user.home}/.modsync}", scope=ScopeType.INHERIT) lateinit var stateDir: Path
    /** Location of the client configuration file inside [stateDir]. */
    val configFile get() = stateDir.resolve("config.json")
/**
 * Loads the persisted client configuration from the state directory.
 *
 * @return The decoded [ClientConfig].
 */
    fun config() = wire.decodeFromString<ClientConfig>(Files.readString(configFile))
/**
 * Persists the configuration with an atomic, permission-restricted write.
 *
 * @param c Configuration to store.
 */
    fun save(c: ClientConfig) = secureWrite(configFile, wire.encodeToString(c))
/**
 * Builds an authenticated API client after re-checking the pinned control-plane identity.
 *
 * Refreshes credentials that expire within a minute and persists the refreshed set.
 *
 * @return Authenticated [Api] client.
 * @throws IllegalStateException when no login exists.
 */
    fun api(): Api {
        var c=config(); require(Api(c.endpoint).capabilities().controlPlaneId == c.controlPlaneId) { "Control-plane identity changed; verify before trusting replacement" }
        var credential=c.credentials ?: error("Login required")
        if (Instant.parse(credential.expiresAt).isBefore(Instant.now().plusSeconds(60))) {
            credential=wire.decodeFromString(Api(c.endpoint).request("/api/v1/auth/refresh", "POST", wire.encodeToString(RefreshRequest(credential.refreshToken ?: error("Login required")))))
            c=c.copy(credentials=credential); save(c)
        }
        return Api(c.endpoint, credential.token)
    }
/**
 * Resolves the directory of a registered instance.
 *
 * @param name Instance name as registered with `instances add`.
 * @return Directory of the instance.
 */
    fun instance(name: String) = Path.of(config().instances[name] ?: error("Instance not registered"))
/** Prints the usage help when no subcommand is given. */
    override fun run() { clientCommandLine(this).usage(System.out) }
/**
 * Configures the server endpoint and pins its control-plane identity.
 *
 * Trust must be given explicitly via `--trust-server-id` or by typing the UUID at a console;
 * device identity is preserved and credentials are dropped when the identity changes.
 *
 * @param endpoint Base HTTPS URL of the server.
 * @param trust Control-plane UUID verified out of band.
 */
    @Command(name="setup") fun setup(@Option(names=["--server-url"], required=true) endpoint: String, @Option(names=["--trust-server-id"]) trust: String?) {
        val caps=Api(endpoint).capabilities(); val old=if (Files.exists(configFile)) config() else null
        if (caps.controlPlaneId != old?.controlPlaneId) {
            if (trust != null) require(uuid(trust) == caps.controlPlaneId)
            else {
                println("Control-plane identity: ${caps.controlPlaneId}")
                val console=System.console() ?: error("Use --trust-server-id after independently verifying this identity")
                require(console.readLine("Trust this control plane? Type its UUID: ") == caps.controlPlaneId)
            }
        }
        save(ClientConfig(endpoint.trimEnd('/'), caps.controlPlaneId, old?.deviceId ?: UUID.randomUUID().toString(), old?.takeIf { it.controlPlaneId == caps.controlPlaneId }?.credentials, old?.instances ?: emptyMap()))
        println("Endpoint configured; device identity preserved")
    }
/**
 * Authenticates against the pinned server and stores the issued credentials.
 *
 * @param user Account name.
 * @param passwordFile File holding the password; prompts on the console when omitted.
 */
    @Command(name="login") fun login(@Option(names=["--username"], required=true) user: String, @Option(names=["--password-file"]) passwordFile: Path?) {
        val c=config(); require(Api(c.endpoint).capabilities().controlPlaneId == c.controlPlaneId)
        val password=passwordFile?.let { Files.readString(it).trimEnd('\r','\n') } ?: String(System.console()?.readPassword("Password: ") ?: error("A console or --password-file is required"))
        save(c.copy(credentials=wire.decodeFromString(Api(c.endpoint).request("/api/v1/auth/login", "POST", wire.encodeToString(Login(user,password,c.deviceId)))))); println("Authenticated")
    }
/** Runs offline diagnostics of the local configuration and registered instance directories; never authenticates. */
    @Command(name="doctor") fun doctor() {
        val c=config(); println("Device: ${c.deviceId}")
        c.instances.keys.forEach { name -> require(Files.isDirectory(instance(name))); println("Instance registered: $name") }
        println("Local diagnostics passed; use connection test to check online authentication")
    }
/**
 * Starts the separately distributed interactive terminal in a child JVM sharing this state directory.
 *
 * @param home Extracted `modsync-client-tui` distribution; its `lib` directory forms the class path.
 */
    @Command(name="interactive") fun interactive(@Option(names=["--tui-home"], defaultValue=$$"${env:MODSYNC_TUI_HOME}") home: Path?) {
        val library=(home ?: error("Set MODSYNC_TUI_HOME to the extracted modsync-client-tui distribution")).resolve("lib")
        require(Files.isDirectory(library)) { "TUI distribution lib directory is missing" }
        val javaHome=System.getenv("JAVA_HOME")
        val java=if (javaHome == null) "java" else Path.of(javaHome,"bin",if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val process=ProcessBuilder(java,"-cp",library.resolve("*").toString(),"de.alexandermora.modsync.InteractiveKt").inheritIO()
        process.environment()["MODSYNC_STATE_DIR"]=stateDir.toAbsolutePath().toString()
        check(process.start().waitFor()==0) { "Interactive terminal exited unsuccessfully" }
    }
}
/**
 * Picocli group `config` for viewing and changing the endpoint settings.
 *
 * @property root Client providing configuration access.
 */
@Command(name="config", mixinStandardHelpOptions=true) class ConfigCommands(private val root: Client) {
/** Prints the non-secret endpoint and identity settings. */
    @Command(name="show") fun show() { val c=root.config(); println("server-url=${c.endpoint}\ncontrol-plane-id=${c.controlPlaneId}\ndevice-id=${c.deviceId}") }
/**
 * Changes the server URL by re-running setup, so identity trust is re-established.
 *
 * @param key Only `server-url` is supported.
 * @param value New endpoint.
 * @param trust Control-plane UUID verified out of band.
 */
    @Command(name="set") fun set(@Parameters(index="0") key: String, @Parameters(index="1") value: String, @Option(names=["--trust-server-id"]) trust: String?) { require(key == "server-url"); root.setup(value, trust) }
}
/**
 * Picocli group `connection` for online connectivity checks.
 *
 * @property root Client providing the authenticated API.
 */
@Command(name="connection", mixinStandardHelpOptions=true) class ConnectionCommands(private val root: Client) {
/** Checks TLS, control-plane identity and authentication with one authenticated request. */
    @Command(name="test") fun test() { root.api().request("/api/v1/servers"); println("TLS, control-plane identity and authentication passed") }
}
/**
 * Picocli group `servers` for listing visible servers.
 *
 * @property root Client providing the authenticated API.
 */
@Command(name="servers", mixinStandardHelpOptions=true) class ServerCommands(private val root: Client) {
/** Prints the servers visible to the authenticated user. */
    @Command(name="list") fun list() { println(root.api().request("/api/v1/servers")) }
}
/**
 * Picocli group `instances` for registering and discovering local game directories.
 *
 * @property root Client providing configuration access.
 */
@Command(name="instances", mixinStandardHelpOptions=true) class InstanceCommands(private val root: Client) {
/**
 * Registers a local game directory as a named instance with an explicit game/loader target.
 *
 * Rejects invalid names and symlinked paths; the real path is stored.
 *
 * @param name Instance name matching `[A-Za-z0-9_-]{1,80}`.
 * @param path Existing game directory.
 * @param minecraft Minecraft version.
 * @param loader Mod loader name.
 * @param loaderVersion Optional loader version to pin.
 */
    @Command(name="add") fun add(@Parameters(index="0") name: String, @Parameters(index="1") path: Path,
        @Option(names=["--minecraft-version"],required=true) minecraft: String,
        @Option(names=["--loader"],required=true) loader: String,
        @Option(names=["--loader-version"]) loaderVersion: String?) {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,80}")) && Files.isDirectory(path) && !Files.isSymbolicLink(path))
        Installer(path).configureTarget(InstanceTarget(minecraft,loader,loaderVersion))
        val c=root.config(); root.save(c.copy(instances=c.instances + (name to path.toRealPath().toString()))); println("Instance registered with explicitly selected game/loader versions")
    }
/** Lists common launcher directories as candidates only; nothing is registered or modified. */
    @Command(name="discover") fun discover() {
        val home=Path.of(System.getProperty("user.home"))
        listOf(home.resolve(".minecraft"), home.resolve("AppData/Roaming/.minecraft"), home.resolve(".local/share/PrismLauncher/instances"), home.resolve("AppData/Roaming/PrismLauncher/instances")).filter { Files.isDirectory(it) }.forEach(::println)
        println("Candidates only; register the selected game directory with instances add")
    }
}
/**
 * Picocli group `mods` for read-only inspection of an instance's mods.
 *
 * @property root Client resolving registered instances.
 */
@Command(name="mods", mixinStandardHelpOptions=true) class ModCommands(private val root: Client) {
/**
 * Prints the read-only inventory of an instance's `mods` directory as JSON.
 *
 * @param instance Registered instance name.
 */
    @Command(name="scan") fun scan(@Option(names=["--instance"], required=true) instance: String) { println(wire.encodeToString(Scanner().scan(root.instance(instance).resolve("mods")))) }
/**
 * Alias of `mods scan`.
 *
 * @param instance Registered instance name.
 */
    @Command(name="list") fun list(@Option(names=["--instance"], required=true) instance: String) { scan(instance) }
}
/**
 * Picocli group `personal` for listing and importing personal profiles.
 *
 * @property root Client providing the authenticated API.
 */
@Command(name="personal", mixinStandardHelpOptions=true) class PersonalCommands(private val root: Client) {
/** Prints the personal profiles of the authenticated user. */
    @Command(name="list") fun list() { println(root.api().request("/api/v1/personal")) }
/**
 * Uploads a personal profile from a JSON file.
 *
 * @param file Profile file; its id must be a valid UUID.
 */
    @Command(name="import") fun import(@Parameters(index="0") file: Path) {
        val profile=wire.decodeFromString<PersonalProfile>(Files.readString(file)); println(root.api().request("/api/v1/personal/${uuid(profile.id)}", "PUT", wire.encodeToString(profile)))
    }
}
/**
 * Picocli group `sync` for planning, applying, verifying and rolling back manifest installations.
 *
 * Applying is atomic with backups and a journal via [Installer]; JARs are never executed and symlinks are refused.
 *
 * @property root Client providing the API and registered instances.
 */
@Command(name="sync", mixinStandardHelpOptions=true) class SyncCommands(private val root: Client) {
/**
 * Builds a deterministic sync plan from the published manifest and the local inventory, and writes it to a file.
 *
 * Also caches the manifest for offline inspection. Exits with an error when the plan is blocked by conflicts or manual actions.
 *
 * @param instance Registered instance name.
 * @param server Server UUID.
 * @param profile Optional personal profile UUID to include.
 * @param output Destination of the plan file.
 * @param includeOptional Also install optional artifacts.
 */
    @Command(name="plan") fun plan(@Option(names=["--instance"], required=true) instance: String, @Option(names=["--server"], required=true) server: String,
        @Option(names=["--profile"]) profile: String?, @Option(names=["--out"], required=true) output: Path, @Option(names=["--include-optional"]) includeOptional: Boolean) {
        val api=root.api(); val manifest=wire.decodeFromString<Manifest>(api.request("/api/v1/servers/${uuid(server)}/manifest"))
        val personal=if (profile == null) emptyList() else wire.decodeFromString<List<PersonalProfile>>(api.request("/api/v1/personal")).first { it.id == uuid(profile) }.artifacts
        val installer=Installer(root.instance(instance)); installer.validateTarget(manifest); val plan=installer.locked { Resolver().plan(manifest, personal, Scanner().scan(installer.mods), installer.state().hashes, includeOptional) }
        secureWrite(output, wire.encodeToString(plan)); secureWrite(root.stateDir.resolve("manifest-${uuid(server)}.json"), wire.encodeToString(manifest)); secureWrite(root.stateDir.resolve("manifest-${uuid(server)}.cached-at"), Instant.now().toString())
        plan.operations.forEach { println("${it.kind} ${it.fileName}: ${it.reason}") }; if (plan.blocked) error("Plan is blocked; resolve conflicts or manual actions")
    }
/**
 * Applies a saved plan after re-verifying online that the manifest is unchanged and personal artifacts are still authorized.
 *
 * The installation itself is atomic, backed up and journaled by [Installer]; a failed history report only logs a warning.
 *
 * @param instance Registered instance name.
 * @param file Plan file from `sync plan`.
 * @param dry Print the operations without touching the instance.
 */
    @Command(name="apply") fun apply(@Option(names=["--instance"], required=true) instance: String, @Option(names=["--plan"], required=true) file: Path, @Option(names=["--dry-run"]) dry: Boolean) {
        val plan=wire.decodeFromString<SyncPlan>(Files.readString(file)); require(!plan.blocked)
        val api=root.api(); val current=wire.decodeFromString<Manifest>(api.request("/api/v1/servers/${uuid(plan.manifest.serverId)}/manifest"))
        require(current == plan.manifest) { "Published manifest changed; generate a new plan" }
        val profiles=wire.decodeFromString<List<PersonalProfile>>(api.request("/api/v1/personal"))
        val personal=plan.operations.mapNotNull { it.artifact }.filter { it.classification == Classification.CLIENT_ONLY }
        require(personal.all { a -> profiles.any { a in it.artifacts } }) { "Personal artifacts are no longer authorized" }
        if (dry) { plan.operations.forEach(::println); return }
        Installer(root.instance(instance)).apply(plan)
        runCatching { api.request("/api/v1/sync/reports", "POST", wire.encodeToString(SyncReport(current.serverId,current.revision,"SUCCESS"))) }
            .onFailure { logger.warn { "Local installation succeeded; online history acknowledgement failed" } }
        println("Installation verified")
    }
/**
 * Verifies the managed files locally and then against the currently published manifest.
 *
 * @param instance Registered instance name.
 */
    @Command(name="verify") fun verify(@Option(names=["--instance"], required=true) instance: String) {
        val installer=Installer(root.instance(instance)); require(installer.verify()) { "Managed file verification failed" }
        val manifest=checkNotNull(installer.state().manifest) { "No managed manifest found" }; val current=wire.decodeFromString<Manifest>(root.api().request("/api/v1/servers/${uuid(manifest.serverId)}/manifest"))
        require(current == manifest) { "Local files verified, but a newer manifest is published" }; println("Managed artifacts verified against current published manifest")
    }
/**
 * Restores the state before the last interrupted or completed apply from its journal and backups.
 *
 * @param instance Registered instance name.
 */
    @Command(name="rollback") fun rollback(@Option(names=["--instance"], required=true) instance: String) { Installer(root.instance(instance)).rollback(); println("Previous managed state restored") }
/**
 * Prints the cached manifest with its age without any network access; the current server state is explicitly reported as unknown.
 *
 * @param server Server UUID.
 */
    @Command(name="cached") fun cached(@Option(names=["--server"], required=true) server: String) {
        uuid(server); val at=Instant.parse(Files.readString(root.stateDir.resolve("manifest-$server.cached-at"))); println("Cached at $at; age ${java.time.Duration.between(at,Instant.now()).seconds}s; current server state is unknown")
        println(Files.readString(root.stateDir.resolve("manifest-$server.json")))
    }
}
/**
 * Computes the wait before the next automatic pass using exponential backoff and bounded jitter.
 *
 * @param baseMinutes Regular interval in minutes.
 * @param failures Number of consecutive failed passes.
 * @param jitter Random fraction in [0, 1) selecting up to 20% additional delay.
 * @return Delay in seconds, never above 60 minutes plus 20% jitter.
 */
fun nextDelaySeconds(baseMinutes: Int, failures: Int, jitter: Double): Long {
    require(baseMinutes in 1..1440 && failures >= 0 && jitter >= 0.0 && jitter < 1.0)
    val backoff = (baseMinutes.toLong() shl minOf(failures, 6)).coerceAtMost(maxOf(60L, baseMinutes.toLong()))
    return (backoff * 60 * (1.0 + 0.2 * jitter)).toLong()
}
/**
 * Picocli group `auto` for consent-based automatic synchronization of registered instances.
 *
 * @property root Client providing the API, state directory and registered instances.
 */
@Command(name="auto", mixinStandardHelpOptions=true, description=["Per-instance automatic synchronization (explicit consent required)"]) class AutoCommands(private val root: Client) {
    /** Settings store of the current state directory. */
    private val store get() = AutoStore(root.stateDir)
    /**
     * Enables automatic synchronization for one registered instance after explicit consent.
     *
     * Consent is given with `--yes` or by typing the instance name at a console prompt.
     *
     * @param instance Registered instance name.
     * @param server Server UUID to synchronize with.
     * @param policy Automatic policy; MANUAL is not a valid automatic policy.
     * @param profile Optional personal profile UUID to keep and install alongside the server mods.
     * @param yes Confirms consent non-interactively.
     */
    @Command(name="enable") fun enable(@Option(names=["--instance"], required=true) instance: String, @Option(names=["--server"], required=true) server: String,
        @Option(names=["--policy"], defaultValue="AUTO_APPROVED_INSTALL") policy: SyncPolicy, @Option(names=["--profile"]) profile: String?, @Option(names=["--yes"]) yes: Boolean) {
        require(policy != SyncPolicy.MANUAL) { "Use auto disable to return to manual synchronization" }
        Installer(root.instance(instance)).target()
        if (!yes) {
            val console=System.console() ?: error("Use --yes to confirm consent without a console")
            require(console.readLine("Allow $policy for instance $instance? Type the instance name to consent: ") == instance) { "Consent not given" }
        }
        val existing=store.load()[instance]
        store.save(instance, AutoSettings(existing?.instanceId ?: UUID.randomUUID().toString(), uuid(server), policy, Instant.now().toString(), profileId = profile?.let { uuid(it) }))
        println("Automatic synchronization enabled for $instance with $policy")
    }
    /**
     * Disables automatic synchronization and forgets its consent for one instance.
     *
     * @param instance Registered instance name.
     */
    @Command(name="disable") fun disable(@Option(names=["--instance"], required=true) instance: String) {
        println(if (store.remove(instance)) "Automatic synchronization disabled for $instance" else "Automatic synchronization was not enabled for $instance")
    }
    /** Prints the automatic synchronization state of every enabled instance. */
    @Command(name="status") fun status() {
        val all=store.load(); if (all.isEmpty()) println("No instance has automatic synchronization enabled")
        all.forEach { (name, s) -> println("$name server=${s.serverId} policy=${s.policy} lastRevision=${s.lastRevision ?: "none"} lastOutcome=${s.lastOutcome}") }
    }
    /**
     * Runs automatic synchronization once or periodically.
     *
     * @param instance Restrict the run to one instance; all enabled instances otherwise.
     * @param once Run a single pass and exit non-zero when a pass failed; intended for OS schedulers.
     * @param intervalMinutes Regular interval of the foreground loop.
     */
    @Command(name="run") fun run(@Option(names=["--instance"]) instance: String?, @Option(names=["--once"]) once: Boolean,
        @Option(names=["--interval-minutes"], defaultValue="15") intervalMinutes: Int) {
        var failures=0
        while (true) {
            val failed=runPass(instance)
            if (once) { check(!failed) { "Automatic synchronization failed" }; return }
            failures=if (failed) failures+1 else 0
            Thread.sleep(nextDelaySeconds(intervalMinutes, failures, java.util.concurrent.ThreadLocalRandom.current().nextDouble()) * 1000)
        }
    }
    /**
     * Executes one pass over the selected instances.
     *
     * @param only Instance to restrict to, or null for all.
     * @return True when any pass failed.
     */
    private fun runPass(only: String?): Boolean {
        val targets=store.load().filterKeys { only == null || it == only }
        require(only == null || targets.isNotEmpty()) { "Automatic synchronization is not enabled for $only" }
        var failed=false
        targets.forEach { (name, settings) ->
            val outcome=runCatching {
                val dir=root.instance(name)
                val (result, updated)=AutoSyncRunner(Installer(dir), name, settings, ApiGateway(root.api()), ProcessGameCheck(dir.toRealPath().toString())).runOnce()
                store.save(name, updated); result
            }.getOrElse { AutoOutcome.Failed(it.javaClass.simpleName) }
            if (outcome is AutoOutcome.Failed) failed=true
            println("$name: $outcome")
        }
        return failed
    }
    /**
     * Prints a scheduler definition that runs one pass periodically without a permanent service.
     *
     * @param platform `systemd` or `windows`.
     * @param executable Absolute path of the modsync executable.
     * @param intervalMinutes Check interval.
     */
    @Command(name="schedule") fun schedule(@Option(names=["--platform"], required=true) platform: String, @Option(names=["--executable"], required=true) executable: String,
        @Option(names=["--interval-minutes"], defaultValue="30") intervalMinutes: Int) {
        println(when (platform) { "systemd" -> SchedulerTemplates.systemd(executable, intervalMinutes); "windows" -> SchedulerTemplates.windows(executable, intervalMinutes); else -> error("Unsupported platform") })
    }
}
/**
 * Builds the command line with all subcommand groups and a handler that logs failures without details and returns exit code 1.
 *
 * @param root Client instance shared by the subcommands.
 * @return Configured [CommandLine].
 */
fun clientCommandLine(root: Client = Client()): CommandLine = CommandLine(root).apply {
    addSubcommand("config", ConfigCommands(root)); addSubcommand("connection", ConnectionCommands(root)); addSubcommand("servers", ServerCommands(root)); addSubcommand("instances", InstanceCommands(root)); addSubcommand("mods", ModCommands(root)); addSubcommand("personal", PersonalCommands(root)); addSubcommand("sync", SyncCommands(root)); addSubcommand("auto", AutoCommands(root))
    setExecutionExceptionHandler { ex, _, _ -> logger.error { "Command failed: ${ex.javaClass.simpleName}; check arguments, connectivity and local state" }; 1 }
}
/**
 * Runs the client command line and exits with its status code.
 *
 * @param args Command-line arguments passed to the Picocli parser.
 */
fun main(args: Array<String>) { exitProcess(clientCommandLine().execute(*args)) }
