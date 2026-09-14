package com.impulsosocial.server.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Motor matemático del sistema presupuestario de Kredi+.
 *
 * Regla principal: ninguna suma monetaria se calcula con Double. Los valores se
 * normalizan a centavos antes de entrar al motor y Double se usa solo al exponer JSON.
 */
data class BudgetMathInput(
    val bankBudgetUsd: BigDecimal,
    val centralAvailableUsd: BigDecimal,
    val administratorsAvailableUsd: BigDecimal,
    val investedUsd: BigDecimal,
    val operatingExpensesUsd: BigDecimal,
    val administrativeExpensesUsd: BigDecimal = BigDecimal.ZERO,
    val loansDisbursedUsd: BigDecimal,
    val loansRecoveredUsd: BigDecimal,
    val loansOutstandingUsd: BigDecimal,
    val overdueLoansUsd: BigDecimal,
    val reservedUsd: BigDecimal,
    val expectedCollections30DaysUsd: BigDecimal,
    val inventoryCostsUsd: BigDecimal = BigDecimal.ZERO,
    val commercialExpensesUsd: BigDecimal = BigDecimal.ZERO,
    val financialExpensesUsd: BigDecimal = BigDecimal.ZERO,
    val extraordinaryExpensesUsd: BigDecimal = BigDecimal.ZERO
)

data class BudgetMathResult(
    val consolidatedAvailableUsd: BigDecimal,
    val totalExpensesUsd: BigDecimal,
    val totalCommittedUsd: BigDecimal,
    val projectedAvailable30DaysUsd: BigDecimal,
    val expectedCashUsd: BigDecimal,
    val integrityDifferenceUsd: BigDecimal,
    val executionPercent: BigDecimal,
    val recoveryPercent: BigDecimal,
    val liquidityCoveragePercent: BigDecimal,
    val integrityStatus: String
)

object BudgetMath {
    private val HUNDRED = BigDecimal("100")
    private val ZERO_MONEY = BigDecimal.ZERO.setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)

    fun calculate(input: BudgetMathInput): BudgetMathResult {
        val bankBudget = money(input.bankBudgetUsd)
        val central = money(input.centralAvailableUsd)
        val administrators = money(input.administratorsAvailableUsd)
        val operatingExpenses = money(input.operatingExpensesUsd)
        val administrativeExpenses = money(input.administrativeExpensesUsd)
        val inventoryCosts = money(input.inventoryCostsUsd)
        val commercialExpenses = money(input.commercialExpensesUsd)
        val financialExpenses = money(input.financialExpensesUsd)
        val extraordinaryExpenses = money(input.extraordinaryExpensesUsd)
        val loansDisbursed = money(input.loansDisbursedUsd)
        val loansRecovered = money(input.loansRecoveredUsd)
        val loansOutstanding = money(input.loansOutstandingUsd)
        val reserved = money(input.reservedUsd)
        val expectedCollections = money(input.expectedCollections30DaysUsd)

        val consolidatedAvailable = central.add(administrators, MoneyMath.CONTEXT).normalizedMoney()
        // Gasto total representa salida real del presupuesto. Las asignaciones a
        // administradores son inversión interna y no se suman otra vez para evitar doble conteo.
        val totalExpenses = operatingExpenses
            .add(administrativeExpenses, MoneyMath.CONTEXT)
            .add(inventoryCosts, MoneyMath.CONTEXT)
            .add(commercialExpenses, MoneyMath.CONTEXT)
            .add(financialExpenses, MoneyMath.CONTEXT)
            .add(extraordinaryExpenses, MoneyMath.CONTEXT)
            .add(loansDisbursed, MoneyMath.CONTEXT)
            .normalizedMoney()
        val totalCommitted = loansOutstanding.add(reserved, MoneyMath.CONTEXT).normalizedMoney()
        val projectedAvailable = consolidatedAvailable
            .subtract(reserved, MoneyMath.CONTEXT)
            .add(expectedCollections, MoneyMath.CONTEXT)
            .coerceAtLeast(ZERO_MONEY)
            .normalizedMoney()

        // Efectivo esperado después de gastos externos, desembolsos y recuperaciones.
        val expectedCash = bankBudget
            .subtract(operatingExpenses, MoneyMath.CONTEXT)
            .subtract(administrativeExpenses, MoneyMath.CONTEXT)
            .subtract(inventoryCosts, MoneyMath.CONTEXT)
            .subtract(commercialExpenses, MoneyMath.CONTEXT)
            .subtract(financialExpenses, MoneyMath.CONTEXT)
            .subtract(extraordinaryExpenses, MoneyMath.CONTEXT)
            .subtract(loansDisbursed, MoneyMath.CONTEXT)
            .add(loansRecovered, MoneyMath.CONTEXT)
            .normalizedMoney()
        val difference = consolidatedAvailable.subtract(expectedCash, MoneyMath.CONTEXT).normalizedMoney()

        val execution = percent(totalExpenses, bankBudget)
        val recovery = percent(loansRecovered, loansDisbursed)
        val coverageBase = loansOutstanding.add(reserved, MoneyMath.CONTEXT).normalizedMoney()
        val coverage = if (coverageBase.signum() == 0) {
            if (consolidatedAvailable.signum() >= 0) HUNDRED.setScale(2) else ZERO_MONEY
        } else percent(consolidatedAvailable, coverageBase)

        val integrityStatus = when {
            difference.abs() <= BigDecimal("0.01") -> "BALANCED"
            difference.abs() <= BigDecimal("1.00") -> "ROUNDING_REVIEW"
            else -> "REVIEW_REQUIRED"
        }

        return BudgetMathResult(
            consolidatedAvailableUsd = consolidatedAvailable,
            totalExpensesUsd = totalExpenses,
            totalCommittedUsd = totalCommitted,
            projectedAvailable30DaysUsd = projectedAvailable,
            expectedCashUsd = expectedCash,
            integrityDifferenceUsd = difference,
            executionPercent = execution,
            recoveryPercent = recovery,
            liquidityCoveragePercent = coverage,
            integrityStatus = integrityStatus
        )
    }

    private fun percent(numerator: BigDecimal, denominator: BigDecimal): BigDecimal {
        if (denominator.signum() <= 0) return ZERO_MONEY
        return numerator
            .multiply(HUNDRED, MoneyMath.CONTEXT)
            .divide(denominator, 2, RoundingMode.HALF_EVEN)
    }

    private fun money(value: BigDecimal): BigDecimal =
        value.setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)

    private fun BigDecimal.normalizedMoney(): BigDecimal = money(this)

    private fun BigDecimal.coerceAtLeast(minimum: BigDecimal): BigDecimal =
        if (this < minimum) minimum else this
}
