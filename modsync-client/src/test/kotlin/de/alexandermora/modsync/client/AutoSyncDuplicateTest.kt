package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.ClientActivity
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.ModInfo
import de.alexandermora.modsync.domain.OperationKind
import de.alexandermora.modsync.domain.SyncPlan
import de.alexandermora.modsync.domain.SyncPolicy
import de.alexandermora.modsync.fixtures.Fixtures
import de.alexandermora.modsync.protocol.ClientInventoryReport
import de.alexandermora.modsync.protocol.SyncReport
import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.scanner.fileHash
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies that automatic synchronization never reports an up-to-date instance while duplicate mods remain. */
class AutoSyncDuplicateTest {
    @TempDir lateinit var root: Path
    private val server = UUID.randomUUID().toString()
    private val instanceId = UUID.randomUUID().toString()
    private lateinit var installer: Installer

    /** Gateway serving one manifest and recording requests. */
    private class Gateway(var manifest: Manifest) : AutoSyncGateway {
        val inventories = mutableListOf<ClientInventoryReport>()
        var manifestFetches = 0

        override fun publishedRevision(serverId: String): Long = manifest.revision

        override fun manifest(serverId: String): Manifest {
            manifestFetches++
            return manifest
        }

        override fun reportInventory(report: ClientInventoryReport) {
            inventories += report
        }

        override fun reportSync(report: SyncReport) = Unit

        override fun personalArtifacts(profileId: String): List<Artifact> = emptyList()
    }

    @BeforeEach fun prepare() {
        installer = Installer(root)
        installer.configureTarget(InstanceTarget("1.21.1", "neoforge"))
    }

    private fun jar(name: String, mod: String, version: String, payload: String = ""): Path = Fixtures.modJar(installer.mods.resolve(name), ModInfo(mod, version), payload = payload)

    private fun artifact(jar: Path, mod: String, version: String?, identity: Boolean = true) =
        Artifact(
            mod,
            "v-${jar.fileName}",
            jar.fileName.toString(),
            fileHash(jar),
            Files.size(jar),
            "https://cdn.modrinth.com/data/x/${jar.fileName}",
            setOf("1.21.1"),
            setOf("neoforge"),
            Classification.CLIENT_REQUIRED,
            mods = if (identity) listOf(ModInfo(mod, version)) else emptyList()
        )

    private fun manifest(revision: Long, vararg artifacts: Artifact) = Manifest(server, revision, "1.21.1", "neoforge", null, artifacts.toList(), "2026-01-01T00:00:00Z", "d")

    private fun managed(previous: Manifest?, vararg jars: Path) =
        secureWrite(root.resolve(".modsync/managed.json"), wire.encodeToString(ManagedState.serializer(), ManagedState(jars.associate { it.fileName.toString() to fileHash(it) }, previous)))

    private fun runner(gateway: Gateway, policy: SyncPolicy, settings: AutoSettings? = null, apply: (SyncPlan) -> Unit = {}) =
        AutoSyncRunner(installer, "main", settings ?: AutoSettings(instanceId, server, policy, "t"), gateway, { false }, apply = apply)

    /** An unmanaged older file next to the pinned one blocks the pass even under the widest policy. */
    @Test fun unmanagedOlderDuplicateNeverReportsUpToDate() {
        val current = jar("alpha-2.0.jar", "alpha", "2.0.0")
        jar("alpha-1.0.jar", "alpha", "1.0.0")
        val manifest = manifest(2, artifact(current, "alpha", "2.0.0"))
        managed(manifest, current)
        val gateway = Gateway(manifest)
        var applied = false
        val (outcome, settings) = runner(gateway, SyncPolicy.AUTO_ALL_MANAGED) { applied = true }.runOnce()
        assertTrue(outcome is AutoOutcome.Pending && "alpha-1.0.jar" in outcome.reason, outcome.toString())
        assertFalse(applied)
        assertEquals(ClientActivity.SYNC_PENDING, gateway.inventories.single().activity)
        assertTrue(settings.lastOutcome != "SUCCESS")
    }

