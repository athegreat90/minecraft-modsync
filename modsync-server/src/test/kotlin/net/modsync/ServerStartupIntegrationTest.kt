package net.modsync

import java.nio.file.Files
import java.nio.file.Path
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
class ServerStartupIntegrationTest {
    companion object {
        @Container @JvmField val database=MariaDBContainer<Nothing>("mariadb:11.4.10")
    }
    @TempDir lateinit var state: Path
    @Test fun `fresh backend runs Flyway before bootstrap`() {
        val password=state.resolve("bootstrap-password")
        Files.writeString(password,"fixture-only-bootstrap-password")
        SpringApplicationBuilder(ServerApplication::class.java).run(
            "--server.port=0",
            "--spring.datasource.url=${database.jdbcUrl}",
            "--spring.datasource.username=${database.username}",
            "--spring.datasource.password=${database.password}",
            "--modsync.bootstrap-password-file=$password"
        ).use { context ->
            val store=context.getBean(Store::class.java)
            assertEquals(1L,store.db.queryForObject("SELECT COUNT(*) FROM users WHERE username='admin'",Long::class.java))
            assertEquals(1L,store.db.queryForObject("SELECT COUNT(*) FROM system_settings WHERE name='control_plane_id'",Long::class.java))
            assertTrue(context.getBean(Flyway::class.java).info().applied().isNotEmpty())
        }
    }
}
