package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.ClientActivity
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.OperationKind
import de.alexandermora.modsync.domain.PlanOperation
import de.alexandermora.modsync.domain.SyncPlan
import de.alexandermora.modsync.domain.SyncPolicy
import de.alexandermora.modsync.fixtures.Fixtures
import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.SyncReport
import de.alexandermora.modsync.scanner.directoryFingerprint
import de.alexandermora.modsync.scanner.fileHash
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies automatic synchronization policy, scheduling helpers and the runner's safety behavior. */
class AutoSyncTest {
    @TempDir lateinit var root: Path
    private val server = UUID.randomUUID().toString()
    private val instanceId = UUID.randomUUID().toString()
    private lateinit var installer: Installer

    /** Recording gateway with a fixed published manifest. */
    private class FakeGateway(var manifest: Manifest, var revision: Long? = manifest.revision) : AutoSyncGateway {
        val inventories = mutableListOf<ClientInventoryReport>()
        val syncs = mutableListOf<SyncReport>()
        var manifestFetches = 0
        var personal: List<Artifact> = emptyList()

        override fun publishedRevision(serverId: String) = revision

        override fun manifest(serverId: String): Manifest {
            manifestFetches++
            return manifest
        }

        override fun reportInventory(report: ClientInventoryReport) {
            inventories += report
        }

        override fun personalArtifacts(profileId: String) = personal

        override fun reportSync(report: SyncReport) {
            syncs += report
        }
    }

    /** Creates an instance configured for 1.21.1 NeoForge. */
    @BeforeEach fun prepare() {
        installer = Installer(root)
        installer.configureTarget(InstanceTarget("1.21.1", "neoforge"))
    }

    /** Builds a client-required artifact whose hash is [hash]. */
    private fun artifact(name: String, hash: String, project: String = name) =
        Artifact(
            project,
            "v-$hash".take(20),
            name,
            hash,
            10,
            "https://cdn.modrinth.com/data/x/versions/y/$name",
            setOf("1.21.1"),
            setOf("neoforge"),
            Classification.CLIENT_REQUIRED
        )

    /** Builds a manifest at [revision] with the supplied artifacts. */
    private fun manifest(revision: Long, vararg artifacts: Artifact) =
        Manifest(server, revision, "1.21.1", "neoforge", null, artifacts.toList(), "2026-01-01T00:00:00Z", "d".repeat(128))

    /** Creates a runner with the supplied collaborators. */
    private fun runner(
        gateway: FakeGateway,
        policy: SyncPolicy = SyncPolicy.AUTO_APPROVED_INSTALL,
        running: Boolean = false,
        settings: AutoSettings = AutoSettings(instanceId, server, policy, "2026-01-01T00:00:00Z"),
        apply: (SyncPlan) -> Unit = {}
    ) = AutoSyncRunner(installer, "main", settings, gateway, { running }, apply = apply)

    /** Policies permit strictly growing operation sets and never allow blocked plans. */
    @Test fun policyMatrix() {
        fun plan(vararg kinds: OperationKind) = SyncPlan(manifest(1), kinds.map { PlanOperation(it, "a.jar", "r") }, "x")
        assertTrue(policyAllows(plan(OperationKind.INSTALL, OperationKind.KEEP), SyncPolicy.AUTO_APPROVED_INSTALL))
        assertFalse(policyAllows(plan(OperationKind.UPDATE), SyncPolicy.AUTO_APPROVED_INSTALL))
        assertTrue(policyAllows(plan(OperationKind.UPDATE), SyncPolicy.AUTO_APPROVED_UPDATE))
        assertFalse(policyAllows(plan(OperationKind.REMOVE_MANAGED), SyncPolicy.AUTO_APPROVED_UPDATE))
        assertTrue(policyAllows(plan(OperationKind.REMOVE_MANAGED), SyncPolicy.AUTO_ALL_MANAGED))
        assertFalse(policyAllows(plan(OperationKind.CONFLICT), SyncPolicy.AUTO_ALL_MANAGED))
        assertFalse(policyAllows(plan(OperationKind.INSTALL), SyncPolicy.MANUAL))
    }

