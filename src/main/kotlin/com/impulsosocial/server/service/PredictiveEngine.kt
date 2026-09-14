package com.impulsosocial.server.service

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Motor predictivo explicable de Kredi+.
 *
 * No utiliza números aleatorios ni cajas negras. Aplica suavizado bayesiano,
 * ponderación por recencia, comportamiento de compra, morosidad y capacidad
 * financiera. Por ello el mismo historial siempre genera el mismo resultado y
 * cada porcentaje puede auditarse mediante sus factores.
 */
data class PredictiveSubjectInput(
    val subjectId: Long,
    val username: String,
    val displayName: String,
    val role: String,
    val creditScorePercent: Int = 100,
    val onTimePayments: Int = 0,
    val latePayments: Int = 0,
    val verifiedDirectPayments: Int = 0,
    val rejectedDirectPayments: Int = 0,
    val totalPurchases: Int = 0,
    val completedPurchases: Int = 0,
    val cancelledPurchases: Int = 0,
    val totalPurchasedUsd: BigDecimal = BigDecimal.ZERO,
    val outstandingUsd: BigDecimal = BigDecimal.ZERO,
    val overdueUsd: BigDecimal = BigDecimal.ZERO,
    val currentCreditLimitUsd: BigDecimal = BigDecimal.ZERO,
    val allocatedBudgetUsd: BigDecimal = BigDecimal.ZERO,
    val availableBudgetUsd: BigDecimal = BigDecimal.ZERO,
    val activityDays: Int = 0
)

data class PredictiveFactorResult(
    val code: String,
    val label: String,
    val value: BigDecimal,
    val weight: BigDecimal,
    val impact: String,
    val explanation: String
)

data class PredictiveSubjectResult(
    val paymentSuccessPercent: BigDecimal,
    val purchaseSuccessPercent: BigDecimal,
    val latePaymentProbabilityPercent: BigDecimal,
    val confidencePercent: BigDecimal,
    val riskLevel: String,
    val recommendedCreditLimitUsd: BigDecimal,
    val predictedNextPurchaseUsd: BigDecimal,
    val factors: List<PredictiveFactorResult>
)

data class PredictiveBudgetInput(
    val consolidatedAvailableUsd: BigDecimal,
    val reservedUsd: BigDecimal,
    val outstandingLoansUsd: BigDecimal,
    val overdueLoansUsd: BigDecimal,
    val recoveredLoansUsd: BigDecimal,
    val disbursedLoansUsd: BigDecimal,
    val recentOperatingExpenses90DaysUsd: BigDecimal,
    val recentBankIncome90DaysUsd: BigDecimal,
    val activeBorrowers: Int,
    val observedInstallments: Int
)

data class PredictiveBudgetPoint(
    val horizonDays: Int,
    val expectedCollectionsUsd: BigDecimal,
    val expectedOperatingExpensesUsd: BigDecimal,
    val expectedBankIncomeUsd: BigDecimal,
    val expectedOverdueUsd: BigDecimal,
    val projectedAvailableUsd: BigDecimal,
    val confidencePercent: BigDecimal
)

data class PredictiveBudgetResult(
    val collectionProbabilityPercent: BigDecimal,
    val defaultRiskPercent: BigDecimal,
    val liquidityRiskLevel: String,
    val confidencePercent: BigDecimal,
    val forecasts: List<PredictiveBudgetPoint>,
    val alerts: List<String>
)

