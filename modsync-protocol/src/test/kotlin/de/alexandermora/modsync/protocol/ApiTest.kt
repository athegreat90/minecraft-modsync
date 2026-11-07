package de.alexandermora.modsync.protocol

import de.alexandermora.modsync.fixtures.TestHttpsServer
import de.alexandermora.modsync.fixtures.TestReply
import de.alexandermora.modsync.fixtures.TestRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Verifies the HTTPS API client against an in-process control plane. */
class ApiTest {
    @TempDir
    lateinit var dir: Path
    private lateinit var server: TestHttpsServer
    private var capabilities = Capabilities("cp")
    private var reply = TestReply(body = "{}")
    private val received = mutableListOf<TestRequest>()

    @BeforeEach
    fun start() {
        server =
            TestHttpsServer(dir) {
                received += it
                if (it.path == "/api/v1/info") TestReply(body = wire.encodeToString(Capabilities.serializer(), capabilities)) else reply
            }
    }

    @AfterEach
    fun stop() = server.close()

    /** Successful calls return the body and send protocol headers, the method and the body. */
    @Test
    fun requestReturnsBody() {
        reply = TestReply(body = "{\"value\":1}")
        val body = Api(server.endpoint + "/", "token").request("/api/v1/thing", "POST", "{\"in\":true}")
        assertEquals("{\"value\":1}", body)
        assertEquals(TestRequest("POST", "/api/v1/thing", "{\"in\":true}"), received.single())
        assertEquals("{\"value\":1}", Api(server.endpoint).request("/api/v1/thing"))
    }

    /** Only API paths and successful statuses are accepted. */
    @Test
    fun requestRefusesBadPathsAndStatuses() {
        val api = Api(server.endpoint)
        assertFailsWith<IllegalArgumentException> { api.request("/other") }
        reply = TestReply(404, "missing")
        assertFailsWith<IllegalStateException> { api.request("/api/v1/thing") }
        reply = TestReply(302, "redirect")
        assertFailsWith<IllegalStateException> { api.request("/api/v1/thing") }
    }

    /** Responses above the size limit are rejected. */
    @Test
    fun requestRefusesOversizedResponses() {
        reply = TestReply(body = "x".repeat(8 * 1024 * 1024 + 1))
        assertFailsWith<IllegalArgumentException> { Api(server.endpoint).request("/api/v1/thing") }
    }

    /** Capabilities are accepted only for the same protocol and when the component version is allowed. */
    @Test
    fun capabilitiesAreChecked() {
        assertEquals("cp", Api(server.endpoint).capabilities().controlPlaneId)
        capabilities = Capabilities("cp", protocol = 2)
        assertFailsWith<IllegalArgumentException> { Api(server.endpoint).capabilities() }
        capabilities = Capabilities("cp", minimumClient = "99.0.0", minimumAgent = "0.1.0")
        assertFailsWith<IllegalArgumentException> { Api(server.endpoint).capabilities() }
        assertEquals("cp", Api(server.endpoint, component = "agent").capabilities().controlPlaneId)
        capabilities = Capabilities("cp", minimumClient = "0.1.0", minimumAgent = "99.0.0")
        assertFailsWith<IllegalArgumentException> { Api(server.endpoint, component = "agent").capabilities() }
        assertTrue(Api(server.endpoint).capabilities().minimumAgent == "99.0.0")
    }
}
