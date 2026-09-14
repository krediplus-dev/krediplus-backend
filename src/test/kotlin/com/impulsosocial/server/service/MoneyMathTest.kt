package com.impulsosocial.server.service

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyMathTest {
    @Test
    fun `convierte USD a bolivares con redondeo bancario`() {
        val result = MoneyMath.usdToVes(BigDecimal("12.35"), BigDecimal("123.456789"))
        assertEquals(BigDecimal("1524.69"), result)
    }

    @Test
    fun `divide cuotas sin perder ni crear centavos`() {
        val total = BigDecimal("10.00")
        val parts = MoneyMath.splitExact(total, 3)
        assertEquals(listOf(BigDecimal("3.33"), BigDecimal("3.33"), BigDecimal("3.34")), parts)
        assertEquals(total, parts.reduce(BigDecimal::add))
    }

    @Test
    fun `ida y vuelta BCV conserva el monto monetario`() {
        val usd = BigDecimal("48.27")
        val rate = BigDecimal("142.831245")
        val ves = MoneyMath.usdToVes(usd, rate)
        val restored = MoneyMath.vesToUsd(ves, rate)
        assertTrue(MoneyMath.nearlyEqual(usd, restored))
    }
    @Test
    fun `suma y resta montos decimales sin error binario`() {
        assertEquals(BigDecimal("0.30"), MoneyMath.addUsd(0.10, 0.20))
        assertEquals(BigDecimal("6.67"), MoneyMath.subtractUsd(10.00, 3.33))
    }

    @Test
    fun `rechaza valores monetarios no finitos`() {
        val failure = runCatching { MoneyMath.usd(Double.NaN) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `consulta de saldo no falla cuando BCV esta temporalmente indisponible`() {
        assertEquals(BigDecimal("0.00"), MoneyMath.usdToVesOrZero(25.00, 0.0))
        assertEquals(BigDecimal("0.00"), MoneyMath.usdToVesOrZero(25.00, Double.NaN))
    }

}
