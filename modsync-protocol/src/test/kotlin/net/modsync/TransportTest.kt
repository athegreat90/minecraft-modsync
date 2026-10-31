package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import kotlin.test.*
class TransportTest {
    @Test fun `minimum version negotiated safely`() { assertTrue(versionAtLeast("0.1.1","0.1.0")); assertFalse(versionAtLeast("0.0.9","0.1.0")); assertFalse(versionAtLeast("-1.1.0","0.1.0")); assertFalse(versionAtLeast("broken","0.1.0")) }
    @Test fun `plaintext and embedded credentials rejected`() { listOf("http://localhost", "https://user:secret@example.com", "https://example.com?token=secret").forEach { assertFails { Api(it) } } }
    @Test fun `unknown wire fields allow compatible evolution`() { assertEquals(1,wire.decodeFromString<Capabilities>("{\"controlPlaneId\":\"test\",\"protocol\":1,\"future\":true}").protocol) }
}
