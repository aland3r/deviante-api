package com.deviante

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {

    @Test
    fun `test root endpoint`() = testApplication {
        application {
            configureHttp()
            configureSerialization()
            configureRouting()
        }

        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `activity catalog requires authentication`() = testApplication {
        application {
            configureHttp()
            configureSerialization()
            configureRouting()
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/activities").status)
    }

    @Test
    fun `defined process activities require authentication`() = testApplication {
        application {
            configureHttp()
            configureSerialization()
            configureRouting()
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/processes/00000000-0000-0000-0000-000000000001/activities").status,
        )
    }
}
