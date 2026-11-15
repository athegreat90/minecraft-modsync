package de.alexandermora.modsync.server

import de.alexandermora.modsync.domain.PublicationPolicy
import de.alexandermora.modsync.server.common.AuditService
import de.alexandermora.modsync.server.common.MaintenanceMode
import de.alexandermora.modsync.server.inventory.InventoryService
import de.alexandermora.modsync.server.publication.AutoPublicationService
import de.alexandermora.modsync.server.publication.PublicationPersistence
import de.alexandermora.modsync.server.ui.DashboardService
import de.alexandermora.modsync.server.ui.api.UiApiController
import de.alexandermora.modsync.server.ui.api.UiApiProblems
import de.alexandermora.modsync.server.users.UserCommandResult
import de.alexandermora.modsync.server.users.UserManagementService
import de.alexandermora.modsync.server.users.UserProblem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.http.MediaType
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.Test

/**
 * Verifies the JSON administration API without a database: status codes, payload shapes and that maintenance and refusals
 * are mapped consistently. Authentication, CSRF and role checks belong to the security chain and are covered by the
 * integration tests.
 */
class UiApiControllerTest {
    private val dashboard = Mockito.mock(DashboardService::class.java)
    private val inventory = Mockito.mock(InventoryService::class.java)
    private val persistence = Mockito.mock(PublicationPersistence::class.java)
    private val autoPublication = Mockito.mock(AutoPublicationService::class.java)
    private val audit = Mockito.mock(AuditService::class.java)
    private val management = Mockito.mock(UserManagementService::class.java)
    private val admin = "11111111-1111-1111-1111-111111111111"

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(admin, "n/a", "ROLE_ADMIN")
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun mvc(maintenance: Boolean = false): MockMvc =
        MockMvcBuilders
            .standaloneSetup(UiApiController(dashboard, inventory, persistence, autoPublication, audit, MaintenanceMode(maintenance), management))
            .setControllerAdvice(UiApiProblems())
            .setMessageConverters(KotlinSerializationJsonHttpMessageConverter())
            .build()

    /** The session endpoint reports the account behind the authenticated principal. */
    @Test
    fun sessionNamesTheSignedInAccount() {
        Mockito
            .`when`(management.session(admin))
            .thenReturn(
                de.alexandermora.modsync.server.users
                    .UserSummary(admin, "root", "ADMIN", true, false)
            )

        mvc()
            .perform(get("/ui/api/session"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("root"))
            .andExpect(jsonPath("$.role").value("ADMIN"))
    }

    /** A session whose account was deleted answers 404, as before. */
    @Test
    fun sessionIsNotFoundWhenTheAccountIsGone() {
        Mockito.`when`(management.session(admin)).thenReturn(null)

        mvc().perform(get("/ui/api/session")).andExpect(status().isNotFound)
    }

    /** An empty installation yields an empty JSON list, not an error. */
    @Test
    fun overviewIsAJsonList() {
        Mockito.`when`(dashboard.overview()).thenReturn(emptyList())

        mvc().perform(get("/ui/api/overview")).andExpect(status().isOk).andExpect(jsonPath("$").isArray)
    }

    /** Policy changes are applied, audited and acknowledged. */
    @Test
    fun policyChangeIsAudited() {
        val server = "22222222-2222-2222-2222-222222222222"
        Mockito.`when`(persistence.setPolicy(server, PublicationPolicy.TRUSTED_AUTO)).thenReturn(true)

        mvc()
            .perform(put("/ui/api/servers/$server/publication-policy").contentType(MediaType.APPLICATION_JSON).content("""{"policy":"TRUSTED_AUTO"}"""))
            .andExpect(status().isOk)

        verify(audit).record(admin, "publication-policy-set:TRUSTED_AUTO", server)
    }

    /** Maintenance mode rejects writes with 409 and changes nothing. */
    @Test
    fun maintenanceRejectsWrites() {
        val server = "22222222-2222-2222-2222-222222222222"

        mvc(maintenance = true)
            .perform(put("/ui/api/servers/$server/publication-policy").contentType(MediaType.APPLICATION_JSON).content("""{"policy":"MANUAL"}"""))
            .andExpect(status().isConflict)

        verify(persistence, never()).setPolicy(server, PublicationPolicy.MANUAL)
    }

    /** A new account answers 201 with its id. */
    @Test
    fun createdAccountAnswers201() {
        Mockito.`when`(management.create(admin, "player", "p".repeat(16), "p".repeat(16))).thenReturn(UserCommandResult.Done("new-id"))

        mvc()
            .perform(
                post("/ui/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username":"player","password":"${"p".repeat(16)}","confirmation":"${"p".repeat(16)}"}""")
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.userId").value("new-id"))
    }

    /** Each refusal reason keeps its own status and a stable code, and never echoes input. */
    @Test
    fun refusalsMapToStatusAndCode() {
        val cases =
            mapOf(
                UserProblem.NOT_FOUND to 404,
                UserProblem.USERNAME_TAKEN to 409,
                UserProblem.NOT_MANAGEABLE to 403,
                UserProblem.INVALID_PASSWORD to 422
            )
        cases.forEach { (problem, code) ->
            Mockito.`when`(management.disable(admin, "target")).thenReturn(UserCommandResult.Failed(problem))

            mvc()
                .perform(post("/ui/api/users/target/disable"))
                .andExpect(status().`is`(code))
                .andExpect(jsonPath("$.code").value(problem.name))
        }
    }

    /** Revoking every device needs the explicit confirmation, enforced on the server. */
    @Test
    fun revokingDevicesNeedsConfirmation() {
        mvc()
            .perform(post("/ui/api/users/target/revoke-devices").contentType(MediaType.APPLICATION_JSON).content("""{"confirm":false}"""))
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"))

        verify(management, never()).revokeDevices(admin, "target")
    }
}
