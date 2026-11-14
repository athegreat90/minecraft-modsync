package de.alexandermora.modsync.client.tui

import de.alexandermora.modsync.client.*
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.TerminalBuilder

internal const val PROMPT = "modsync> "
internal const val BANNER = "Minecraft ModSync. Enter CLI commands without the modsync prefix; quit exits."

/**
 * Runs the prompt loop: each entered line is split into words and handed to [execute]; `quit` or end of input exits.
 *
 * Ctrl+C only discards the current line, blank lines are ignored, and the status code of a command never ends the session.
 *
 * @param readLine Reads one line for the given prompt; throws [EndOfFileException] at end of input and [UserInterruptException] on Ctrl+C.
 * @param parse Splits a line into words the way a shell would, honoring quotes.
 * @param execute Runs one client command line and returns its status code.
 */
internal fun runSession(
    readLine: (String) -> String,
    parse: (String) -> List<String>,
    execute: (Array<String>) -> Int
) {
    println(BANNER)
    while (true) {
        val line =
            try {
                readLine(PROMPT)
            } catch (_: EndOfFileException) {
                break
            } catch (_: UserInterruptException) {
                continue
            }
        if (line.trim() == "quit") break
        if (line.isBlank()) continue
        execute(parse(line).toTypedArray())
    }
}

/**
 * Runs a JLine prompt that forwards each entered line to the regular client command line; `quit` or end of input exits.
 *
 * Ctrl+C only discards the current line, and command failures do not end the session. Every command
 * goes through the regular client command line, so its verification, backup and atomic-apply
 * guarantees are unchanged.
 */
fun main() {
    TerminalBuilder.builder().system(true).build().use { terminal ->
        val reader = LineReaderBuilder.builder().terminal(terminal).build()
        runSession(reader::readLine, { reader.parser.parse(it, it.length).words() }, { clientCommandLine().execute(*it) })
    }
}
