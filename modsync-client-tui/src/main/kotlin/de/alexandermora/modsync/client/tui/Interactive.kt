package de.alexandermora.modsync.client.tui

import de.alexandermora.modsync.client.*
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder

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
        println("Minecraft ModSync. Enter CLI commands without the modsync prefix; quit exits.")
        while (true) {
            val line =
                try {
                    reader.readLine("modsync> ")
                } catch (_: EndOfFileException) {
                    break
                } catch (_: UserInterruptException) {
                    continue
                }
            if (line.trim() == "quit") break
            if (line.isBlank()) continue
            val parsed = reader.parser.parse(line, line.length)
            clientCommandLine().execute(*parsed.words().toTypedArray())
        }
    }
}
