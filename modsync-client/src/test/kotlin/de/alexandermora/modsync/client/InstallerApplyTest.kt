package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.OperationKind
import de.alexandermora.modsync.domain.SyncPlan
import de.alexandermora.modsync.fixtures.Fixtures
import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.resolver.Resolver
import de.alexandermora.modsync.scanner.Scanner
import de.alexandermora.modsync.scanner.fileHash
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies applying, verifying and rolling back a plan that needs no downloads. */
class InstallerApplyTest {
    @TempDir lateinit var root: Path
    private lateinit var installer: Installer
    private lateinit var keptHash: String
    private lateinit var oldHash: String
    private lateinit var manifest: Manifest

    /** Instance with one kept mod and one previously managed mod that the manifest no longer selects. */
    @BeforeEach fun setUp() {
        installer = Installer(root)
        installer.configureTarget(InstanceTarget("1.21.1", "neoforge"))
        val kept = Fixtures.jar(installer.mods.resolve("kept.jar"), "kept")
        val old = Fixtures.jar(installer.mods.resolve("old.jar"), "old")
        keptHash = fileHash(kept)
        oldHash = fileHash(old)
        val artifact =
            Artifact(
                "p1",
                "v1",
                "kept.jar",
                keptHash,
                Files.size(kept),
                null,
                setOf("1.21.1"),
                setOf("neoforge"),
                Classification.CLIENT_REQUIRED
            )
        manifest = Manifest("s", 1, "1.21.1", "neoforge", null, listOf(artifact), "2026-01-01T00:00:00Z", "d")
        secureWrite(root.resolve(".modsync/managed.json"), wire.encodeToString(ManagedState.serializer(), ManagedState(mapOf("old.jar" to oldHash))))
    }

    private fun plan(): SyncPlan = Resolver().plan(manifest, emptyList(), Scanner().scan(installer.mods), installer.state().hashes)

    /** The plan keeps the pinned mod and removes the managed one. */
    @Test fun planShape() {
        assertEquals(setOf(OperationKind.KEEP, OperationKind.REMOVE_MANAGED), plan().operations.map { it.kind }.toSet())
    }

    /** Applying removes only the managed mod, records state and verifies. */
    @Test fun applyRemovesManagedAndRecordsState() {
        installer.apply(plan())
        assertFalse(Files.exists(installer.mods.resolve("old.jar")))
        assertTrue(Files.exists(installer.mods.resolve("kept.jar")))
        assertEquals(manifest, installer.state().manifest)
        assertEquals(mapOf("kept.jar" to keptHash), installer.state().verifiedHashes)
        assertTrue(installer.state().hashes.isEmpty())
        assertTrue(installer.verify())
    }

    /** A recorded synchronization stops verifying once a managed file is edited. */
    @Test fun verifyDetectsLocalEdit() {
        installer.apply(plan())
        Files.writeString(installer.mods.resolve("kept.jar"), "edited")
        assertFalse(installer.verify())
    }

    /** Rolling back a completed apply restores the removed mod and previous state. */
    @Test fun rollbackRestoresRemovedMod() {
        installer.apply(plan())
        installer.rollback()
        assertEquals(oldHash, fileHash(installer.mods.resolve("old.jar")))
        assertEquals(mapOf("old.jar" to oldHash), installer.state().hashes)
    }

    /** Plans are rejected when the local inventory changed after planning. */
    @Test fun staleInventoryRejected() {
        val stale = plan()
        Fixtures.jar(installer.mods.resolve("late.jar"), "late")
        assertFailsWith<IllegalArgumentException> { installer.apply(stale) }
        assertTrue(Files.exists(installer.mods.resolve("old.jar")))
    }

    /** Plans with conflicts never touch files. */
    @Test fun blockedPlanRejected() {
        val blocked =
            plan().copy(
                operations =
                    plan().operations +
                        de.alexandermora.modsync.domain
                            .PlanOperation(OperationKind.CONFLICT, "x.jar", "conflict")
            )
        assertFailsWith<IllegalArgumentException> { installer.apply(blocked) }
        assertTrue(Files.exists(installer.mods.resolve("old.jar")))
    }

    /** Verification needs a recorded synchronization. */
    @Test fun verifyWithoutStateFails() {
        assertFailsWith<IllegalArgumentException> { installer.verify() }
    }

    /** A second installer cannot hold the instance lock concurrently. */
    @Test fun lockIsExclusive() {
        installer.locked { assertFailsWith<IllegalStateException> { Installer(root).locked { } } }
    }
}
