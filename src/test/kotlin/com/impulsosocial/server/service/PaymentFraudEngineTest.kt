package com.impulsosocial.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentFraudEngineTest {
    @Test
    fun `reporte consistente conserva riesgo bajo`() {
        val result = PaymentFraudEngine.evaluate(
            PaymentFraudInput(
                expectedAmountBs = 1000.0,
                reportedAmountBs = 1000.0,
                referenceNumber = "123456789012",
                bankExists = true,
                proofPresent = true,
                proofDuplicateCount = 0,
                proofVisualNearDuplicateCount = 0,
                proofImageReadable = true,
                sameReferenceCount = 0,
                sameReferenceOtherUsersCount = 0,
                paidFromDifferentPhone = false,
                originPhoneMatchesProfile = true,
                priorRejectedReports = 0,
                reportsLast24Hours = 1
            )
        )
        assertTrue(result.riskScore < 15)
        assertEquals("VERY_LOW", result.riskLevel)
    }

    @Test
    fun `referencia y comprobante reutilizados elevan riesgo`() {
        val result = PaymentFraudEngine.evaluate(
            PaymentFraudInput(
                expectedAmountBs = 1000.0,
                reportedAmountBs = 700.0,
                referenceNumber = "1234",
                bankExists = true,
                proofPresent = true,
                proofDuplicateCount = 1,
                proofVisualNearDuplicateCount = 1,
                proofImageReadable = true,
                sameReferenceCount = 2,
                sameReferenceOtherUsersCount = 1,
                paidFromDifferentPhone = true,
                originPhoneMatchesProfile = false,
                priorRejectedReports = 3,
                reportsLast24Hours = 7
            )
        )
        assertEquals(100, result.riskScore)
        assertEquals("CRITICAL", result.riskLevel)
        assertTrue(result.reasons.any { it.contains("otra cuenta") })
    }
    @Test
    fun `comprobante visualmente parecido requiere revision reforzada`() {
        val result = PaymentFraudEngine.evaluate(
            PaymentFraudInput(
                expectedAmountBs = 1000.0,
                reportedAmountBs = 1000.0,
                referenceNumber = "998877665544",
                bankExists = true,
                proofPresent = true,
                proofDuplicateCount = 0,
                proofVisualNearDuplicateCount = 1,
                proofImageReadable = true,
                sameReferenceCount = 0,
                sameReferenceOtherUsersCount = 0,
                paidFromDifferentPhone = false,
                originPhoneMatchesProfile = true,
                priorRejectedReports = 0,
                reportsLast24Hours = 1
            )
        )
        assertTrue(result.riskScore >= 34)
        assertTrue(result.reasons.any { it.contains("se parece") })
    }

}
