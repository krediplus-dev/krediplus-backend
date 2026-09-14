package com.impulsosocial.server.service

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvoiceIntegrityEngineTest {
    @Test
    fun `factura directa exacta queda verificada`() {
        val result = InvoiceIntegrityEngine.evaluate(
            InvoiceIntegrityInput(
                orderId = 123,
                invoiceNumber = "IS-20260728-000123",
                financingType = "DIRECT_PAYMENT",
                orderTotalBs = BigDecimal("1250.50"),
                lineTotalBs = BigDecimal("1250.50"),
                paymentTotalBs = BigDecimal("1250.50"),
                paymentStatus = "VERIFIED",
                creditPrincipalBs = BigDecimal.ZERO,
                lineCount = 3,
                duplicateInvoiceCount = 1
            )
        )
        assertEquals("VERIFIED", result.status)
        assertEquals(100, result.integrityScore)
        assertTrue(result.warnings.isEmpty())
        assertEquals(64, result.documentHash.length)
    }

    @Test
    fun `factura con totales inconsistentes exige revision o rechazo`() {
        val result = InvoiceIntegrityEngine.evaluate(
            InvoiceIntegrityInput(
                orderId = 124,
                invoiceNumber = "IS-20260728-000124",
                financingType = "DIRECT_PAYMENT",
                orderTotalBs = BigDecimal("1000.00"),
                lineTotalBs = BigDecimal("850.00"),
                paymentTotalBs = BigDecimal("700.00"),
                paymentStatus = "VERIFIED",
                creditPrincipalBs = BigDecimal.ZERO,
                lineCount = 2,
                duplicateInvoiceCount = 1
            )
        )
        assertTrue(result.status in setOf("REVIEW_REQUIRED", "REJECTED"))
        assertTrue(result.integrityScore < 100)
        assertTrue(result.differenceBs == BigDecimal("300.00"))
    }

    @Test
    fun `pago reportado queda pendiente de revision administrativa`() {
        val result = InvoiceIntegrityEngine.evaluate(
            InvoiceIntegrityInput(
                orderId = 126,
                invoiceNumber = "IS-20260728-000126",
                financingType = "DIRECT_PAYMENT",
                orderTotalBs = BigDecimal("300.00"),
                lineTotalBs = BigDecimal("300.00"),
                paymentTotalBs = BigDecimal("300.00"),
                paymentStatus = "REPORTED",
                creditPrincipalBs = BigDecimal.ZERO,
                lineCount = 1,
                duplicateInvoiceCount = 1
            )
        )
        assertEquals("REVIEW_REQUIRED", result.status)
        assertTrue(result.warnings.any { it.contains("verificación administrativa") })
    }

    @Test
    fun `numero duplicado reduce severamente la integridad`() {
        val result = InvoiceIntegrityEngine.evaluate(
            InvoiceIntegrityInput(
                orderId = 125,
                invoiceNumber = "IS-20260728-000125",
                financingType = "CREDIMPULSO",
                orderTotalBs = BigDecimal("500.00"),
                lineTotalBs = BigDecimal("500.00"),
                paymentTotalBs = BigDecimal.ZERO,
                paymentStatus = "NOT_APPLICABLE",
                creditPrincipalBs = BigDecimal("500.00"),
                lineCount = 1,
                duplicateInvoiceCount = 2
            )
        )
        assertTrue(result.warnings.any { it.contains("duplicado") })
        assertEquals(55, result.integrityScore)
        assertEquals("REJECTED", result.status)
    }
}
