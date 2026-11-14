package de.alexandermora.modsync.client.tui

import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the prompt loop with scripted input, without a real terminal or client. */
class InteractiveTest {
    private val parser = DefaultParser()
    private val executed = mutableListOf<List<String>>()

    /** Feeds [inputs] (a string is a line, a Throwable is raised by the reader) and returns the console output. */
    private fun session(vararg inputs: Any, status: Int = 0): String {
        val queue = ArrayDeque(inputs.toList())
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out, true))
        try {
            runSession(
                readLine = {
                    when (val next = queue.removeFirstOrNull() ?: throw EndOfFileException()) {
                        is Throwable -> throw next
                        else -> next as String
                    }
                },
                parse = { parser.parse(it, it.length).words() },
                execute = {
                    executed += it.toList()
                    status
                }
            )
        } finally {
            System.setOut(original)
        }
        return out.toString()
    }

    @Test
    fun printsTheBannerOnStart() {
        assertTrue(session("quit").contains("Minecraft ModSync. Enter CLI commands without the modsync prefix; quit exits."))
    }

    @Test
    fun quitEndsTheSessionWithoutRunningAnything() {
        session("quit", "doctor")
        assertEquals(emptyList(), executed)
    }

    @Test
    fun quitIgnoresSurroundingWhitespace() {
        session("  quit  ", "doctor")
        assertEquals(emptyList(), executed)
    }

    @Test
    fun endOfInputEndsTheSession() {
        session("doctor", EndOfFileException())
        assertEquals(listOf(listOf("doctor")), executed)
    }

    @Test
    fun forwardsEachCommandWithItsWords() {
        session("servers list", "auto status", "quit")
        assertEquals(listOf(listOf("servers", "list"), listOf("auto", "status")), executed)
    }

    @Test
    fun honorsQuotedArguments() {
        session("instances add \"my pack\"", "quit")
        assertEquals(listOf(listOf("instances", "add", "my pack")), executed)
    }

    @Test
    fun blankLinesAreIgnored() {
        session("", "   ", "doctor", "quit")
        assertEquals(listOf(listOf("doctor")), executed)
    }

    @Test
    fun ctrlCDiscardsTheLineAndKeepsTheSession() {
        session(UserInterruptException("partial"), "doctor", "quit")
        assertEquals(listOf(listOf("doctor")), executed)
    }

    @Test
    fun failingCommandDoesNotEndTheSession() {
        session("doctor", "servers list", "quit", status = 1)
        assertEquals(2, executed.size)
    }
}
