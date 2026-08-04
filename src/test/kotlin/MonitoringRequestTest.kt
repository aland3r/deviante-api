package com.deviante

import com.deviante.dto.CreateMonitoringRequest
import com.deviante.dto.toMonitoringRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MonitoringRequestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `empty monitoring request receives a usable default name`() {
        val request = json.decodeFromString<CreateMonitoringRequest>("{}")

        assertEquals("Novo monitoramento", request.toMonitoringRequest().name)
    }

    @Test
    fun `null or blank monitoring name receives the default`() {
        val nullName = json.decodeFromString<CreateMonitoringRequest>("{\"name\":null}")

        assertEquals("Novo monitoramento", nullName.toMonitoringRequest().name)
        assertEquals("Novo monitoramento", CreateMonitoringRequest("   ").toMonitoringRequest().name)
    }

    @Test
    fun `explicit monitoring name is trimmed and preserved`() {
        assertEquals(
            "Linha CNC",
            CreateMonitoringRequest("  Linha CNC  ").toMonitoringRequest().name,
        )
    }
}
