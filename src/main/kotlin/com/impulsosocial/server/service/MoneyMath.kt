package com.impulsosocial.server.service

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Núcleo matemático financiero de Kredi+.
 *
 * Todos los cálculos se hacen con BigDecimal; Double se usa únicamente en los límites JSON
 * para conservar compatibilidad con las versiones anteriores de la aplicación.
 */
object MoneyMath {
    val CONTEXT: MathContext = MathContext(24, RoundingMode.HALF_EVEN)
    const val USD_SCALE: Int = 2
    const val VES_SCALE: Int = 2
    const val RATE_SCALE: Int = 6
    const val QUANTITY_SCALE: Int = 3

    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(USD_SCALE)

    fun decimal(value: Double, field: String): BigDecimal {
        require(value.isFinite()) { "$field no es un número finito." }
        return BigDecimal.valueOf(value)
    }

    fun usd(value: Double, field: String = "Monto USD"): BigDecimal =
        decimal(value, field).setScale(USD_SCALE, RoundingMode.HALF_EVEN)

    fun ves(value: Double, field: String = "Monto Bs"): BigDecimal =
        decimal(value, field).setScale(VES_SCALE, RoundingMode.HALF_EVEN)

    fun rate(value: Double, field: String = "Tasa BCV"): BigDecimal =
        decimal(value, field).setScale(RATE_SCALE, RoundingMode.HALF_EVEN)

    fun positive(value: BigDecimal, field: String): BigDecimal {
        require(value.signum() > 0) { "$field debe ser mayor que cero." }
        return value
    }

    fun nonNegative(value: BigDecimal, field: String): BigDecimal {
        require(value.signum() >= 0) { "$field no puede ser negativo." }
        return value
    }


    fun addUsd(left: Double, right: Double): BigDecimal =
        usd(left).add(usd(right), CONTEXT).setScale(USD_SCALE, RoundingMode.HALF_EVEN)

    fun subtractUsd(left: Double, right: Double): BigDecimal =
        usd(left).subtract(usd(right), CONTEXT).setScale(USD_SCALE, RoundingMode.HALF_EVEN)

    fun usdToVes(amountUsd: Double, bcvRate: Double): BigDecimal =
        usdToVes(usd(amountUsd), rate(bcvRate))

    /**
     * Conversión tolerante para pantallas de consulta. Si la API BCV está temporalmente
     * indisponible, conserva el saldo USD y devuelve Bs 0,00 sin derribar la sesión.
     * Las operaciones que mueven dinero siguen exigiendo una tasa positiva.
     */
    fun usdToVesOrZero(amountUsd: Double, bcvRate: Double): BigDecimal =
        if (!bcvRate.isFinite() || bcvRate <= 0.0) BigDecimal.ZERO.setScale(VES_SCALE)
        else usdToVes(amountUsd, bcvRate)

    fun greaterThanUsd(left: Double, right: Double): Boolean = usd(left) > usd(right)

    fun usdToVes(amountUsd: BigDecimal, bcvRate: BigDecimal): BigDecimal =
        amountUsd.multiply(positive(bcvRate, "Tasa BCV"), CONTEXT)
            .setScale(VES_SCALE, RoundingMode.HALF_EVEN)

    fun vesToUsd(amountVes: BigDecimal, bcvRate: BigDecimal): BigDecimal =
        amountVes.divide(positive(bcvRate, "Tasa BCV"), USD_SCALE, RoundingMode.HALF_EVEN)

    fun multiplyMoney(unitPrice: BigDecimal, quantity: Int): BigDecimal {
        require(quantity > 0) { "La cantidad debe ser mayor que cero." }
        return unitPrice.multiply(BigDecimal.valueOf(quantity.toLong()), CONTEXT)
            .setScale(VES_SCALE, RoundingMode.HALF_EVEN)
    }

    fun multiplyWeight(unitPrice: BigDecimal, kilograms: BigDecimal): BigDecimal =
        unitPrice.multiply(positive(kilograms, "Peso"), CONTEXT)
            .setScale(VES_SCALE, RoundingMode.HALF_EVEN)

    fun sum(values: Iterable<BigDecimal>, scale: Int = VES_SCALE): BigDecimal =
        values.fold(BigDecimal.ZERO) { total, value -> total.add(value, CONTEXT) }
            .setScale(scale, RoundingMode.HALF_EVEN)

    fun splitExact(total: BigDecimal, parts: Int, scale: Int = USD_SCALE): List<BigDecimal> {
        require(parts > 0) { "La cantidad de partes debe ser mayor que cero." }
        val normalized = total.setScale(scale, RoundingMode.HALF_EVEN)
        val regular = normalized.divide(BigDecimal.valueOf(parts.toLong()), scale, RoundingMode.HALF_EVEN)
        val values = MutableList(parts) { regular }
        val assigned = regular.multiply(BigDecimal.valueOf(parts.toLong()), CONTEXT)
        values[parts - 1] = regular.add(normalized.subtract(assigned, CONTEXT), CONTEXT)
            .setScale(scale, RoundingMode.HALF_EVEN)
        return values
    }

    fun nearlyEqual(left: BigDecimal, right: BigDecimal, tolerance: BigDecimal = BigDecimal("0.01")): Boolean =
        left.subtract(right, CONTEXT).abs() <= tolerance
}
