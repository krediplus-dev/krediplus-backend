package com.impulsosocial.server.service

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredictiveEngineTest {
    @Test
    fun `historial sano obtiene mayor probabilidad que historial moroso`() {
        val healthy = PredictiveEngine.subject(
            PredictiveSubjectInput(
                subjectId = 1,
                username = "sano",
                displayName = "Usuario sano",
                role = "BENEFICIARY",
                creditScorePercent = 96,
                onTimePayments = 18,
                latePayments = 1,
                verifiedDirectPayments = 8,
                rejectedDirectPayments = 0,
                totalPurchases = 24,
                completedPurchases = 23,
                cancelledPurchases = 1,
                totalPurchasedUsd = BigDecimal("1800.00"),
                outstandingUsd = BigDecimal("120.00"),
                overdueUsd = BigDecimal.ZERO,
                currentCreditLimitUsd = BigDecimal("500.00"),
                activityDays = 300
            )
        )
        val risky = PredictiveEngine.subject(
            PredictiveSubjectInput(
                subjectId = 2,
                username = "riesgo",
                displayName = "Usuario riesgoso",
                role = "BENEFICIARY",
                creditScorePercent = 35,
                onTimePayments = 1,
                latePayments = 8,
                verifiedDirectPayments = 1,
                rejectedDirectPayments = 4,
                totalPurchases = 10,
                completedPurchases = 3,
                cancelledPurchases = 7,
                totalPurchasedUsd = BigDecimal("900.00"),
                outstandingUsd = BigDecimal("500.00"),
                overdueUsd = BigDecimal("420.00"),
                currentCreditLimitUsd = BigDecimal("500.00"),
                activityDays = 20
            )
        )

        assertTrue(healthy.paymentSuccessPercent > risky.paymentSuccessPercent)
        assertTrue(healthy.purchaseSuccessPercent > risky.purchaseSuccessPercent)
        assertTrue(healthy.recommendedCreditLimitUsd > risky.recommendedCreditLimitUsd)
        assertTrue(healthy.confidencePercent in BigDecimal("35.00")..BigDecimal("95.00"))
    }

    @Test
    fun `el motor es determinista`() {
        val input = PredictiveSubjectInput(
            subjectId = 8,
            username = "usuario",
            displayName = "Usuario",
            role = "ADMIN",
            creditScorePercent = 80,
            verifiedDirectPayments = 12,
            rejectedDirectPayments = 2,
            totalPurchases = 15,
            completedPurchases = 13,
            cancelledPurchases = 2,
            allocatedBudgetUsd = BigDecimal("10000.00"),
            availableBudgetUsd = BigDecimal("4200.00"),
            activityDays = 180
        )
        assertEquals(PredictiveEngine.subject(input), PredictiveEngine.subject(input))
    }

    @Test
    fun `proyeccion presupuestaria entrega horizontes validos`() {
        val result = PredictiveEngine.budget(
            PredictiveBudgetInput(
                consolidatedAvailableUsd = BigDecimal("50000.00"),
                reservedUsd = BigDecimal("3000.00"),
                outstandingLoansUsd = BigDecimal("12000.00"),
                overdueLoansUsd = BigDecimal("900.00"),
                recoveredLoansUsd = BigDecimal("18000.00"),
                disbursedLoansUsd = BigDecimal("22000.00"),
                recentOperatingExpenses90DaysUsd = BigDecimal("6000.00"),
                recentBankIncome90DaysUsd = BigDecimal("9000.00"),
                activeBorrowers = 40,
                observedInstallments = 120
            )
        )
        assertEquals(listOf(30, 60, 90), result.forecasts.map { it.horizonDays })
        assertTrue(result.forecasts.all { it.projectedAvailableUsd.signum() >= 0 })
        assertTrue(result.collectionProbabilityPercent in BigDecimal.ZERO..BigDecimal("100.00"))
        assertTrue(result.defaultRiskPercent in BigDecimal.ZERO..BigDecimal("100.00"))
    }
}
