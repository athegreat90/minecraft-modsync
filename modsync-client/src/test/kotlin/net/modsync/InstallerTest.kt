package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
class InstallerTest {
    @TempDir lateinit var root: Path
    @Test fun `interrupted update restores original and preserves unmanaged`() {
        val installer=Installer(root); val dir=root.resolve(".modsync"); val id=UUID.randomUUID().toString(); val backup=dir.resolve(id).resolve("backup"); Files.createDirectories(backup)
        Files.writeString(backup.resolve("old.jar"),"old"); Files.writeString(installer.mods.resolve("old.jar"),"new"); Files.writeString(installer.mods.resolve("personal.jar"),"personal")
        val journal=InstallJournal(id,listOf(JournalEntry("old.jar",sha512("old".toByteArray()),sha512("new".toByteArray()))),ManagedState())
        secureWrite(dir.resolve("journal.json"),wire.encodeToString(journal)); installer.rollback()
        assertEquals("old",Files.readString(installer.mods.resolve("old.jar"))); assertEquals("personal",Files.readString(installer.mods.resolve("personal.jar"))); assertFalse(Files.exists(dir.resolve("journal.json")))
    }
    @Test fun `rollback refuses later local edit`() {
        val installer=Installer(root); val id=UUID.randomUUID().toString(); Files.createDirectories(root.resolve(".modsync/$id/backup")); Files.writeString(installer.mods.resolve("new.jar"),"local edit")
        secureWrite(root.resolve(".modsync/journal.json"),wire.encodeToString(InstallJournal(id,listOf(JournalEntry("new.jar",null,sha512("expected".toByteArray()))),ManagedState())))
        assertFails { installer.rollback() }; assertEquals("local edit",Files.readString(installer.mods.resolve("new.jar")))
    }
    @Test fun `instance loader mismatch rejected`() {
        val installer=Installer(root); installer.configureTarget(InstanceTarget("1.21.1","fabric"))
        val manifest=Manifest("00000000-0000-0000-0000-000000000001",1,"1.21.1","neoforge",artifacts=emptyList(),createdAt="2026-01-01T00:00:00Z",inventoryDigest="a".repeat(128))
        assertFails { installer.validateTarget(manifest) }
    }
    @Test fun `downloaded jar validation does not accept plain text`() { val file=root.resolve("bad.jar"); Files.writeString(file,"bad"); assertFails { validateArchive(file) } }
    @Test fun `unsafe cross platform filenames rejected`() { listOf("../escape.jar","CON.jar","bad\\path.jar","c:drive.jar").forEach { assertFalse(safeFileName(it)) } }
}
