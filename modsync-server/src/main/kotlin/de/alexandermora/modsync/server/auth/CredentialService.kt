package de.alexandermora.modsync.server.auth

import de.alexandermora.modsync.domain.sha512
import de.alexandermora.modsync.protocol.Credentials
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

private const val AGENT_ROLE = "AGENT"
private const val AGENT_TOKEN_SECONDS = 30L * 86400
private const val ACCESS_TOKEN_SECONDS = 3600L
private const val REFRESH_TOKEN_SECONDS = 30L * 86400
private const val SECRET_BYTES = 32

/**
 * Generates secrets and issues hashed credentials.
 *
 * @property credentials Credential repository.
 */
@Service
class CredentialService(private val credentials: CredentialRepository) {
    private val random = SecureRandom()

    /**
     * Generates an unguessable URL-safe secret from 32 bytes of [SecureRandom] output.
     *
     * @return The Base64url-encoded secret without padding.
     */
    fun newSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SECRET_BYTES).also(random::nextBytes))

    /**
     * Issues an access token and, for non-agent roles, a refresh token.
     *
     * Only SHA-512 hashes are persisted; the clear tokens exist solely in the returned [Credentials].
     * Agents get a long-lived access token and no refresh token.
     *
     * @param subject User or agent UUID.
     * @param role Role granted to the credentials.
     * @param device Device UUID for client credentials, null for agents.
     * @return The clear credentials.
     */
    fun issue(subject: String, role: String, device: String? = null): Credentials {
        val token = newSecret()
        val expires = Instant.now().plusSeconds(if (role == AGENT_ROLE) AGENT_TOKEN_SECONDS else ACCESS_TOKEN_SECONDS)
        credentials.saveAndFlush(CredentialEntity(sha512(token.toByteArray()), subject, role, device, expires))
        val refresh =
            if (role == AGENT_ROLE) {
                null
            } else {
                newSecret().also {
                    credentials.saveAndFlush(CredentialEntity(sha512(it.toByteArray()), subject, role, device, Instant.now().plusSeconds(REFRESH_TOKEN_SECONDS), "refresh"))
                }
            }
        return Credentials(subject, token, expires.toString(), refresh)
    }
}
