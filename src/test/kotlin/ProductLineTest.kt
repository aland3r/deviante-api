package com.deviante

import com.deviante.variability.Feature
import com.deviante.variability.Plan
import com.deviante.variability.ProductLine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.config.yaml.YamlConfig
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductLineTest {
    private val productLine = ProductLine.fromConfig(YamlConfig("application.yaml")!!)

    @Test
    fun `each client receives its own variant`() {
        val alfa = productLine.variantFor("demo-gestor@deviante.alander.io")
        val beta = productLine.variantFor("demo-operador@deviante.alander.io")
        val gama = productLine.variantFor("DEMO-TECNICO@deviante.alander.io ")

        assertEquals("Metalúrgica Alfa", alfa.client)
        assertEquals(Plan.MANUTENCAO, alfa.plan)
        assertTrue(alfa.has(Feature.SCHEDULE_EXPORT))

        assertEquals("Metalúrgica Beta", beta.client)
        assertEquals(Plan.ESSENCIAL, beta.plan)
        assertFalse(beta.has(Feature.EQUIPMENT))
        assertFalse(beta.has(Feature.SCHEDULES))

        assertEquals("Metalúrgica Gama", gama.client)
        assertTrue(gama.has(Feature.SCHEDULES))
        assertFalse(gama.has(Feature.SCHEDULE_EXPORT))
    }

    @Test
    fun `common features are in every plan`() {
        Plan.entries.forEach { plan ->
            assertTrue(plan.features.containsAll(setOf(Feature.PROCESSES, Feature.ACTIVITIES, Feature.DRIFT_ANALYSIS)), plan.key)
        }
    }

    @Test
    fun `unknown accounts get the default variant`() {
        assertEquals(Plan.MANUTENCAO, productLine.variantFor("alguem@example.com").plan)
    }

    @Test
    fun `a route-scoped gate that responds stops the handler`() = testApplication {
        val gate = createRouteScopedPlugin("TestGate") {
            onCall { call -> call.respond(HttpStatusCode.Forbidden, "fora do plano") }
        }
        application {
            routing {
                route("/modulo") {
                    install(gate)
                    get { call.respondText("handler") }
                }
            }
        }
        val response = client.get("/modulo")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("fora do plano", response.bodyAsText())
    }

    @Test
    fun `schedules csv quotes every cell`() {
        val csv = schedulesToCsv(listOf(
            com.deviante.dto.MaintenanceScheduleResponse(
                id = "1", equipmentId = "e1", title = "Troca \"A\"", notes = "", scheduledStart = "2026-10-14T08:00:00Z",
                status = "planned", createdAt = "", updatedAt = "",
            ),
        ))
        assertEquals("titulo;equipamento;inicio;fim;situacao;observacoes\n\"Troca \"\"A\"\"\";\"e1\";\"2026-10-14T08:00:00Z\";\"\";\"planned\";\"\"\n", csv)
    }
}