    /** A duplicate added after a successful pass is found even though the manifest revision did not change. */
    @Test fun unchangedRevisionRescansWhenTheModsFolderChanges() {
        val current = jar("alpha-2.0.jar", "alpha", "2.0.0")
        val manifest = manifest(2, artifact(current, "alpha", "2.0.0"))
        managed(manifest, current)
        val gateway = Gateway(manifest)
        val (first, settings) = runner(gateway, SyncPolicy.AUTO_APPROVED_INSTALL).runOnce()
        assertEquals(AutoOutcome.UpToDate(2), first)
        assertNotNull(settings.modsFingerprint)
        jar("alpha-1.0.jar", "alpha", "1.0.0")
        val (second, _) = runner(gateway, SyncPolicy.AUTO_APPROVED_INSTALL, settings).runOnce()
        assertTrue(second is AutoOutcome.Pending, second.toString())
        assertEquals(2, gateway.manifestFetches)
    }

    /** An untouched folder at an unchanged revision still skips the full pass. */
    @Test fun unchangedFolderStillSkipsTheFullPass() {
        val current = jar("alpha-2.0.jar", "alpha", "2.0.0")
        val manifest = manifest(2, artifact(current, "alpha", "2.0.0"))
        managed(manifest, current)
        val gateway = Gateway(manifest)
        val (_, settings) = runner(gateway, SyncPolicy.AUTO_APPROVED_INSTALL).runOnce()
        val (again, _) = runner(gateway, SyncPolicy.AUTO_APPROVED_INSTALL, settings).runOnce()
        assertEquals(AutoOutcome.UpToDate(2), again)
        assertEquals(1, gateway.manifestFetches)
    }

    /** Settings written before the fingerprint existed cannot prove the folder is unchanged. */
    @Test fun settingsWithoutFingerprintForceAFullPass() {
        val current = jar("alpha-2.0.jar", "alpha", "2.0.0")
        val manifest = manifest(2, artifact(current, "alpha", "2.0.0"))
        managed(manifest, current)
        val gateway = Gateway(manifest)
        val legacy = AutoSettings(instanceId, server, SyncPolicy.AUTO_APPROVED_INSTALL, "t", 2, System.currentTimeMillis() / 1000, 1, "SUCCESS")
        runner(gateway, SyncPolicy.AUTO_APPROVED_INSTALL, legacy).runOnce()
        assertEquals(1, gateway.manifestFetches)
    }

    /** A manifest without mod identity cannot link the files, but duplicates among installed files still block UpToDate. */
    @Test fun duplicatesBlockUpToDateEvenWithoutManifestIdentity() {
        val current = jar("alpha-2.0.jar", "alpha", "2.0.0")
        jar("alpha-copy.jar", "alpha", "2.0.0", payload = "copy")
        val manifest = manifest(2, artifact(current, "alpha", "2.0.0", identity = false))
        managed(manifest, current)
        val (outcome, _) = runner(Gateway(manifest), SyncPolicy.AUTO_ALL_MANAGED).runOnce()
        assertTrue(outcome is AutoOutcome.Pending && "alpha" in outcome.reason, outcome.toString())
    }

    /** A managed older version is replaced automatically only when the policy allows removals. */
    @Test fun managedOlderVersionIsRemovedOnlyWhenThePolicyAllows() {
        val old = jar("alpha-1.0.jar", "alpha", "1.0.0")
        val incoming = Fixtures.modJar(root.resolve("alpha-2.0.jar"), ModInfo("alpha", "2.0.0"))
        val previous = manifest(1, artifact(old, "alpha", "1.0.0"))
        managed(previous, old)
        val next = manifest(2, artifact(incoming, "alpha", "2.0.0"))
        var plan: SyncPlan? = null
        val (applied, _) = runner(Gateway(next), SyncPolicy.AUTO_ALL_MANAGED) { plan = it }.runOnce()
        assertEquals(AutoOutcome.Applied(2), applied)
        assertEquals(OperationKind.REMOVE_MANAGED, plan!!.operations.single { it.fileName == "alpha-1.0.jar" }.kind)
        var installOnlyApplied = false
        val (pending, _) = runner(Gateway(next), SyncPolicy.AUTO_APPROVED_INSTALL) { installOnlyApplied = true }.runOnce()
        assertTrue(pending is AutoOutcome.Pending)
        assertFalse(installOnlyApplied)
    }
}
