package de.alexandermora.modsync.server.auth

import de.alexandermora.modsync.protocol.Credentials
import de.alexandermora.modsync.domain.sha512
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

private const val AGENT_ROLE = "AGENT"
private const val AGENT_TOKEN_SECONDS = 30L * 86400
private const val ACCESS_TOKEN_SECONDS = 3600L
private const val REFRESH_TOKEN_SECONDS = 30L * 86400
private const val SECRET_BYTES = 32

@Service
class CredentialService(private val credentials: CredentialRepository) {
    private val random = SecureRandom()

    fun newSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SECRET_BYTES).also(random::nextBytes))

    fun issue(subject: String, role: String, device: String? = null): Credentials {
        val token = newSecret()
        val expires = Instant.now().plusSeconds(if (role == AGENT_ROLE) AGENT_TOKEN_SECONDS else ACCESS_TOKEN_SECONDS)
        credentials.saveAndFlush(CredentialEntity(sha512(token.toByteArray()), subject, role, device, expires))
        val refresh = if (role == AGENT_ROLE) null else newSecret().also {
            credentials.saveAndFlush(CredentialEntity(sha512(it.toByteArray()), subject, role, device, Instant.now().plusSeconds(REFRESH_TOKEN_SECONDS), "refresh"))
        }
        return Credentials(subject, token, expires.toString(), refresh)
    }
}
