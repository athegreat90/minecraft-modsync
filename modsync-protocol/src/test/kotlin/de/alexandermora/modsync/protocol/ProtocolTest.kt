package de.alexandermora.modsync.protocol

import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.ClientActivity
import de.alexandermora.modsync.domain.CoverageStatus
import de.alexandermora.modsync.domain.ModEntry
import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.domain.SyncPolicy
import kotlinx.serialization.KSerializer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies wire DTO round trips and atomic file writes. */
class ProtocolTest {
    private fun <T> roundTrip(
        serializer: KSerializer<T>,
        value: T
    ) = assertEquals(value, wire.decodeFromString(serializer, wire.encodeToString(serializer, value)))

    /** Every wire DTO survives a JSON round trip. */
    @Test fun dtoRoundTrip() {
        val hash = "a".repeat(128)
        val entry = ModEntry("m.jar", hash, 1, listOf("id"), Classification.CLIENT_REQUIRED)
        val begin = SnapshotBegin("s", "scan", 1, 2, "2026-01-01T00:00:00Z", "digest", 1)
        val report =
            ClientInventoryReport(
                "i",
                "name",
                "s",
                "1.21.1",
                "neoforge",
                "21.1",
                3,
                4,
                SyncPolicy.AUTO_ALL_MANAGED,
                ClientActivity.SYNCING,
                "profile",
                listOf(ClientModEntry(hash, "m.jar", "p", "v", true))
            )
        roundTrip(Capabilities.serializer(), Capabilities("c"))
        roundTrip(Login.serializer(), Login("u", "p", "d"))
        roundTrip(Credentials.serializer(), Credentials("id", "t", "2026-01-01T00:00:00Z", "r"))
        roundTrip(Enrollment.serializer(), Enrollment("t", "a"))
        roundTrip(EnrollmentRequest.serializer(), EnrollmentRequest())
        roundTrip(EnrollmentToken.serializer(), EnrollmentToken("t", "2026-01-01T00:00:00Z"))
        roundTrip(ServerRegistration.serializer(), ServerRegistration("n", "1.21.1", "neoforge", "21.1"))
        roundTrip(Assignment.serializer(), Assignment("s", "a", 1, "m"))
        roundTrip(Transfer.serializer(), Transfer("a", "m"))
        roundTrip(ServerInfo.serializer(), ServerInfo("s", "n", "1.21.1", "neoforge", null, "t", 1, false))
        roundTrip(SnapshotBegin.serializer(), begin)
        roundTrip(SnapshotBatch.serializer(), SnapshotBatch(0, listOf(entry)))
        roundTrip(SnapshotAck.serializer(), SnapshotAck("scan", 2, "d", "t"))
        roundTrip(SnapshotView.serializer(), SnapshotView(begin, listOf(entry)))
        roundTrip(Heartbeat.serializer(), Heartbeat(1, listOf(ScanConfirmation("s", 1, "d"))))
        roundTrip(OverrideRequest.serializer(), OverrideRequest(hash, Classification.SERVER_ONLY, "why"))
        roundTrip(PublishRequest.serializer(), PublishRequest("scan", emptyList()))
        roundTrip(RefreshRequest.serializer(), RefreshRequest("t"))
        roundTrip(Failure.serializer(), Failure("code", "message", "id"))
        roundTrip(UserRegistration.serializer(), UserRegistration("u", "p"))
        roundTrip(ClientInventoryReport.serializer(), report)
        roundTrip(ClientInventoryAck.serializer(), ClientInventoryAck("i", 3, true, "t"))
        roundTrip(CoverageRow.serializer(), CoverageRow("i", "n", CoverageStatus.MISSING_MODS, 1, "t", 2))
        roundTrip(ServerCoverage.serializer(), ServerCoverage("s", 1, listOf(CoverageRow("i", "n", CoverageStatus.UNKNOWN))))
        roundTrip(PublicationPolicyRequest.serializer(), PublicationPolicyRequest(PublicationPolicy.TRUSTED_AUTO))
        roundTrip(PublicationRecord.serializer(), PublicationRecord(1, "scan", PublicationPolicy.MANUAL, "ok", 2, "d", "a", "t"))
        roundTrip(SyncReport.serializer(), SyncReport("s", 1, "ok"))
    }

    /** Version comparison handles prefixes, suffixes and ordering of every component. */
    @Test fun versionComparison() {
        assertTrue(versionAtLeast("v1.0.0-dev.1", "1.0.0"))
        assertTrue(versionAtLeast("1.2.0", "1.1.9"))
        assertTrue(versionAtLeast("2.0.0", "1.9.9"))
        assertEquals(false, versionAtLeast("1.0", "1.0.0"))
        assertEquals(false, versionAtLeast("1.0.0", "1.0.x"))
    }

    /** Writes create parent directories and replace existing content atomically. */
    @Test fun secureWriteReplacesContent() {
        val dir = Files.createTempDirectory("modsync-write")
        try {
            val target = dir.resolve("nested/config.json")
            secureWrite(target, "one")
            secureWrite(target, "two")
            assertEquals("two", Files.readString(target))
            Files.list(target.parent).use { assertEquals(listOf(target.fileName), it.map { p -> p.fileName }.toList()) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
