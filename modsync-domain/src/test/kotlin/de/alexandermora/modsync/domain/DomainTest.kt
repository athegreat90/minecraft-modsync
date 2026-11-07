package de.alexandermora.modsync.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Verifies domain helpers and the serialized shape of the domain model. */
class DomainTest {
    private val json = Json { encodeDefaults = true }
    private val hash = "a".repeat(128)

    private val artifact =
        Artifact(
            projectId = "p1",
            versionId = "v1",
            fileName = "mod.jar",
            sha512 = hash,
            size = 3,
            url = "https://cdn.modrinth.com/mod.jar",
            minecraftVersions = setOf("1.21.1"),
            loaders = setOf("neoforge"),
            classification = Classification.CLIENT_REQUIRED,
            dependencies = listOf(Dependency("p2", null, DependencyKind.OPTIONAL)),
            evidence = listOf(Evidence("modrinth", "hash match"))
        )

    private val manifest =
        Manifest("s", 2, "1.21.1", "neoforge", "21.1.0", listOf(artifact), "2026-01-01T00:00:00Z", "digest")

    /** A SHA-512 is 128 lowercase hex characters. */
    @Test fun hashValidation() {
        assertEquals(128, sha512("abc".toByteArray()).length)
        assertTrue(validHash(sha512("abc".toByteArray())))
        assertFalse(validHash("A".repeat(128)))
        assertFalse(validHash("abc"))
    }

    /** Hex encoding is lowercase and fixed width. */
    @Test fun hexEncoding() = assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())

    /** Only well-formed UUIDs are accepted and they are normalized. */
    @Test fun uuidParsing() {
        assertEquals("123e4567-e89b-12d3-a456-426614174000", uuid("123E4567-E89B-12D3-A456-426614174000"))
        assertFailsWith<IllegalArgumentException> { uuid("not-a-uuid") }
    }

    /** File names that could escape a directory or break Windows are rejected. */
    @Test fun safeFileNames() {
        assertTrue(safeFileName("mod-1.0.jar"))
        assertTrue(safeFileName("MOD.JAR"))
        listOf("", " ", ".", "..", "a/b.jar", "a\\b.jar", "a:b.jar", "a*b.jar", "tab\t.jar", "mod.txt", "CON.jar", "com1.jar", "a".repeat(240) + ".jar")
            .forEach { assertFalse(safeFileName(it), it) }
    }

    /** The digest is independent of entry order and changes with content. */
    @Test fun inventoryDigestIsOrderIndependent() {
        val a = ModEntry("a.jar", hash, 1)
        val b = ModEntry("b.jar", "b".repeat(128), 2)
        assertEquals(inventoryDigest(listOf(a, b)), inventoryDigest(listOf(b, a)))
        assertNotEquals(inventoryDigest(listOf(a, b)), inventoryDigest(listOf(a)))
    }

    /** A plan is blocked only by conflicts or manual actions. */
    @Test fun planBlocking() {
        fun plan(vararg kinds: OperationKind) = SyncPlan(manifest, kinds.map { PlanOperation(it, "m.jar", "r") }, "d")
        assertFalse(plan(OperationKind.INSTALL, OperationKind.KEEP).blocked)
        assertTrue(plan(OperationKind.INSTALL, OperationKind.CONFLICT).blocked)
        assertTrue(plan(OperationKind.MANUAL_ACTION_REQUIRED).blocked)
        assertFalse(plan().includeOptional)
    }

    /** Every model type survives a JSON round trip unchanged. */
    @Test fun modelRoundTrip() {
        val entry = ModEntry("m.jar", hash, 3, listOf("id"), Classification.BOTH_REQUIRED, listOf(Evidence("s", "d")), mapOf("k" to "v"))
        val profile = PersonalProfile("id", "name", listOf(artifact))
        val plan = SyncPlan(manifest, listOf(PlanOperation(OperationKind.INSTALL, "mod.jar", "new", artifact)), "local", true)
        assertEquals(entry, json.decodeFromString<ModEntry>(json.encodeToString(ModEntry.serializer(), entry)))
        assertEquals(artifact, json.decodeFromString<Artifact>(json.encodeToString(Artifact.serializer(), artifact)))
        assertEquals(manifest, json.decodeFromString<Manifest>(json.encodeToString(Manifest.serializer(), manifest)))
        assertEquals(profile, json.decodeFromString<PersonalProfile>(json.encodeToString(PersonalProfile.serializer(), profile)))
        assertEquals(plan, json.decodeFromString<SyncPlan>(json.encodeToString(SyncPlan.serializer(), plan)))
    }
}
