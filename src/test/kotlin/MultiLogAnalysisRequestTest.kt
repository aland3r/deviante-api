package com.deviante

import com.deviante.dto.RunAnalysisRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiLogAnalysisRequestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old analysis clients keep latest-log fallback`() {
        val request = json.decodeFromString<RunAnalysisRequest>("{}")

        assertTrue(request.eventLogIds.isEmpty())
    }

    @Test
    fun `analysis request carries every selected event log id`() {
        val request = json.decodeFromString<RunAnalysisRequest>(
            """{"eventLogIds":["00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002"]}""",
        )

        assertEquals(2, request.eventLogIds.size)
        assertEquals("00000000-0000-0000-0000-000000000002", request.eventLogIds.last())
    }
}
