package com.impulsosocial.server.security

import com.auth0.jwt.JWT
import com.impulsosocial.server.config.AppConfig
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtServiceTest {
    private fun config(
        secret: String = "0123456789abcdef0123456789abcdef",
        ttlMinutes: Long = 60L
    ) = AppConfig(
        dbUrl = "jdbc:postgresql://localhost:5432/test",
        dbUser = "test",
        dbPassword = "test",
        dbConfigurationSource = "test",
        jwtSecret = secret,
        usesGeneratedJwtSecret = false,
        jwtAccessTokenTtlMinutes = ttlMinutes,
        publicBaseUrl = "http://localhost:8080",
        uploadDir = "build/test-uploads"
    )

    @Test
    fun `el token de acceso incluye expiracion`() {
        val before = Instant.now()
        val token = JwtService(config(ttlMinutes = 45L))
            .createAccessToken(7L, Roles.ADMIN, UUID.randomUUID(), "DESKTOP")
        val decoded = JWT.decode(token)
        val issuedAt = assertNotNull(decoded.issuedAt).toInstant()
        val expiresAt = assertNotNull(decoded.expiresAt).toInstant()

        assertEquals("7", decoded.subject)
        assertEquals(Roles.ADMIN, decoded.getClaim("role").asString())
        assertEquals("DESKTOP", decoded.getClaim("client").asString())
        assertTrue(!issuedAt.isBefore(before.minusSeconds(2)))
        assertEquals(45L, Duration.between(issuedAt, expiresAt).toMinutes())
    }

    @Test
    fun `rechaza secretos jwt demasiado cortos`() {
        assertFailsWith<IllegalArgumentException> { JwtService(config(secret = "muy-corto")) }
    }
}
