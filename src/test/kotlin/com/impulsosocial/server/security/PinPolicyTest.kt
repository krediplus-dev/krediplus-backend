package com.impulsosocial.server.security

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PinPolicyTest {
    @Test
    fun `rechaza pines triviales y secuenciales`() {
        listOf("123456", "654321", "000000", "111111", "123123", "121212").forEach { pin ->
            assertNotNull(PinPolicy.validationError(pin), "El PIN $pin no debe aceptarse")
        }
    }

    @Test
    fun `acepta un pin de seis digitos no predecible`() {
        assertNull(PinPolicy.validationError("593817"))
    }
}
