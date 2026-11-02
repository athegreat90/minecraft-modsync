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

@EnableScheduling
@SpringBootApplication
class ServerApplication {
    @Bean fun initialize(db: JdbcTemplate, passwords: Argon2PasswordEncoder,
        @Value($$"${modsync.bootstrap-user}") user: String,
        @Value($$"${modsync.bootstrap-password-file}") file: String) = CommandLineRunner {
        db.update("INSERT IGNORE INTO system_settings(name,value) VALUES ('control_plane_id',?)", UUID.randomUUID().toString())
        if (db.queryForObject("SELECT COUNT(*) FROM users", Long::class.java) == 0L) {
            require(file.isNotBlank()) { "First startup requires MODSYNC_BOOTSTRAP_PASSWORD_FILE" }
            val password = Files.readString(Path.of(file)).trimEnd('\r', '\n')
            require(password.length in 16..256) { PASSWORD_LENGTHS_ERROR }
            db.update("INSERT INTO users(id,username,password_hash,role) VALUES (?,?,?,'ADMIN')", UUID.randomUUID().toString(), user, passwords.encode(password))
        }
    }
}

fun main(args: Array<String>) { runApplication<ServerApplication>(*args) }
