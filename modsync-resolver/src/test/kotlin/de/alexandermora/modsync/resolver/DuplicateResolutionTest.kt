package de.alexandermora.modsync.resolver

import de.alexandermora.modsync.domain.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies that the planner never keeps or installs two versions of one identified mod. */
class DuplicateResolutionTest {
    private fun hash(char: Char) = char.toString().repeat(128)

    private fun pinned(file: String, mod: String, version: String?, hashChar: Char, project: String = mod, mods: List<ModInfo>? = null) =
        Artifact(
            project,
            "v-$file",
            file,
            hash(hashChar),
            10,
            "https://cdn.modrinth.com/data/x/$file",
            setOf("1.21.1"),
            setOf("neoforge"),
            Classification.CLIENT_REQUIRED,
            mods = mods ?: listOf(ModInfo(mod, version))
        )

    private fun jar(file: String, hashChar: Char, vararg mods: ModInfo) = ModEntry(file, hash(hashChar), 10, mods.map { it.id }, mods = mods.toList())

    private fun manifest(vararg artifacts: Artifact) =
        Manifest("00000000-0000-0000-0000-000000000001", 1, "1.21.1", "neoforge", "21.1.172", artifacts.toList(), "2026-01-01T00:00:00Z", "a".repeat(128))

    private fun plan(
        manifest: Manifest,
        local: List<ModEntry>,
        managed: Map<String, String> = emptyMap(),
        approved: Set<String> = emptySet()
    ) = Resolver().plan(manifest, emptyList(), local, managed, approvedRemovals = approved)

    private fun SyncPlan.op(file: String) = operations.single { it.fileName == file }

