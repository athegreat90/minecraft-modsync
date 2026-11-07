package de.alexandermora.modsync.resolver

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Dependency
import de.alexandermora.modsync.domain.DependencyKind
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.OperationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies every planning decision of the resolver. */
class ResolverBranchTest {
    private fun hash(c: Char) = c.toString().repeat(128)

    private fun artifact(
        project: String,
        file: String = "$project.jar",
        sha: Char = 'a',
        version: String = "v1",
        classification: Classification = Classification.CLIENT_REQUIRED,
        url: String? = "https://cdn.modrinth.com/data/$project/$file",
        dependencies: List<Dependency> = emptyList(),
        minecraft: Set<String> = setOf("1.21.1"),
        loaders: Set<String> = setOf("neoforge"),
        size: Long = 1
    ) = Artifact(project, version, file, hash(sha), size, url, minecraft, loaders, classification, dependencies)

    private fun manifest(vararg artifacts: Artifact) =
        Manifest("s", 1, "1.21.1", "neoforge", null, artifacts.toList(), "2026-01-01T00:00:00Z", "d")

    private fun local(
        file: String,
        sha: Char
    ) = ModEntry(file, hash(sha), 1)

    private fun kinds(
        manifest: Manifest,
        personal: List<Artifact> = emptyList(),
        local: List<ModEntry> = emptyList(),
        managed: Map<String, String> = emptyMap(),
        optional: Boolean = false
    ) = Resolver().plan(manifest, personal, local, managed, optional).operations.map { it.kind to it.fileName }

    /** New files install, matching files are kept and optional mods need opt-in. */
    @Test
    fun installKeepAndOptional() {
        val optional = artifact("opt", classification = Classification.CLIENT_OPTIONAL, sha = 'b')
        val m = manifest(artifact("a"), optional)
        assertEquals(listOf(OperationKind.INSTALL to "a.jar"), kinds(m))
        assertEquals(listOf(OperationKind.INSTALL to "a.jar", OperationKind.INSTALL to "opt.jar"), kinds(m, optional = true))
        assertEquals(listOf(OperationKind.KEEP to "a.jar"), kinds(m, local = listOf(local("a.jar", 'a'))))
    }

    /** Managed files update, unmanaged or renamed ones conflict, and manual artifacts need local supply. */
    @Test
    fun updateConflictAndManual() {
        val m = manifest(artifact("a", sha = 'b'))
        assertEquals(listOf(OperationKind.UPDATE to "a.jar"), kinds(m, local = listOf(local("a.jar", 'a')), managed = mapOf("a.jar" to hash('a'))))
        assertEquals(listOf(OperationKind.CONFLICT to "a.jar"), kinds(m, local = listOf(local("a.jar", 'a'))))
        assertEquals(listOf(OperationKind.CONFLICT to "a.jar"), kinds(m, local = listOf(local("A.jar", 'a')), managed = mapOf("A.jar" to hash('a'))).take(1))
        assertEquals(listOf(OperationKind.MANUAL_ACTION_REQUIRED to "a.jar"), kinds(manifest(artifact("a", url = null))))
    }

    /** Managed leftovers are removed, everything else is preserved. */
    @Test
    fun removeAndSkip() {
        val result =
            kinds(
                manifest(),
                local = listOf(local("old.jar", 'a'), local("mine.jar", 'b'), local("edited.jar", 'c')),
                managed = mapOf("old.jar" to hash('a'), "edited.jar" to hash('d'))
            )
        assertEquals(listOf(OperationKind.REMOVE_MANAGED to "old.jar", OperationKind.SKIP to "mine.jar", OperationKind.SKIP to "edited.jar"), result)
    }

    /** Conflicting pins, personal misuse, unknown classification and file name collisions are blocked. */
    @Test
    fun selectionConflicts() {
        val server = artifact("a")
        val personalSame = artifact("a", sha = 'b', version = "v2", classification = Classification.CLIENT_ONLY)
        assertTrue(kinds(manifest(server), listOf(personalSame)).any { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(manifest(), listOf(artifact("p"))).any { it.first == OperationKind.CONFLICT })
        assertEquals(listOf(OperationKind.MANUAL_ACTION_REQUIRED), kinds(manifest(artifact("u", classification = Classification.UNKNOWN))).map { it.first })
        val collision = manifest(artifact("a", file = "same.jar"), artifact("b", file = "same.jar", sha = 'b'))
        assertTrue(kinds(collision).any { it.first == OperationKind.CONFLICT })
    }

    /** Personal client-only artifacts are installed next to server mods. */
    @Test
    fun personalClientOnly() {
        val personal = artifact("p", classification = Classification.CLIENT_ONLY, sha = 'c')
        assertEquals(listOf(OperationKind.INSTALL to "a.jar", OperationKind.INSTALL to "p.jar"), kinds(manifest(artifact("a")), listOf(personal)))
    }

    /** Required, optional, incompatible and embedded dependencies are checked against the selection. */
    @Test
    fun dependencies() {
        fun withDeps(vararg deps: Dependency) = manifest(artifact("a", dependencies = deps.toList()), artifact("b", sha = 'b'))
        assertEquals(1, kinds(withDeps(Dependency("b", "v1", DependencyKind.REQUIRED))).count { it.first == OperationKind.INSTALL } - 1)
        assertTrue(kinds(withDeps(Dependency("missing", null, DependencyKind.REQUIRED))).any { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(withDeps(Dependency("b", "other", DependencyKind.REQUIRED))).any { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(withDeps(Dependency("missing", null, DependencyKind.OPTIONAL))).none { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(withDeps(Dependency("b", null, DependencyKind.INCOMPATIBLE))).any { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(withDeps(Dependency("missing", null, DependencyKind.INCOMPATIBLE))).none { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(withDeps(Dependency("lib", "v1", DependencyKind.EMBEDDED), Dependency("lib", "v2", DependencyKind.EMBEDDED))).any { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(withDeps(Dependency("b", "v9", DependencyKind.EMBEDDED))).any { it.first == OperationKind.CONFLICT })
        val satisfiedByEmbedded = manifest(artifact("a", dependencies = listOf(Dependency("lib", "v1", DependencyKind.EMBEDDED), Dependency("lib", null, DependencyKind.REQUIRED))))
        assertTrue(kinds(satisfiedByEmbedded).none { it.first == OperationKind.CONFLICT })
    }

    /** Incompatible game or loader versions and invalid metadata are blocked. */
    @Test
    fun compatibilityAndMetadata() {
        assertTrue(kinds(manifest(artifact("a", minecraft = setOf("1.20.1")))).any { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(manifest(artifact("a", loaders = setOf("fabric")))).any { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(manifest(artifact("a", file = "../x.jar"))).any { it.first == OperationKind.CONFLICT })
        assertTrue(kinds(manifest(artifact("a", size = -1))).any { it.first == OperationKind.CONFLICT })
    }
}
