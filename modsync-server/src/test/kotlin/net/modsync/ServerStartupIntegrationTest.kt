package net.modsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.springframework.boot.web.server.context.WebServerApplicationContext
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
            val base="http://127.0.0.1:${requireNotNull((context as WebServerApplicationContext).webServer).port}/api/v1"
            val http=HttpClient.newHttpClient()
            fun request(path: String, method: String, body: String, token: String? = null): String {
                val builder=HttpRequest.newBuilder(URI("$base/$path"))
                    .header("Content-Type","application/json")
                    .header("X-ModSync-Protocol","1").header("X-ModSync-Version","0.1.0")
                    .method(method,HttpRequest.BodyPublishers.ofString(body))
                if(token != null) builder.header("Authorization","Bearer $token")
                val response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString())
                assertEquals(200,response.statusCode(),"$method $path")
                assertTrue(response.body().isNotBlank(),"$method $path returned an empty body")
                return response.body()
            }
            val credentials=wire.decodeFromString<Credentials>(request("auth/login","POST",wire.encodeToString(Login("admin","fixture-only-bootstrap-password",UUID.randomUUID().toString()))))
            val profile=PersonalProfile(UUID.randomUUID().toString(),"Response regression",emptyList())
            val saved=wire.decodeFromString<PersonalProfile>(request("personal/${profile.id}","PUT",wire.encodeToString(profile),credentials.token))
            assertEquals(profile,saved)
        }
    }
}
