package com.impulsosocial.server.config

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuntimeSecurityConfigurationTest {
    @Test
    fun `produccion rechaza secreto temporal o de ejemplo`() {
        assertNotNull(AppConfig(
            jwtSecret = "generated-secret-that-is-long-enough-for-this-test",
            usesGeneratedJwtSecret = true,
            requireStableJwtSecret = true,
            publicBaseUrl = "http://localhost:8080"
        ).runtimeSecurityValidationError())
        assertNotNull(AppConfig(
            jwtSecret = "CAMBIA_ESTA_CLAVE_POR_UNA_ALEATORIA_LARGA",
            usesGeneratedJwtSecret = false,
            requireStableJwtSecret = true,
            publicBaseUrl = "http://localhost:8080"
        ).runtimeSecurityValidationError())
    }

    @Test
    fun `acepta secreto estable y origen web seguro`() {
        val config = AppConfig(
            jwtSecret = "vJ7tfEomSNFWq4CgP9QzA6M3yL2kR8DxH5uB1nZ0",
            usesGeneratedJwtSecret = false,
            requireStableJwtSecret = true,
            corsAllowedOrigins = listOf("https://app.credicash.example"),
            publicBaseUrl = "http://localhost:8080"
        )
        assertNull(config.runtimeSecurityValidationError())
    }
}
