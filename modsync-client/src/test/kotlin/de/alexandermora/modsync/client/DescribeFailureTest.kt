package de.alexandermora.modsync.client

import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies that command failures are explained safely and actionably. */
class DescribeFailureTest {
    private val config = Path.of("state", "config.json")

    /** A missing configuration file means the client was never set up, and the message names the commands to run. */
    @Test
    fun missingConfigurationMeansNotSetUp() {
        val text = describeFailure(NoSuchFileException(config.toString()), config)
        assertTrue(text.startsWith("Client not set up") && text.contains("modsync setup") && text.contains("modsync login"))
    }

    /** Any other missing file is named. */
    @Test
    fun otherMissingFileIsNamed() {
        assertEquals("File not found: plan.json", describeFailure(NoSuchFileException("plan.json"), config))
    }

    /** The client's own validation messages are shown; the two that have a next step say what to run. */
    @Test
    fun ownMessagesAreShown() {
        assertEquals("Invalid UUID string: x", describeFailure(IllegalArgumentException("Invalid UUID string: x"), config))
        assertEquals("Plan is blocked", describeFailure(IllegalStateException("Plan is blocked"), config))
        assertTrue(describeFailure(IllegalStateException("Login required"), config).contains("modsync login"))
        assertTrue(describeFailure(IllegalStateException("Instance not registered"), config).contains("modsync instances add"))
    }

    /** A blank or absent message falls back to the exception name. */
    @Test
    fun missingMessageFallsBack() {
        assertTrue(describeFailure(IllegalStateException(), config).startsWith("IllegalStateException;"))
    }

    /** Connection problems, even when wrapped, say the server is unreachable. */
    @Test
    fun connectionFailuresAreExplained() {
        assertTrue(describeFailure(ConnectException("refused: https://secret.example/path"), config).startsWith("Cannot reach the server (ConnectException)"))
        assertTrue(describeFailure(RuntimeException("wrapped", HttpConnectTimeoutException("timed out")), config).contains("HttpConnectTimeoutException"))
    }

    /** A JSON decoding error may quote a local file, so its text is never shown even though it is an IllegalArgumentException. */
    @Test
    fun jsonErrorsNeverQuoteContent() {
        val text = describeFailure(SerializationException("Unexpected JSON token at offset 4: {\"token\":\"s3cret\"}"), config)
        assertFalse(text.contains("s3cret") || text.contains("token"))
        assertTrue(text.contains("modsync doctor"))
    }

    /** Anything else is reduced to its class name. */
    @Test
    fun unknownExceptionsAreReducedToTheirName() {
        assertEquals("UnsupportedOperationException; check arguments, connectivity and local state", describeFailure(UnsupportedOperationException("body: secret"), config))
    }
}
