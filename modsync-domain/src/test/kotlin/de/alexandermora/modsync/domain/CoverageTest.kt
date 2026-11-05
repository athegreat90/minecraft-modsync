package de.alexandermora.modsync.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies [computeCoverage] status decisions. */
class CoverageTest {
    private val now = 1_000_000L
    private val hashA = "a".repeat(128)
    private val hashB = "b".repeat(128)

    /** Builds a manifest requiring one client mod with [hash]. */
    private fun manifest(hash: String = hashA, classification: Classification = Classification.CLIENT_REQUIRED) = Manifest(
        "s", 1, "1.21.1", "neoforge", null,
        listOf(Artifact("p1", "v1", "m.jar", hash, 1, null, setOf("1.21.1"), setOf("neoforge"), classification)),
        "2026-01-01T00:00:00Z", "d"
    )

    /** Builds a verified, online, idle client view with the supplied overrides. */
    private fun client(
        hashes: Set<String> = setOf(hashA), projects: Set<String> = setOf("p1"), mc: String = "1.21.1",
        verified: Long? = now, online: Boolean = true, activity: ClientActivity = ClientActivity.IDLE
    ) = ClientInventoryView(mc, "NeoForge", hashes, verified, online, activity, projects)

    /** A missing report yields UNKNOWN. */
    @Test fun unknownWithoutInventory() = assertEquals(CoverageStatus.UNKNOWN, computeCoverage(manifest(), null, now))

    /** Matching hash is up to date, regardless of file name. */
    @Test fun upToDate() = assertEquals(CoverageStatus.UP_TO_DATE, computeCoverage(manifest(), client(), now))

    /** Same project with a different hash is a wrong version. */
    @Test fun wrongVersion() =
        assertEquals(CoverageStatus.WRONG_VERSION, computeCoverage(manifest(), client(hashes = setOf(hashB)), now))

    /** Absent project is a missing mod. */
    @Test fun missingMods() =
        assertEquals(CoverageStatus.MISSING_MODS, computeCoverage(manifest(), client(hashes = emptySet(), projects = emptySet()), now))

    /** Different Minecraft version is incompatible. */
    @Test fun incompatible() = assertEquals(CoverageStatus.INCOMPATIBLE, computeCoverage(manifest(), client(mc = "1.20.1"), now))

    /** Offline and stale clients are never reported as up to date. */
    @Test fun offlineAndStale() {
        assertEquals(CoverageStatus.OFFLINE, computeCoverage(manifest(), client(online = false), now))
        assertEquals(CoverageStatus.INVENTORY_STALE, computeCoverage(manifest(), client(verified = now - 90_000), now))
    }

    /** Never-verified inventories are UNKNOWN. */
    @Test fun neverVerified() = assertEquals(CoverageStatus.UNKNOWN, computeCoverage(manifest(), client(verified = null), now))

    /** Sync activity states map one-to-one. */
    @Test fun activityStates() {
        val expected = mapOf(
            ClientActivity.SYNC_PENDING to CoverageStatus.SYNC_PENDING, ClientActivity.SYNCING to CoverageStatus.SYNCING,
            ClientActivity.PENDING_GAME_EXIT to CoverageStatus.PENDING_GAME_EXIT, ClientActivity.SYNC_FAILED to CoverageStatus.SYNC_FAILED
        )
        expected.forEach { (activity, status) -> assertEquals(status, computeCoverage(manifest(), client(activity = activity), now)) }
    }

    /** Non-client-required artifacts do not affect coverage. */
    @Test fun serverOnlyIgnored() = assertEquals(
        CoverageStatus.UP_TO_DATE,
        computeCoverage(manifest(classification = Classification.SERVER_ONLY), client(hashes = emptySet()), now)
    )
}
