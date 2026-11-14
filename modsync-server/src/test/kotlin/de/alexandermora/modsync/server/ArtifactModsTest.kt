package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.ModInfo
import de.alexandermora.modsync.server.manifest.withInventoryMods
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies that published artifacts carry the mods of the scanned inventory file with the same hash. */
class ArtifactModsTest {
    private val hash = "a".repeat(128)
    private val artifact = Artifact("p", "v", "a.jar", hash, 1, null, setOf("1.21.1"), setOf("neoforge"), Classification.CLIENT_REQUIRED)
    private val mods = listOf(ModInfo("alpha", "1.2.3"))

    /** The scanned entry with the same hash supplies the mods. */
    @Test
    fun copiesModsFromMatchingEntry() {
        assertEquals(mods, artifact.withInventoryMods(ModEntry("a.jar", hash, 1, mods = mods)).mods)
    }

    /** Missing or different entries never attach foreign identity. */
    @Test
    fun ignoresMissingOrDifferentEntry() {
        assertEquals(emptyList(), artifact.withInventoryMods(null).mods)
        assertEquals(emptyList(), artifact.withInventoryMods(ModEntry("a.jar", "b".repeat(128), 1, mods = mods)).mods)
    }
}
