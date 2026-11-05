package de.alexandermora.modsync.server

import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.server.publication.ModrinthArtifactResolver
import kotlin.test.Test
import kotlin.test.assertNull

/** Verifies the Modrinth-backed artifact resolver degrades safely when Modrinth cannot be reached. */
class ModrinthArtifactResolverTest {
    /** An unreachable Modrinth means "unidentified", never an exception or a guess. */
    @Test
    fun unreachableModrinthYieldsNull() {
        val resolver = ModrinthArtifactResolver(Modrinth(java.net.URI("http://127.0.0.1:1/")))
        assertNull(resolver.resolve("a".repeat(128)))
    }
}