    /** Manual policy never contacts the server. */
    @Test fun manualDoesNothing() {
        val gateway = FakeGateway(manifest(1))
        val (outcome, _) = runner(gateway, SyncPolicy.MANUAL).runOnce()
        assertEquals(AutoOutcome.Disabled, outcome)
        assertEquals(0, gateway.manifestFetches)
    }

    /** A recent successful pass at the same revision skips manifest download and rescanning. */
    @Test fun unchangedRevisionSkipsWork() {
        val gateway = FakeGateway(manifest(3))
        val settings = AutoSettings(instanceId, server, SyncPolicy.AUTO_APPROVED_INSTALL, "t", 3, System.currentTimeMillis() / 1000, 1, "SUCCESS", modsFingerprint = directoryFingerprint(installer.mods))
        val (outcome, _) = runner(gateway, settings = settings).runOnce()
        assertEquals(AutoOutcome.UpToDate(3), outcome)
        assertEquals(0, gateway.manifestFetches)
        assertTrue(gateway.inventories.isEmpty())
    }

    /** A running game defers installation, applies nothing and reports PENDING_GAME_EXIT. */
    @Test fun runningGameDefersInstall() {
        val gateway = FakeGateway(manifest(1, artifact("new.jar", "a".repeat(128))))
        var applied = false
        val (outcome, _) = runner(gateway, running = true) { applied = true }.runOnce()
        assertEquals(AutoOutcome.DeferredGameRunning, outcome)
        assertFalse(applied)
        assertEquals(ClientActivity.PENDING_GAME_EXIT, gateway.inventories.single().activity)
    }

    /** An approved install is applied, reported and the revision recorded. */
    @Test fun approvedInstallIsApplied() {
        val gateway = FakeGateway(manifest(2, artifact("new.jar", "a".repeat(128))))
        var plan: SyncPlan? = null
        val (outcome, settings) = runner(gateway) { plan = it }.runOnce()
        assertEquals(AutoOutcome.Applied(2), outcome)
        assertEquals(OperationKind.INSTALL, plan!!.operations.single().kind)
        assertEquals(listOf("SUCCESS"), gateway.syncs.map { it.status })
        assertEquals(2L, settings.lastRevision)
        assertEquals(ClientActivity.IDLE, gateway.inventories.single().activity)
    }

    /** Personal profile mods are planned with the server mods and the profile id is reported centrally. */
    @Test
    fun profileArtifactsArePlannedAndReported() {
        val gateway = FakeGateway(manifest(2))
        gateway.personal = listOf(artifact("mine.jar", "f".repeat(128)).copy(classification = Classification.CLIENT_ONLY))
        val profile = UUID.randomUUID().toString()
        var plan: SyncPlan? = null
        val settings = AutoSettings(instanceId, server, SyncPolicy.AUTO_APPROVED_INSTALL, "t", profileId = profile)
        val (outcome, _) = runner(gateway, settings = settings) { plan = it }.runOnce()
        assertEquals(AutoOutcome.Applied(2), outcome)
        assertEquals(listOf("mine.jar"), plan!!.operations.filter { it.kind == OperationKind.INSTALL }.map { it.fileName })
        assertEquals(profile, gateway.inventories.single().profileId)
    }

    /** Failures are reported as SYNC_FAILED and leave the revision unrecorded. */
    @Test fun failedApplyIsReported() {
        val gateway = FakeGateway(manifest(2, artifact("new.jar", "a".repeat(128))))
        val (outcome, settings) = runner(gateway) { error("download failed") }.runOnce()
        assertTrue(outcome is AutoOutcome.Failed)
        assertEquals(listOf("FAILED"), gateway.syncs.map { it.status })
        assertEquals(ClientActivity.SYNC_FAILED, gateway.inventories.single().activity)
        assertEquals(null, settings.lastRevision)
    }

