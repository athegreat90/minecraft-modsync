package net.modsync

import org.jline.terminal.TerminalBuilder
import org.jline.reader.LineReaderBuilder
import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException

fun main() {
    TerminalBuilder.builder().system(true).build().use { terminal ->
        val reader=LineReaderBuilder.builder().terminal(terminal).build()
        println("Minecraft ModSync. Enter CLI commands without the modsync prefix; quit exits.")
        while (true) {
            val line=try { reader.readLine("modsync> ") } catch (_: EndOfFileException) { break } catch (_: UserInterruptException) { continue }
            if (line.trim() == "quit") break
            if (line.isBlank()) continue
            val parsed=reader.parser.parse(line, line.length)
            clientCommandLine().execute(*parsed.words().toTypedArray())
        }
    }
}
