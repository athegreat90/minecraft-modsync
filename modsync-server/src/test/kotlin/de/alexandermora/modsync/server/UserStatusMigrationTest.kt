package de.alexandermora.modsync.server

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import kotlin.test.assertEquals

/** Verifies that the user-status migration upgrades an existing installation without changing its accounts. */
@Testcontainers
class UserStatusMigrationTest {
    companion object {
        @Container @JvmField
        val database = MariaDBContainer<Nothing>("mariadb:11.4.10")
    }

    /** Builds a Flyway instance against the test database that stops at [version] (or the latest when null). */
    private fun flyway(version: String?): Flyway =
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .apply { version?.let { target(it) } }
            .load()

    /** Accounts created before V5 stay enabled and keep every other column. */
    @Test fun existingAccountsStayEnabled() {
        flyway("4").migrate()
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
            connection.createStatement().use {
                it.executeUpdate("INSERT INTO users (id, username, password_hash, role) VALUES ('11111111-1111-1111-1111-111111111111', 'legacy', 'hash', 'CLIENT')")
            }
            flyway(null).migrate()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT username, password_hash, role, enabled FROM users").use { rows ->
                    check(rows.next()) { "Legacy account was lost" }
                    assertEquals(listOf("legacy", "hash", "CLIENT", true), listOf(rows.getString(1), rows.getString(2), rows.getString(3), rows.getBoolean(4)))
                }
            }
        }
    }
}