    /** An update exceeds the install-only policy and stays pending. */
    @Test fun updateExceedsInstallPolicy() {
        val old = Fixtures.jar(installer.mods.resolve("m.jar"), "m")
        val oldHash = fileHash(old)
        secureState(oldHash)
        val gateway = FakeGateway(manifest(2, artifact("m.jar", "b".repeat(128))))
        var applied = false
        val (outcome, _) = runner(gateway) { applied = true }.runOnce()
        assertTrue(outcome is AutoOutcome.Pending)
        assertFalse(applied)
        assertEquals(ClientActivity.SYNC_PENDING, gateway.inventories.single().activity)
    }

    /** Unmanaged local mods are preserved and reported as unmanaged without paths. */
    @Test fun unmanagedModsArePreservedAndReported() {
        val personal = Fixtures.jar(installer.mods.resolve("personal.jar"), "p")
        val gateway = FakeGateway(manifest(1))
        val (outcome, _) = runner(gateway, SyncPolicy.AUTO_ALL_MANAGED).runOnce()
        assertEquals(AutoOutcome.UpToDate(1), outcome)
        assertTrue(Files.exists(personal))
        val mod =
            gateway.inventories
                .single()
                .mods
                .single()
        assertEquals("personal.jar", mod.fileName)
        assertFalse(mod.managed)
    }

    /** Managed files from outside the previous server manifest (personal mods) are never auto-removed. */
    @Test fun personalManagedFilesAreNotRemoved() {
        val jar = Fixtures.jar(installer.mods.resolve("personal.jar"), "p")
        val hash = fileHash(jar)
        Files.writeString(root.resolve(".modsync/managed.json"), """{"hashes":{"personal.jar":"$hash"}}""")
        val gateway = FakeGateway(manifest(2))
        var applied = false
        val (outcome, _) = runner(gateway, SyncPolicy.AUTO_ALL_MANAGED) { applied = true }.runOnce()
        assertTrue(outcome is AutoOutcome.Pending)
        assertFalse(applied)
        assertTrue(Files.exists(jar))
    }

    /** Writes managed state marking m.jar as installed with [hash]. */
    private fun secureState(hash: String) =
        Files.writeString(root.resolve(".modsync/managed.json"), """{"hashes":{"m.jar":"$hash"}}""")

    /** Backoff grows with failures, stays bounded and jitter adds at most 20%. */
    @Test fun delayIsBounded() {
        assertEquals(900L, nextDelaySeconds(15, 0, 0.0))
        assertEquals(1800L, nextDelaySeconds(15, 1, 0.0))
        assertEquals(3600L, nextDelaySeconds(15, 10, 0.0))
        assertEquals(990L, nextDelaySeconds(15, 0, 0.5))
        assertFailsWith<IllegalArgumentException> { nextDelaySeconds(0, 0, 0.0) }
    }

    /** Scheduler templates embed the executable literally and reject injection characters. */
    @Test fun schedulerTemplates() {
        val unit = SchedulerTemplates.systemd("/opt/modsync/bin/modsync", 30)
        assertTrue(unit.contains("ExecStart=\"/opt/modsync/bin/modsync\" auto run --once"))
        assertTrue(unit.contains("OnUnitActiveSec=30min"))
        assertTrue(SchedulerTemplates.windows("C:\\modsync\\modsync.exe", 30).contains("/MO 30"))
        assertFailsWith<IllegalArgumentException> { SchedulerTemplates.systemd("/bin/x\"; rm", 30) }
        assertFailsWith<IllegalArgumentException> { SchedulerTemplates.windows("a", 1) }
    }

    /** Settings round-trip through the store and can be removed. */
    @Test fun storeRoundTrip() {
        val store = AutoStore(root.resolve("state"))
        val settings = AutoSettings(instanceId, server, SyncPolicy.AUTO_APPROVED_UPDATE, "t")
        store.save("main", settings)
        assertEquals(mapOf("main" to settings), store.load())
        assertTrue(store.remove("main"))
        assertFalse(store.remove("main"))
        assertEquals(emptyMap(), store.load())
    }
}
