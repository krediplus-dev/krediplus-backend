package com.impulsosocial.server.service

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentReviewPolicyTest {
    @Test
    fun `normaliza referencia y método`() {
        assertEquals("00123456", PaymentReviewPolicy.normalizeReference("00 12 34 56"))
        assertEquals("MOBILE_PAYMENT", PaymentReviewPolicy.normalizeMethod(" mobile_payment "))
    }

    @Test
    fun `rechaza letras y separadores en la referencia`() {
        assertFailsWith<AppException> { PaymentReviewPolicy.normalizeReference("12-34AB") }
    }

    @Test
    fun `detecta diferencia material`() {
        val result = PaymentReviewPolicy.compare(BigDecimal("120.00"), BigDecimal("100.00"))
        assertTrue(result.materialDifference)
        assertEquals(BigDecimal("20.00"), result.differenceBs)
    }

    @Test
    fun `tolera diferencia mínima por redondeo`() {
        val result = PaymentReviewPolicy.compare(BigDecimal("100.40"), BigDecimal("100.00"))
        assertFalse(result.materialDifference)
    }

    @Test
    fun `no aprueba sin confirmación bancaria`() {
        assertFailsWith<AppException> {
            PaymentReviewPolicy.validateDecision(
                "REPORTED", true, false, 10,
                BigDecimal("100"), BigDecimal("100"), null
            )
        }
    }

    @Test
    fun `exige nota al aprobar monto diferente`() {
        assertFailsWith<AppException> {
            PaymentReviewPolicy.validateDecision(
                "REPORTED", true, true, 10,
                BigDecimal("130"), BigDecimal("100"), null
            )
        }
    }
}
