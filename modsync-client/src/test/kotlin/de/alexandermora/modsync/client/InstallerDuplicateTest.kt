package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.ModInfo
import de.alexandermora.modsync.domain.OperationKind
import de.alexandermora.modsync.domain.SyncPlan
import de.alexandermora.modsync.fixtures.Fixtures
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

/** Verifies that applying a plan never leaves two versions of one mod and that removed files stay recoverable. */
class InstallerDuplicateTest {
    @TempDir lateinit var root: Path
    private lateinit var installer: Installer
    private lateinit var incoming: Path
    private lateinit var oldJar: Path

    /** Instance with an unmanaged `alpha` 1.0.0 and a prepared download of `alpha` 2.0.0. */
    @BeforeEach fun setUp() {
        incoming = Fixtures.modJar(root.resolve("alpha-2.0.jar"), ModInfo("alpha", "2.0.0"))
        installer = Installer(root) { FakeHttpClient(200, Files.readAllBytes(incoming)) }
        installer.configureTarget(InstanceTarget("1.21.1", "neoforge"))
        oldJar = Fixtures.modJar(installer.mods.resolve("alpha-1.0.jar"), ModInfo("alpha", "1.0.0"))
    }

    private fun artifact(mods: List<ModInfo>) =
        Artifact(
            "p",
            "v2",
            "alpha-2.0.jar",
            fileHash(incoming),
            Files.size(incoming),
            "https://cdn.modrinth.com/data/p/alpha-2.0.jar",
            setOf("1.21.1"),
            setOf("neoforge"),
            Classification.CLIENT_REQUIRED,
            mods = mods
        )

    private fun manifest(artifact: Artifact) = Manifest("s", 1, "1.21.1", "neoforge", null, listOf(artifact), "2026-01-01T00:00:00Z", "d")

    private fun plan(artifact: Artifact, approved: Set<String> = emptySet()): SyncPlan =
        Resolver().plan(manifest(artifact), emptyList(), Scanner().scan(installer.mods), installer.state().hashes, approvedRemovals = approved)

    private fun jars() = Files.list(installer.mods).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

    /** Without approval the unmanaged older file blocks the plan and nothing is installed. */
    @Test fun unapprovedDuplicateBlocksApply() {
        val blocked = plan(artifact(listOf(ModInfo("alpha", "2.0.0"))))
        assertTrue(blocked.blocked)
        assertFailsWith<IllegalArgumentException> { installer.apply(blocked) }
        assertEquals(listOf("alpha-1.0.jar"), jars())
    }

    /** An approved removal installs the new version, keeps a recoverable backup of the old file and leaves one version. */
    @Test fun approvedDuplicateIsReplacedAndBackedUp() {
        val oldHash = fileHash(oldJar)
        val plan = plan(artifact(listOf(ModInfo("alpha", "2.0.0"))), setOf("alpha-1.0.jar"))
        assertEquals(setOf(OperationKind.INSTALL, OperationKind.QUARANTINE_DUPLICATE), plan.operations.map { it.kind }.toSet())
        installer.apply(plan)
        assertEquals(listOf("alpha-2.0.jar"), jars())
        assertEquals(mapOf("alpha-2.0.jar" to fileHash(incoming)), installer.state().hashes)
        assertTrue(installer.verify())
        assertTrue(installer.unresolvedDuplicates().isEmpty())
        val backups = Files.walk(root.resolve(".modsync")).use { stream -> stream.filter { it.fileName.toString() == "alpha-1.0.jar" }.toList() }
        assertEquals(listOf(oldHash), backups.map { fileHash(it) })
    }

    /** Repeating the synchronization neither reinstalls the old version nor recreates a duplicate. */
    @Test fun repeatedSynchronizationIsIdempotent() {
        installer.apply(plan(artifact(listOf(ModInfo("alpha", "2.0.0"))), setOf("alpha-1.0.jar")))
        val again = plan(artifact(listOf(ModInfo("alpha", "2.0.0"))))
        assertEquals(listOf(OperationKind.KEEP), again.operations.map { it.kind })
        assertFalse(again.blocked)
        installer.apply(again)
        assertEquals(listOf("alpha-2.0.jar"), jars())
    }

    /** Rolling back an approved replacement restores the older file and removes the new one. */
    @Test fun rollbackRestoresTheRemovedDuplicate() {
        val oldHash = fileHash(oldJar)
        installer.apply(plan(artifact(listOf(ModInfo("alpha", "2.0.0"))), setOf("alpha-1.0.jar")))
        installer.rollback()
        assertEquals(listOf("alpha-1.0.jar"), jars())
        assertEquals(oldHash, fileHash(installer.mods.resolve("alpha-1.0.jar")))
        assertTrue(installer.state().hashes.isEmpty())
    }

    /** A manifest without mod identity cannot sneak a second version in: the downloaded JAR is inspected before anything changes. */
    @Test fun downloadedJarWithAnInstalledModIdIsRefusedWithoutChanges() {
        val legacy = plan(artifact(emptyList()))
        assertFalse(legacy.blocked)
        val error = assertFailsWith<IllegalArgumentException> { installer.apply(legacy) }
        assertTrue("alpha" in error.message.orEmpty() && "same mod" in error.message.orEmpty(), error.message)
        assertEquals(listOf("alpha-1.0.jar"), jars())
        assertFalse(Files.exists(root.resolve(".modsync/journal.json")))
    }

    /** The installation check lists every mod id provided by more than one installed file. */
    @Test fun unresolvedDuplicatesNameTheFiles() {
        assertTrue(installer.unresolvedDuplicates().isEmpty())
        Fixtures.modJar(installer.mods.resolve("alpha-copy.jar"), ModInfo("alpha", "1.0.0"), payload = "x")
        assertEquals(listOf("mod alpha is provided by alpha-1.0.jar, alpha-copy.jar"), installer.unresolvedDuplicates())
    }
}
