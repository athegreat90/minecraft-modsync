package de.alexandermora.modsync.server

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val PASSWORD_LENGTHS_ERROR = "Bootstrap password must have 16-256 characters"

/**
 * Spring Boot entry point of the ModSync control plane (Host B).
 *
 * Enables scheduling for the background publication and cleanup jobs.
 */
@EnableScheduling
@SpringBootApplication
class ServerApplication {
    /**
     * Builds the startup runner that seeds the control-plane id (idempotent) and, only when no user exists yet, creates the bootstrap admin.
     *
     * The password is read from [file] (never from configuration values) and must have 16-256 characters;
     * startup fails fast otherwise so the server never runs without an administrator.
     *
     * @param db JDBC access used for the seeding statements.
     * @param passwords Encoder that hashes the bootstrap password.
     * @param user Username of the bootstrap administrator.
     * @param file Path of the file holding the bootstrap password; may be blank once a user exists.
     * @return The runner executed once at startup.
     */
    @Bean fun initialize(
        db: JdbcTemplate,
        passwords: Argon2PasswordEncoder,
        @Value($$"${modsync.bootstrap-user}") user: String,
        @Value($$"${modsync.bootstrap-password-file}") file: String
    ) =
        CommandLineRunner {
            db.update("INSERT IGNORE INTO system_settings(name,value) VALUES ('control_plane_id',?)", UUID.randomUUID().toString())
            if (db.queryForObject("SELECT COUNT(*) FROM users", Long::class.java) == 0L) {
                require(file.isNotBlank()) { "First startup requires MODSYNC_BOOTSTRAP_PASSWORD_FILE" }
                val password = Files.readString(Path.of(file)).trimEnd('\r', '\n')
                require(password.length in 16..256) { PASSWORD_LENGTHS_ERROR }
                db.update("INSERT INTO users(id,username,password_hash,role) VALUES (?,?,?,'ADMIN')", UUID.randomUUID().toString(), user, passwords.encode(password))
            }
        }
}

/**
 * Starts the Spring Boot control plane.
 *
 * @param args Command-line arguments forwarded to Spring.
 */
fun main(args: Array<String>) {
    runApplication<ServerApplication>(*args)
}
