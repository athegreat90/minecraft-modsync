package de.alexandermora.modsync.server.config

import de.alexandermora.modsync.modrinth.Modrinth
import de.alexandermora.modsync.protocol.wire
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

/** Declares the shared infrastructure beans: wire-format JSON, Modrinth client and password encoder. */
@Configuration
class ApplicationConfiguration {
    /**
     * Provides the shared wire format; the kotlinx message converter uses it so HTTP bodies match the client protocol.
     *
     * @return The protocol [Json] instance.
     */
    @Bean fun json(): Json = wire

    /**
     * Provides the bounded, rate-limited Modrinth API client used for hash identification and update lookups.
     *
     * @return A new client with default limits.
     */
    @Bean fun modrinth() = Modrinth()

    /**
     * Provides the Argon2id encoder that hashes user passwords.
     *
     * @return Encoder using the Spring Security 5.8 default parameters.
     */
    @Bean fun passwords() = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
}
