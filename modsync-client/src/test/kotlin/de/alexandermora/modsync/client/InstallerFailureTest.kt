package de.alexandermora.modsync.client

import de.alexandermora.modsync.domain.Artifact
import de.alexandermora.modsync.domain.Classification
import de.alexandermora.modsync.domain.Manifest
import de.alexandermora.modsync.domain.sha512
import de.alexandermora.modsync.fixtures.Fixtures
import de.alexandermora.modsync.protocol.secureWrite
import de.alexandermora.modsync.protocol.wire
import de.alexandermora.modsync.resolver.Resolver
import de.alexandermora.modsync.scanner.Scanner
import de.alexandermora.modsync.scanner.fileHash
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies the refusal and recovery paths of the installer that do not need a network. */
class InstallerFailureTest {
    @TempDir
    lateinit var root: Path
    private lateinit var installer: Installer

    @BeforeEach
    fun setUp() {
        installer = Installer(root)
        installer.configureTarget(InstanceTarget("1.21.1", "neoforge", "21.1"))
    }

    private fun manifest(
        artifacts: List<Artifact> = emptyList(),
        loaderVersion: String? = null
    ) = Manifest("s", 1, "1.21.1", "neoforge", loaderVersion, artifacts, "2026-01-01T00:00:00Z", "d")

    private fun download(
        url: String?,
        size: Long = 5
    ) = Artifact("p", "v", "new.jar", "e".repeat(128), size, url, setOf("1.21.1"), setOf("neoforge"), Classification.CLIENT_REQUIRED)

    private fun plan(manifest: Manifest) = Resolver().plan(manifest, emptyList(), Scanner().scan(installer.mods), installer.state().hashes)

    private fun journal(
        id: String,
        vararg entries: JournalEntry,
        complete: Boolean = false
    ) = secureWrite(root.resolve(".modsync/journal.json"), wire.encodeToString(InstallJournal.serializer(), InstallJournal(id, entries.toList(), ManagedState(), complete)))

    /** Targets need a game version and loader, and loader versions must match when the manifest pins one. */
    @Test
    fun targetValidation() {
        assertFailsWith<IllegalArgumentException> { installer.configureTarget(InstanceTarget(" ", "neoforge")) }
        installer.validateTarget(manifest())
        installer.validateTarget(manifest(loaderVersion = "21.1"))
        assertFailsWith<IllegalArgumentException> { installer.validateTarget(manifest(loaderVersion = "20.0")) }
        assertFailsWith<IllegalArgumentException> { installer.validateTarget(manifest().copy(minecraftVersion = "1.20.1")) }
        assertFailsWith<java.nio.file.NoSuchFileException> { Installer(root.resolve("missing")) }
        assertFailsWith<IllegalArgumentException> { Installer(Files.writeString(root.resolve("file"), "x")) }
    }

    /** Downloads are limited to the official HTTPS CDN with a bounded size, and refusals change nothing. */
    @Test
    fun downloadsAreRestrictedToTheCdn() {
        listOf(
            "http://cdn.modrinth.com/data/p/new.jar",
            "https://evil.example/data/p/new.jar",
            "https://user@cdn.modrinth.com/data/p/new.jar",
            "https://cdn.modrinth.com:8443/data/p/new.jar",
            "https://cdn.modrinth.com/other/new.jar"
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { installer.apply(plan(manifest(listOf(download(url))))) }
        }
        assertFailsWith<IllegalArgumentException> { installer.apply(plan(manifest(listOf(download("https://cdn.modrinth.com/data/p/new.jar", size = 0))))) }
        assertTrue(Files.list(installer.mods).use { it.toList().isEmpty() })
    }

    /** An allowed CDN download is requested with the component User-Agent, verified and installed, using a fake HTTP client. */
    @Test
    fun cdnDownloadsAreRequestedVerifiedAndInstalled() {
        val source = Fixtures.jar(root.resolve("source.jar"), "m")
        val artifact = download("https://cdn.modrinth.com/data/p/new.jar", Files.size(source)).copy(sha512 = fileHash(source))
        val http = FakeHttpClient(200, Files.readAllBytes(source))
        val withFake = Installer(root) { http }
        withFake.apply(Resolver().plan(manifest(listOf(artifact)), emptyList(), Scanner().scan(withFake.mods), withFake.state().hashes))
        assertEquals(
            "Minecraft-ModSync/0.4.0",
            http.requests
                .single()
                .headers()
                .firstValue("User-Agent")
                .get()
        )
        assertEquals(artifact.sha512, fileHash(withFake.mods.resolve("new.jar")))
    }

