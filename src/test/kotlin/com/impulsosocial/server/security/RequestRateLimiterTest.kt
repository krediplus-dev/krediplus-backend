package com.impulsosocial.server.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestRateLimiterTest {
    @Test
    fun `bloquea al superar el limite y libera al vencer la ventana`() {
        var now = 1_000L
        val limiter = RequestRateLimiter(maxRequests = 2, windowSeconds = 10, nowMillis = { now })

        assertTrue(limiter.check("login:127.0.0.1").allowed)
        assertTrue(limiter.check("login:127.0.0.1").allowed)
        val blocked = limiter.check("login:127.0.0.1")
        assertFalse(blocked.allowed)
        assertTrue(blocked.retryAfterSeconds > 0)

        now += 10_000L
        assertTrue(limiter.check("login:127.0.0.1").allowed)
    }

    @Test
    fun `mantiene limites separados por accion y origen`() {
        val limiter = RequestRateLimiter(maxRequests = 1, windowSeconds = 60)
        assertTrue(limiter.check("login:uno").allowed)
        assertFalse(limiter.check("login:uno").allowed)
        assertTrue(limiter.check("pin:uno").allowed)
        assertTrue(limiter.check("login:dos").allowed)
    }
}
