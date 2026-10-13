package com.deviante

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppConfigTest {
    @Test
    fun `there is a single instance`() {
        assertSame(AppConfig, AppConfig)
    }

    @Test
    fun `configuration is loaded once and reused`() {
        AppConfig.init(MapApplicationConfig("mining.url" to "http://primeira:8000"))
        val loaded = AppConfig.miningUrl

        AppConfig.init(MapApplicationConfig("mining.url" to "http://segunda:8000"))

        assertEquals(loaded, AppConfig.miningUrl)
    }
}