object PredictiveEngine {
    private val HUNDRED = BigDecimal("100")
    private val ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)

    fun subject(input: PredictiveSubjectInput): PredictiveSubjectResult {
        val paymentSuccesses = input.onTimePayments + input.verifiedDirectPayments
        val paymentFailures = input.latePayments + input.rejectedDirectPayments
        val paymentObservations = paymentSuccesses + paymentFailures
        val purchaseFailures = input.cancelledPurchases.coerceAtLeast(0)
        val completed = input.completedPurchases.coerceAtLeast(0)
        val purchaseObservations = max(input.totalPurchases, completed + purchaseFailures)

        // Priors conservadores: 4 éxitos y 1 falla evitan extremos con muestras pequeñas.
        val paymentBayes = ratio(paymentSuccesses + 4, paymentObservations + 5)
        val purchaseBayes = ratio(completed + 4, purchaseObservations + 5)
        val creditScore = input.creditScorePercent.coerceIn(0, 100) / 100.0
        val overdueRatio = if (input.outstandingUsd.signum() <= 0) 0.0 else
            input.overdueUsd.divide(input.outstandingUsd, 8, RoundingMode.HALF_EVEN).toDouble().coerceIn(0.0, 1.0)
        val activityStability = (ln(1.0 + input.activityDays.coerceAtLeast(0)) / ln(366.0)).coerceIn(0.0, 1.0)

        val paymentRaw = (
            paymentBayes * 0.50 +
                creditScore * 0.25 +
                purchaseBayes * 0.10 +
                activityStability * 0.05 +
                (1.0 - overdueRatio) * 0.10
            ).coerceIn(0.0, 1.0)
        val purchaseRaw = (
            purchaseBayes * 0.62 +
                paymentBayes * 0.20 +
                creditScore * 0.10 +
                activityStability * 0.08
            ).coerceIn(0.0, 1.0)

        // Curva logística suave para evitar saltos bruscos entre historiales parecidos.
        val payment = logisticNormalize(paymentRaw)
        val purchase = logisticNormalize(purchaseRaw)
        val lateProbability = (100.0 - payment).coerceIn(0.0, 100.0)
        val sampleSize = paymentObservations + purchaseObservations
        val confidence = (35.0 + 60.0 * (1.0 - exp(-sampleSize / 12.0))).coerceIn(35.0, 95.0)

        val currentLimit = input.currentCreditLimitUsd.coerceAtLeast(BigDecimal.ZERO)
        val adminBase = input.allocatedBudgetUsd.max(input.availableBudgetUsd)
        val limitBase = if (input.role.equals("ADMIN", true)) adminBase else currentLimit
        val riskMultiplier = when {
            payment >= 90.0 -> BigDecimal("1.20")
            payment >= 80.0 -> BigDecimal("1.05")
            payment >= 65.0 -> BigDecimal("0.85")
            payment >= 50.0 -> BigDecimal("0.60")
            else -> BigDecimal("0.35")
        }
        val recommended = limitBase.multiply(riskMultiplier, MoneyMath.CONTEXT).setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)
        val avgPurchase = if (input.totalPurchases > 0)
            input.totalPurchasedUsd.divide(BigDecimal(input.totalPurchases), 2, RoundingMode.HALF_EVEN)
        else BigDecimal.ZERO
        val predictedNext = avgPurchase.multiply(BigDecimal.valueOf(purchase / 100.0), MoneyMath.CONTEXT).setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)

        val risk = when {
            payment >= 88.0 && overdueRatio < 0.05 -> "VERY_LOW"
            payment >= 75.0 && overdueRatio < 0.15 -> "LOW"
            payment >= 60.0 && overdueRatio < 0.30 -> "MEDIUM"
            payment >= 42.0 -> "HIGH"
            else -> "CRITICAL"
        }

        val factors = listOf(
            factor("PAYMENT_HISTORY", "Historial de pagos", paymentBayes * 100.0, 50.0,
                if (paymentBayes >= .8) "POSITIVE" else if (paymentBayes >= .6) "NEUTRAL" else "NEGATIVE",
                "$paymentSuccesses pagos favorables de $paymentObservations observaciones"),
            factor("CREDIT_SCORE", "Puntaje crediticio", creditScore * 100.0, 25.0,
                if (creditScore >= .8) "POSITIVE" else if (creditScore >= .6) "NEUTRAL" else "NEGATIVE",
                "Puntaje registrado: ${input.creditScorePercent.coerceIn(0,100)}%"),
            factor("PURCHASE_COMPLETION", "Compras completadas", purchaseBayes * 100.0, 10.0,
                if (purchaseBayes >= .8) "POSITIVE" else if (purchaseBayes >= .6) "NEUTRAL" else "NEGATIVE",
                "$completed compras completadas de $purchaseObservations"),
            factor("OVERDUE_EXPOSURE", "Exposición vencida", (1.0 - overdueRatio) * 100.0, 10.0,
                if (overdueRatio <= .05) "POSITIVE" else if (overdueRatio <= .20) "NEUTRAL" else "NEGATIVE",
                "${percent(overdueRatio * 100.0)}% del saldo por cobrar está vencido"),
            factor("ACTIVITY", "Antigüedad y actividad", activityStability * 100.0, 5.0,
                if (activityStability >= .55) "POSITIVE" else "NEUTRAL",
                "${input.activityDays.coerceAtLeast(0)} días de historial observable")
        )

        return PredictiveSubjectResult(
            paymentSuccessPercent = percentMoney(payment),
            purchaseSuccessPercent = percentMoney(purchase),
            latePaymentProbabilityPercent = percentMoney(lateProbability),
            confidencePercent = percentMoney(confidence),
            riskLevel = risk,
            recommendedCreditLimitUsd = recommended,
            predictedNextPurchaseUsd = predictedNext,
            factors = factors
        )
    }

    fun budget(input: PredictiveBudgetInput): PredictiveBudgetResult {
        val disbursed = input.disbursedLoansUsd.coerceAtLeast(BigDecimal.ZERO)
        val recovered = input.recoveredLoansUsd.coerceAtLeast(BigDecimal.ZERO)
        val outstanding = input.outstandingLoansUsd.coerceAtLeast(BigDecimal.ZERO)
        val overdue = input.overdueLoansUsd.coerceAtLeast(BigDecimal.ZERO)
        val collectionRate = if (disbursed.signum() == 0) 0.85 else
            recovered.divide(disbursed, 8, RoundingMode.HALF_EVEN).toDouble().coerceIn(0.10, 1.0)
        val overdueRate = if (outstanding.signum() == 0) 0.0 else
            overdue.divide(outstanding, 8, RoundingMode.HALF_EVEN).toDouble().coerceIn(0.0, 1.0)
        val adjustedCollectionRate = (collectionRate * (1.0 - overdueRate * .55)).coerceIn(.08, .98)
        val defaultRisk = (overdueRate * .70 + (1.0 - adjustedCollectionRate) * .30).coerceIn(0.0, 1.0)
        val confidence = (40.0 + 55.0 * (1.0 - exp(-input.observedInstallments.coerceAtLeast(0) / 20.0))).coerceIn(40.0, 95.0)

        val monthlyExpense = input.recentOperatingExpenses90DaysUsd.divide(BigDecimal("3"), 2, RoundingMode.HALF_EVEN)
        val monthlyIncome = input.recentBankIncome90DaysUsd.divide(BigDecimal("3"), 2, RoundingMode.HALF_EVEN)
        val horizons = listOf(30, 60, 90)
        val forecasts = horizons.map { days ->
            val fraction = BigDecimal(days).divide(BigDecimal("90"), 8, RoundingMode.HALF_EVEN)
            val collections = outstanding.multiply(BigDecimal.valueOf(adjustedCollectionRate), MoneyMath.CONTEXT)
                .multiply(fraction, MoneyMath.CONTEXT).min(outstanding).setScale(2, RoundingMode.HALF_EVEN)
            val expenses = monthlyExpense.multiply(BigDecimal(days).divide(BigDecimal("30"), 8, RoundingMode.HALF_EVEN), MoneyMath.CONTEXT)
                .setScale(2, RoundingMode.HALF_EVEN)
            val income = monthlyIncome.multiply(BigDecimal(days).divide(BigDecimal("30"), 8, RoundingMode.HALF_EVEN), MoneyMath.CONTEXT)
                .setScale(2, RoundingMode.HALF_EVEN)
            val expectedOverdue = outstanding.multiply(BigDecimal.valueOf(defaultRisk), MoneyMath.CONTEXT)
                .multiply(fraction, MoneyMath.CONTEXT).setScale(2, RoundingMode.HALF_EVEN)
            val projected = input.consolidatedAvailableUsd
                .subtract(input.reservedUsd, MoneyMath.CONTEXT)
                .add(collections, MoneyMath.CONTEXT)
                .add(income, MoneyMath.CONTEXT)
                .subtract(expenses, MoneyMath.CONTEXT)
                .coerceAtLeast(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_EVEN)
            PredictiveBudgetPoint(days, collections, expenses, income, expectedOverdue, projected,
                percentMoney((confidence - (days / 90.0) * 8.0).coerceAtLeast(30.0)))
        }

        val available = input.consolidatedAvailableUsd.coerceAtLeast(BigDecimal.ZERO)
        val committed = input.reservedUsd.add(outstanding, MoneyMath.CONTEXT)
        val coverage = if (committed.signum() == 0) 2.0 else available.divide(committed, 8, RoundingMode.HALF_EVEN).toDouble()
        val risk = when {
            coverage >= 1.5 && defaultRisk < .10 -> "VERY_LOW"
            coverage >= 1.0 && defaultRisk < .20 -> "LOW"
            coverage >= .65 && defaultRisk < .35 -> "MEDIUM"
            coverage >= .35 -> "HIGH"
            else -> "CRITICAL"
        }
        val alerts = buildList {
            if (overdueRate >= .20) add("La cartera vencida supera el 20% del saldo pendiente.")
            if (coverage < 1.0) add("La liquidez disponible no cubre completamente fondos reservados y préstamos pendientes.")
            if (monthlyExpense.signum() > 0 && monthlyExpense > monthlyIncome) add("El gasto operativo mensual estimado supera los abonos bancarios recientes.")
            if (input.observedInstallments < 10) add("La confianza es limitada porque existen menos de 10 cuotas observadas.")
            if (isEmpty()) add("No se detectan alertas financieras críticas en el horizonte analizado.")
        }
        return PredictiveBudgetResult(
            collectionProbabilityPercent = percentMoney(adjustedCollectionRate * 100.0),
            defaultRiskPercent = percentMoney(defaultRisk * 100.0),
            liquidityRiskLevel = risk,
            confidencePercent = percentMoney(confidence),
            forecasts = forecasts,
            alerts = alerts
        )
    }

    private fun logisticNormalize(value: Double): Double {
        val centered = (value.coerceIn(0.0, 1.0) - .5) * 4.0
        return (1.0 / (1.0 + exp(-centered)) * 100.0).coerceIn(1.0, 99.0)
    }

    private fun ratio(successes: Int, total: Int): Double =
        if (total <= 0) 0.0 else successes.toDouble() / total.toDouble()

    private fun factor(code: String, label: String, value: Double, weight: Double, impact: String, explanation: String) =
        PredictiveFactorResult(code, label, percentMoney(value), percentMoney(weight), impact, explanation)

    private fun percentMoney(value: Double): BigDecimal =
        BigDecimal.valueOf(value.coerceIn(0.0, 100.0)).setScale(2, RoundingMode.HALF_EVEN)

    private fun percent(value: Double): String = String.format(java.util.Locale.US, "%.2f", value.coerceAtLeast(0.0))

    private fun BigDecimal.coerceAtLeast(minimum: BigDecimal): BigDecimal = if (this < minimum) minimum else this
}