    /** A non-200 CDN answer fails the download and leaves the mods directory untouched. */
    @Test
    fun failedCdnDownloadsInstallNothing() {
        val withFake = Installer(root) { FakeHttpClient(404, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            withFake.apply(Resolver().plan(manifest(listOf(download("https://cdn.modrinth.com/data/p/new.jar"))), emptyList(), Scanner().scan(withFake.mods), withFake.state().hashes))
        }
        assertTrue(Files.list(withFake.mods).use { it.toList().isEmpty() })
    }

    /** An interrupted installation must be rolled back before anything else is applied. */
    @Test
    fun incompleteJournalBlocksApply() {
        journal(UUID.randomUUID().toString())
        assertFailsWith<IllegalArgumentException> { installer.apply(plan(manifest())) }
        journal(UUID.randomUUID().toString(), complete = true)
        installer.apply(plan(manifest()))
    }

    /** A plan generated for another manifest or managed state is refused. */
    @Test
    fun foreignPlanIsRefused() {
        Fixtures.jar(installer.mods.resolve("kept.jar"), "kept")
        val stale = plan(manifest())
        val other = stale.copy(operations = stale.operations.reversed() + stale.operations)
        assertFailsWith<IllegalArgumentException> { installer.apply(other) }
    }

    /** Rollback removes files that did not exist before and restores files that were deleted. */
    @Test
    fun rollbackRemovesNewAndRestoresDeleted() {
        val id = UUID.randomUUID().toString()
        val backup = Files.createDirectories(root.resolve(".modsync/$id/backup"))
        Files.writeString(backup.resolve("gone.jar"), "old")
        Files.writeString(installer.mods.resolve("added.jar"), "added")
        journal(
            id,
            JournalEntry("added.jar", null, sha512("added".toByteArray())),
            JournalEntry("gone.jar", sha512("old".toByteArray()), null)
        )
        installer.rollback()
        assertFalse(Files.exists(installer.mods.resolve("added.jar")))
        assertEquals("old", Files.readString(installer.mods.resolve("gone.jar")))
    }

    /** Rollback refuses tampered backups, invalid journal ids and a missing journal. */
    @Test
    fun rollbackRefusesTampering() {
        val id = UUID.randomUUID().toString()
        val backup = Files.createDirectories(root.resolve(".modsync/$id/backup"))
        Files.writeString(backup.resolve("a.jar"), "tampered")
        journal(id, JournalEntry("a.jar", sha512("original".toByteArray()), null))
        assertFailsWith<IllegalArgumentException> { installer.rollback() }
        journal("not-a-uuid")
        assertFailsWith<IllegalArgumentException> { installer.rollback() }
        Files.delete(root.resolve(".modsync/journal.json"))
        assertFailsWith<java.nio.file.NoSuchFileException> { installer.rollback() }
    }

    /** Verification reports missing or changed managed files. */
    @Test
    fun verifyDetectsMissingFile() {
        val jar = Fixtures.jar(installer.mods.resolve("kept.jar"), "kept")
        val artifact = download(null).copy(fileName = "kept.jar", sha512 = fileHash(jar))
        secureWrite(root.resolve(".modsync/managed.json"), wire.encodeToString(ManagedState.serializer(), ManagedState(emptyMap(), manifest(listOf(artifact)), mapOf("kept.jar" to fileHash(jar)))))
        assertTrue(installer.verify())
        Files.delete(jar)
        assertFalse(installer.verify())
    }

    /** Archive validation accepts well-formed jars and refuses text files and archives with a huge number of entries. */
    @Test
    fun archiveValidation() {
        validateArchive(Fixtures.jar(root.resolve("ok.jar"), "ok"))
        val dirs = root.resolve("dirs.jar")
        ZipOutputStream(Files.newOutputStream(dirs)).use { zip ->
            zip.putNextEntry(ZipEntry("folder/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("folder/file.txt"))
            zip.write("data".toByteArray())
            zip.closeEntry()
        }
        validateArchive(dirs)
        Files.writeString(root.resolve("text.jar"), "not a zip")
        assertFailsWith<java.util.zip.ZipException> { validateArchive(root.resolve("text.jar")) }
    }
}
