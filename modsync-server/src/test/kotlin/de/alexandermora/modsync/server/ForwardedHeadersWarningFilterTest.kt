package de.alexandermora.modsync.server

import de.alexandermora.modsync.server.common.ForwardedHeadersWarningFilter
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies the one-time warning that tells operators a reverse proxy's forwarded headers are being ignored. */
class ForwardedHeadersWarningFilterTest {
    private val warnings = mutableListOf<String>()

    private fun send(
        strategy: String,
        proto: String?,
        times: Int = 1
    ): MockFilterChain {
        val filter = ForwardedHeadersWarningFilter(strategy) { warnings += it }
        var chain = MockFilterChain()
        repeat(times) {
            val request = MockHttpServletRequest("GET", "/ui/login").apply { proto?.let { addHeader("X-Forwarded-Proto", it) } }
            chain = MockFilterChain()
            filter.doFilter(request, MockHttpServletResponse(), chain)
        }
        return chain
    }

    /** A proxy header with the default strategy is reported exactly once and names the setting that fixes it. */
    @Test fun warnsOnceWhenAProxyHeaderArrivesAndTheStrategyIsNone() {
        send("none", "https", times = 3)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("MODSYNC_FORWARD_HEADERS=framework"))
    }

    /** An empty strategy behaves like none. */
    @Test fun anEmptyStrategyCountsAsNone() {
        send("", "https")
        assertEquals(1, warnings.size)
    }

    /** Nothing is reported when the headers are honoured. */
    @Test fun staysQuietWhenTheHeadersAreHonoured() {
        listOf("framework", "FRAMEWORK", "native").forEach { send(it, "https") }
        assertTrue(warnings.isEmpty())
    }

    /** Direct access without a proxy header is never reported. */
    @Test fun staysQuietWithoutAProxyHeader() {
        send("none", null, times = 2)
        assertTrue(warnings.isEmpty())
    }

    /** The request always continues down the chain, warned or not. */
    @Test fun alwaysPassesTheRequestOn() {
        assertNotNull(send("none", "https").request)
        assertNotNull(send("none", null).request)
        assertNotNull(send("framework", "https").request)
    }
}
