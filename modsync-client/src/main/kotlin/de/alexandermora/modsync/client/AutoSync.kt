package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.ClientActivity
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.OperationKind
import de.alexandermora.modsync.domain.SyncPlan
import de.alexandermora.modsync.domain.SyncPolicy
import de.alexandermora.modsync.domain.safeFileName
import de.alexandermora.modsync.domain.validHash
import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.ClientModEntry
import de.alexandermora.modsync.protocol.SyncReport
import de.alexandermora.modsync.resolver.Resolver
import de.alexandermora.modsync.scanner.Scanner
import java.time.Clock
import java.time.Duration

private val MUTATING = setOf(OperationKind.INSTALL, OperationKind.UPDATE, OperationKind.REMOVE_MANAGED)
private val FULL_CHECK_INTERVAL: Duration = Duration.ofHours(24)
private const val MAX_REPORTED_MODS = 2000

/** Result of one automatic synchronization pass. */
sealed interface AutoOutcome {
    /** Synchronization is disabled for the instance. */
    data object Disabled : AutoOutcome

    /** Nothing to do; the published revision is already applied. */
    data class UpToDate(val revision: Long) : AutoOutcome

    /** Changes exist but the consented policy or a conflict forbids applying them automatically. */
    data class Pending(val reason: String) : AutoOutcome

    /** Changes are approved but Minecraft is running; installation is deferred until the game exits. */
    data object DeferredGameRunning : AutoOutcome

    /** The manifest revision was installed and verified. */
    data class Applied(val revision: Long) : AutoOutcome

    /** The pass failed; local files were rolled back or untouched. */
    data class Failed(val reason: String) : AutoOutcome
}

/** Detects whether Minecraft is using an instance directory. */
fun interface GameRunningCheck {
    /**
     * Checks for a running game.
     *
     * @return True when a game process appears to use the instance.
     */
    fun isRunning(): Boolean
}

/**
 * Process-based [GameRunningCheck]: a Java process whose command line references the instance directory.
 *
 * Launchers that do not pass the game directory on the command line cannot be detected; the check
 * never inspects or signals the process.
 *
 * @property instanceDir Real path of the instance directory.
 */
class ProcessGameCheck(private val instanceDir: String) : GameRunningCheck {
    /**
     * Scans visible processes for one running Java against the instance directory.
     *
     * @return True when such a process exists.
     */
    override fun isRunning(): Boolean =
        ProcessHandle.allProcesses().anyMatch { process ->
            val command = process.info().commandLine().orElse("")
            command.contains(instanceDir) && command.contains("java", ignoreCase = true)
        }
}

/** Server operations needed by automatic synchronization, abstracted from HTTP for the runner. */
interface AutoSyncGateway {
    /**
     * Reads the latest published manifest revision without downloading the manifest.
     *
     * @param serverId Server UUID.
     * @return Published revision, or null when nothing is published.
     */
    fun publishedRevision(serverId: String): Long?

    /**
     * Downloads the latest published manifest.
     *
     * @param serverId Server UUID.
     * @return The manifest.
     */
    fun manifest(serverId: String): Manifest

    /**
     * Uploads an inventory report.
     *
     * @param report Report to upload.
     */
    fun reportInventory(report: ClientInventoryReport)

    /**
     * Uploads the outcome of an installation.
     *
     * @param report Outcome report.
     */
    fun reportSync(report: SyncReport)

    /**
     * Loads the client-only artifacts of one of the user's personal profiles.
     *
     * @param profileId Personal profile UUID.
     * @return The profile's artifacts, as currently authorized by the server.
     */
    fun personalArtifacts(profileId: String): List<Artifact>
}

/**
 * Decides whether a plan may be applied without further approval under a consented policy.
 *
 * Plans containing conflicts or manual actions are never allowed. Each policy permits a
 * strictly larger set of file-changing operations than the previous one.
 *
 * @param plan Deterministic plan.
 * @param policy Policy the user consented to.
 * @return True when every file-changing operation is covered by the policy.
 */