    @Test fun `unmanaged older version under another file name is reported and never ignored`() {
        val plan = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("alpha-1.0.jar", 'a', ModInfo("alpha", "1.0.0"))))
        assertEquals(OperationKind.INSTALL, plan.op("alpha-2.0.jar").kind)
        val old = plan.op("alpha-1.0.jar")
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, old.kind)
        assertTrue(plan.blocked)
        assertTrue("alpha 2.0.0" in old.reason && "1.0.0" in old.reason && "approval" in old.reason, old.reason)
    }

    @Test fun `explicit approval turns the unmanaged older duplicate into a recoverable removal`() {
        val plan = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("alpha-1.0.jar", 'a', ModInfo("alpha", "1.0.0"))), approved = setOf("alpha-1.0.jar"))
        assertEquals(OperationKind.QUARANTINE_DUPLICATE, plan.op("alpha-1.0.jar").kind)
        assertFalse(plan.blocked)
        assertEquals(setOf("alpha-1.0.jar"), plan.approvedRemovals)
    }

    @Test fun `managed older version is removed with an explanation`() {
        val plan =
            plan(
                manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')),
                listOf(jar("alpha-1.0.jar", 'a', ModInfo("alpha", "1.0.0"))),
                managed = mapOf("alpha-1.0.jar" to hash('a'))
            )
        val old = plan.op("alpha-1.0.jar")
        assertEquals(OperationKind.REMOVE_MANAGED, old.kind)
        assertTrue("older" in old.reason.lowercase() && "2.0.0" in old.reason, old.reason)
        assertFalse(plan.blocked)
    }

    @Test fun `three versions keep only the pinned one and each older file follows its ownership`() {
        val local =
            listOf(
                jar("alpha-1.0.jar", 'a', ModInfo("alpha", "1.0.0")),
                jar("alpha-1.5.jar", 'c', ModInfo("alpha", "1.5.0")),
                jar("alpha-1.7.jar", 'd', ModInfo("alpha", "1.7.0"))
            )
        val plan = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), local, managed = mapOf("alpha-1.5.jar" to hash('c')))
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, plan.op("alpha-1.0.jar").kind)
        assertEquals(OperationKind.REMOVE_MANAGED, plan.op("alpha-1.5.jar").kind)
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, plan.op("alpha-1.7.jar").kind)
    }

    @Test fun `an already installed pinned file is kept and its older sibling needs approval`() {
        val plan = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("alpha-2.0.jar", 'b', ModInfo("alpha", "2.0.0")), jar("alpha-1.0.jar", 'a', ModInfo("alpha", "1.0.0"))))
        assertEquals(OperationKind.KEEP, plan.op("alpha-2.0.jar").kind)
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, plan.op("alpha-1.0.jar").kind)
    }

    @Test fun `a newer installed version is never silently downgraded`() {
        val unmanaged = plan(manifest(pinned("alpha-1.0.jar", "alpha", "1.0.0", 'a')), listOf(jar("alpha-2.0.jar", 'b', ModInfo("alpha", "2.0.0"))))
        assertEquals(OperationKind.CONFLICT, unmanaged.op("alpha-2.0.jar").kind)
        assertTrue("downgrade" in unmanaged.op("alpha-2.0.jar").reason.lowercase())
        val managed = plan(manifest(pinned("alpha-1.0.jar", "alpha", "1.0.0", 'a')), listOf(jar("alpha-2.0.jar", 'b', ModInfo("alpha", "2.0.0"))), managed = mapOf("alpha-2.0.jar" to hash('b')))
        assertEquals(OperationKind.CONFLICT, managed.op("alpha-2.0.jar").kind)
        assertTrue(managed.blocked)
    }

    @Test fun `unestablished versions are conflicts not guesses`() {
        val missing = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("alpha-1.0.jar", 'a', ModInfo("alpha", null))))
        assertEquals(OperationKind.CONFLICT, missing.op("alpha-1.0.jar").kind)
        val unknownPin = plan(manifest(pinned("alpha-2.0.jar", "alpha", null, 'b')), listOf(jar("alpha-1.0.jar", 'a', ModInfo("alpha", "1.0.0"))))
        assertEquals(OperationKind.CONFLICT, unknownPin.op("alpha-1.0.jar").kind)
        val incomparable = plan(manifest(pinned("alpha-1.jar", "alpha", "1.0.0.1", 'b')), listOf(jar("alpha-p.jar", 'a', ModInfo("alpha", "1.0.0-patch1"))))
        assertEquals(OperationKind.CONFLICT, incomparable.op("alpha-p.jar").kind)
    }

    @Test fun `prereleases order below their release`() {
        val older = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("alpha-rc.jar", 'a', ModInfo("alpha", "2.0.0-rc.1"))))
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, older.op("alpha-rc.jar").kind)
        val newer = plan(manifest(pinned("alpha-1.9.jar", "alpha", "1.9.0", 'b')), listOf(jar("alpha-beta.jar", 'a', ModInfo("alpha", "2.0.0-beta.1"))))
        assertEquals(OperationKind.CONFLICT, newer.op("alpha-beta.jar").kind)
    }

    @Test fun `multi mod jars are never removed automatically`() {
        val plan =
            plan(
                manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')),
                listOf(jar("bundle.jar", 'a', ModInfo("alpha", "1.0.0"), ModInfo("beta", "1.0.0"))),
                managed = mapOf("bundle.jar" to hash('a'))
            )
        assertEquals(OperationKind.CONFLICT, plan.op("bundle.jar").kind)
    }

    @Test fun `identical content under another file name is a removable duplicate`() {
        val plan = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("alpha-2.0.jar", 'b', ModInfo("alpha", "2.0.0")), jar("alpha-copy.jar", 'b', ModInfo("alpha", "2.0.0"))))
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, plan.op("alpha-copy.jar").kind)
        assertTrue("identical" in plan.op("alpha-copy.jar").reason.lowercase())
    }

    @Test fun `two selected artifacts that provide the same mod are a conflict`() {
        val plan = plan(manifest(pinned("alpha-a.jar", "alpha", "1.0.0", 'a', project = "p1"), pinned("alpha-b.jar", "alpha", "2.0.0", 'b', project = "p2")), emptyList())
        assertTrue(plan.blocked)
        for (file in listOf("alpha-a.jar", "alpha-b.jar")) {
            assertTrue(plan.operations.any { it.fileName == file && it.kind == OperationKind.CONFLICT && "alpha" in it.reason }, file)
        }
    }

    @Test fun `updating a file in place is not a duplicate`() {
        val plan = plan(manifest(pinned("alpha.jar", "alpha", "2.0.0", 'b')), listOf(jar("alpha.jar", 'a', ModInfo("alpha", "1.0.0"))), managed = mapOf("alpha.jar" to hash('a')))
        assertEquals(OperationKind.UPDATE, plan.op("alpha.jar").kind)
        assertFalse(plan.blocked)
    }

    @Test fun `repeating the synchronization after cleanup only keeps files`() {
        val plan = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("alpha-2.0.jar", 'b', ModInfo("alpha", "2.0.0"))), managed = mapOf("alpha-2.0.jar" to hash('b')))
        assertEquals(listOf(OperationKind.KEEP), plan.operations.map { it.kind })
    }

    @Test fun `artifacts without mod identity cannot be linked and add no duplicate verdict`() {
        val legacy = pinned("alpha-2.0.jar", "alpha", null, 'b', mods = emptyList())
        val plan = plan(manifest(legacy), listOf(jar("alpha-1.0.jar", 'a', ModInfo("alpha", "1.0.0"))))
        assertEquals(OperationKind.SKIP, plan.op("alpha-1.0.jar").kind)
    }

    @Test fun `approval never applies to files that are not older duplicates`() {
        val plan = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("player.jar", 'e', ModInfo("player", "1.0.0"))), approved = setOf("player.jar", "alpha-2.0.jar"))
        assertEquals(OperationKind.SKIP, plan.op("player.jar").kind)
        assertEquals(emptySet(), plan.approvedRemovals)
    }

    @Test fun `local only duplicates keep the highest verifiably compatible version`() {
        val range = "[1.21,1.22)"
        val local =
            listOf(
                jar("beta-1.0.jar", 'a', ModInfo("beta", "1.0.0", range)),
                jar("beta-2.0.jar", 'b', ModInfo("beta", "2.0.0", range))
            )
        val plan = plan(manifest(), local)
        assertEquals(OperationKind.SKIP, plan.op("beta-2.0.jar").kind)
        val old = plan.op("beta-1.0.jar")
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, old.kind)
        assertTrue("beta 2.0.0" in old.reason, old.reason)
    }

    @Test fun `local only duplicates without verifiable compatibility are conflicts`() {
        val plan = plan(manifest(), listOf(jar("beta-1.0.jar", 'a', ModInfo("beta", "1.0.0")), jar("beta-2.0.jar", 'b', ModInfo("beta", "2.0.0"))))
        assertEquals(setOf(OperationKind.CONFLICT), plan.operations.map { it.kind }.toSet())
    }

    @Test fun `a newer file for another minecraft version is not preferred`() {
        val local =
            listOf(
                jar("beta-1.0.jar", 'a', ModInfo("beta", "1.0.0", "[1.21,1.22)")),
                jar("beta-2.0.jar", 'b', ModInfo("beta", "2.0.0", "[1.20,1.21)"))
            )
        val plan = plan(manifest(), local)
        assertEquals(OperationKind.SKIP, plan.op("beta-1.0.jar").kind)
        val other = plan.op("beta-2.0.jar")
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, other.kind)
        assertTrue("1.21.1" in other.reason, other.reason)
    }

    @Test fun `loader range of a kept local mod must match the configured loader version`() {
        val local =
            listOf(
                jar("beta-1.0.jar", 'a', ModInfo("beta", "1.0.0", "[1.21,1.22)", "[21.1,)")),
                jar("beta-2.0.jar", 'b', ModInfo("beta", "2.0.0", "[1.21,1.22)", "[22.0,)"))
            )
        val plan = plan(manifest(), local)
        assertEquals(OperationKind.SKIP, plan.op("beta-1.0.jar").kind)
        assertEquals(OperationKind.MANUAL_ACTION_REQUIRED, plan.op("beta-2.0.jar").kind)
    }

    @Test fun `unrelated unmanaged mods stay untouched`() {
        val plan = plan(manifest(pinned("alpha-2.0.jar", "alpha", "2.0.0", 'b')), listOf(jar("sodium.jar", 'e', ModInfo("sodium", "1.0.0"))))
        assertEquals(OperationKind.SKIP, plan.op("sodium.jar").kind)
    }
}
