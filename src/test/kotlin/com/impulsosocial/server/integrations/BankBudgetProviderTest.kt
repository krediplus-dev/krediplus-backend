package com.impulsosocial.server.integrations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BankBudgetProviderTest {
    @Test
    fun `la integracion bancaria permanece desactivada por defecto`() {
        val provider = PreparedBankBudgetProvider()
        assertEquals("DISABLED", provider.connectionStatus().status)
        assertFailsWith<UnsupportedOperationException> { provider.fetchAvailableBudget("cuenta-prueba") }
    }
}