fun policyAllows(plan: SyncPlan, policy: SyncPolicy): Boolean {
    if (plan.blocked) return false
    val allowed =
        when (policy) {
            SyncPolicy.MANUAL -> emptySet()
            SyncPolicy.AUTO_APPROVED_INSTALL -> setOf(OperationKind.INSTALL)
            SyncPolicy.AUTO_APPROVED_UPDATE -> setOf(OperationKind.INSTALL, OperationKind.UPDATE)
            SyncPolicy.AUTO_ALL_MANAGED -> MUTATING
        }
    return plan.operations.filter { it.kind in MUTATING }.all { it.kind in allowed }
}

/**
 * Runs one safe automatic synchronization pass for a registered instance.
 *
 * The runner reuses [Installer] for planning-compatible application, journaling, backups and rollback.
 * It never installs while the game runs, never exceeds the consented policy, and uploads a fresh inventory
 * after every pass that inspected local files.
 *
 * @property installer Installer bound to the instance.
 * @property instanceName Registered instance name.
 * @property settings Persisted automatic-sync settings of the instance.
 * @property gateway Server access.
 * @property gameRunning Running-game detection.
 * @property clock Time source.
 * @property apply Applies an approved plan; defaults to [Installer.apply].
 */
class AutoSyncRunner(
    private val installer: Installer,
    private val instanceName: String,
    private val settings: AutoSettings,
    private val gateway: AutoSyncGateway,
    private val gameRunning: GameRunningCheck,
    private val clock: Clock = Clock.systemUTC(),
    private val apply: (SyncPlan) -> Unit = installer::apply
) {
    /**
     * Executes one pass.
     *
     * Order: policy gate, cheap revision check, manifest retrieval, target validation, deterministic plan,
     * policy check, running-game check, application with verification, inventory upload.
     *
     * @return The outcome together with the updated settings to persist.
     */
    @Suppress("TooGenericExceptionCaught") // a pass boundary must turn any failure into a reported outcome
    fun runOnce(): Pair<AutoOutcome, AutoSettings> {
        if (settings.policy == SyncPolicy.MANUAL) return AutoOutcome.Disabled to settings
        val now = clock.instant().epochSecond
        val published =
            gateway.publishedRevision(settings.serverId)
                ?: return AutoOutcome.Failed("No manifest published") to settings
        if (published == settings.lastRevision && settings.lastOutcome == SUCCESS && now - settings.lastFullCheckEpoch < FULL_CHECK_INTERVAL.seconds) {
            return AutoOutcome.UpToDate(published) to settings
        }
        return try {
            synchronize(gateway.manifest(settings.serverId), now)
        } catch (error: Exception) {
            AutoOutcome.Failed(error.javaClass.simpleName) to settings.copy(lastOutcome = FAILED)
        }
    }

    /**
     * Plans and, when permitted, applies the manifest.
     *
     * @param manifest Retrieved manifest.
     * @param now Current epoch seconds.
     * @return Outcome and updated settings.
     */
    @Suppress("TooGenericExceptionCaught") // installation failures of any kind are rolled back and reported, never propagated
    private fun synchronize(manifest: Manifest, now: Long): Pair<AutoOutcome, AutoSettings> {
        installer.validateTarget(manifest)
        val personal = settings.profileId?.let(gateway::personalArtifacts).orEmpty()
        val plan =
            installer.locked {
                Resolver().plan(manifest, personal, Scanner().scan(installer.mods), installer.state().hashes, false)
            }
        val changes = plan.operations.any { it.kind in MUTATING }
        if (!changes && !plan.blocked) {
            return finish(AutoOutcome.UpToDate(manifest.revision), manifest, ClientActivity.IDLE, now, applied = manifest.revision)
        }
        if (!policyAllows(plan, settings.policy) || removesNonServerFile(plan)) {
            return finish(AutoOutcome.Pending("Plan exceeds the approved policy or needs manual action"), manifest, ClientActivity.SYNC_PENDING, now)
        }
        if (gameRunning.isRunning()) {
            return finish(AutoOutcome.DeferredGameRunning, manifest, ClientActivity.PENDING_GAME_EXIT, now)
        }
        return try {
            apply(plan)
            runCatching { gateway.reportSync(SyncReport(manifest.serverId, manifest.revision, "SUCCESS")) }
            finish(AutoOutcome.Applied(manifest.revision), manifest, ClientActivity.IDLE, now, applied = manifest.revision)
        } catch (error: Exception) {
            runCatching { gateway.reportSync(SyncReport(manifest.serverId, manifest.revision, "FAILED")) }
            finish(AutoOutcome.Failed(error.javaClass.simpleName), manifest, ClientActivity.SYNC_FAILED, now)
        }
    }

    /**
     * Detects removals of managed files that the previously applied server manifest never listed.
     *
     * Such files come from personal profiles, which automatic mode does not resolve, so removing them
     * automatically could destroy personal mods.
     *
     * @param plan Candidate plan.
     * @return True when the plan would remove a file not owned by the previous server manifest.
     */
    private fun removesNonServerFile(plan: SyncPlan): Boolean {
        val serverFiles =
            installer
                .state()
                .manifest
                ?.artifacts
                ?.map { it.fileName }
                .orEmpty()
                .toSet()
        return plan.operations.any { it.kind == OperationKind.REMOVE_MANAGED && it.fileName !in serverFiles }
    }

    /**
     * Uploads a fresh inventory (best effort) and computes the settings to persist.
     *
     * @param outcome Outcome of the pass.
     * @param manifest Manifest the pass worked against.
     * @param activity Activity to report.
     * @param now Current epoch seconds.
     * @param applied Revision now applied, or null when unchanged.
     * @return Outcome and updated settings.
     */
    private fun finish(outcome: AutoOutcome, manifest: Manifest, activity: ClientActivity, now: Long, applied: Long? = null): Pair<AutoOutcome, AutoSettings> {
        val sequence = maxOf(settings.lastSequence + 1, clock.millis())
        val success = outcome is AutoOutcome.UpToDate || outcome is AutoOutcome.Applied
        val updated =
            settings.copy(
                lastSequence = sequence,
                lastRevision = if (success) manifest.revision else settings.lastRevision,
                lastFullCheckEpoch = if (success) now else settings.lastFullCheckEpoch,
                lastOutcome = if (success) SUCCESS else outcome.javaClass.simpleName.uppercase()
            )
        runCatching { gateway.reportInventory(inventoryReport(manifest, activity, sequence, applied ?: installer.state().manifest?.revision)) }
        return outcome to updated
    }

    /**
     * Builds the inventory report from the current files.
     *
     * Only file names, hashes and recognized identifiers leave the machine; managed ownership
     * requires that the file still carries the hash recorded at installation.
     *
     * @param manifest Manifest used to recognize project and version identifiers by hash.
     * @param activity Activity to report.
     * @param sequence Report sequence.
     * @param applied Applied manifest revision, if any.
     * @return Report ready for upload.
     */
    private fun inventoryReport(manifest: Manifest, activity: ClientActivity, sequence: Long, applied: Long?): ClientInventoryReport {
        val target = installer.target()
        val managed = installer.state().hashes
        val known = manifest.artifacts.associateBy { it.sha512 }
        val mods =
            Scanner()
                .scan(installer.mods)
                .filter { validHash(it.sha512) && safeFileName(it.fileName) }
                .distinctBy { it.sha512 }
                .take(MAX_REPORTED_MODS)
                .map { ClientModEntry(it.sha512, it.fileName, known[it.sha512]?.projectId, known[it.sha512]?.versionId, managed[it.fileName] == it.sha512) }
        return ClientInventoryReport(
            instanceId = settings.instanceId,
            instanceName = instanceName,
            serverId = settings.serverId,
            minecraftVersion = target.minecraftVersion,
            loader = target.loader,
            loaderVersion = target.loaderVersion,
            sequence = sequence,
            appliedRevision = applied,
            syncPolicy = settings.policy,
            profileId = settings.profileId,
            activity = activity,
            mods = mods
        )
    }

    private companion object {
        const val SUCCESS = "SUCCESS"
        const val FAILED = "FAILED"
    }
}
