package de.alexandermora.modsync.server.config

import kotlinx.serialization.json.Json
import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.protocol.wire
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

@Configuration
class ApplicationConfiguration {
    /** The shared wire format; the kotlinx message converter uses it so HTTP bodies match the client protocol. */
    @Bean fun json(): Json = wire
    @Bean fun modrinth() = Modrinth()
    @Bean fun passwords() = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
}
