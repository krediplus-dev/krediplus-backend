package com.impulsosocial.server.service

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/** Motor determinista de creación, sellado y verificación de facturas. */
data class InvoiceIntegrityInput(
    val orderId: Long,
    val invoiceNumber: String,
    val financingType: String,
    val orderTotalBs: BigDecimal,
    val lineTotalBs: BigDecimal,
    val paymentTotalBs: BigDecimal,
    val paymentStatus: String = "VERIFIED",
    val creditPrincipalBs: BigDecimal,
    val lineCount: Int,
    val duplicateInvoiceCount: Int
)

data class InvoiceIntegrityResult(
    val status: String,
    val integrityScore: Int,
    val calculatedTotalBs: BigDecimal,
    val differenceBs: BigDecimal,
    val documentHash: String,
    val warnings: List<String>
)

object InvoiceIntegrityEngine {
    const val ALGORITHM_VERSION = "INVOICE-6.0.0"
    private val TOLERANCE = BigDecimal("0.01")

    fun evaluate(input: InvoiceIntegrityInput): InvoiceIntegrityResult {
        val orderTotal = money(input.orderTotalBs)
        val lineTotal = money(input.lineTotalBs)
        val expectedSettlement = if (input.financingType.equals("CREDIMPULSO", true))
            money(input.creditPrincipalBs) else money(input.paymentTotalBs)
        val lineDifference = orderTotal.subtract(lineTotal).abs().setScale(2, RoundingMode.HALF_EVEN)
        val settlementDifference = orderTotal.subtract(expectedSettlement).abs().setScale(2, RoundingMode.HALF_EVEN)
        val warnings = buildList {
            if (!input.invoiceNumber.matches(Regex("^IS-[0-9]{8}-[0-9]{6,}$"))) add("Nomenclatura de factura no válida.")
            if (input.duplicateInvoiceCount > 1) add("Número de factura duplicado.")
            if (input.lineCount <= 0) add("La factura no contiene productos ni combos.")
            if (orderTotal.signum() <= 0) add("El total de la factura debe ser mayor que cero.")
            if (lineDifference > TOLERANCE) add("El total de líneas no coincide con el total del pedido.")
            if (settlementDifference > TOLERANCE) add(
                if (input.financingType.equals("CREDIMPULSO", true)) "El principal del crédito no coincide con la factura."
                else "El pago reportado no coincide con el total de la factura."
            )
            if (!input.financingType.equals("CREDIMPULSO", true)) {
                when (input.paymentStatus.uppercase()) {
                    "REPORTED", "PENDING" -> add("El pago todavía requiere verificación administrativa.")
                    "REJECTED" -> add("El pago asociado fue rechazado por el administrador.")
                    "VERIFIED" -> Unit
                    else -> add("El estado del pago no es reconocible.")
                }
            }
        }
        var score = 100
        warnings.forEach { warning ->
            score -= when {
                warning.contains("duplicado") -> 45
                warning.contains("no contiene") -> 35
                warning.contains("total de líneas") -> 30
                warning.contains("no coincide") -> 25
                warning.contains("rechazado") -> 45
                warning.contains("requiere verificación") -> 15
                warning.contains("estado del pago") -> 20
                warning.contains("nomenclatura") -> 10
                else -> 15
            }
        }
        score = score.coerceIn(0, 100)
        val status = when {
            warnings.isEmpty() -> "VERIFIED"
            score >= 70 -> "REVIEW_REQUIRED"
            else -> "REJECTED"
        }
        val canonical = listOf(
            ALGORITHM_VERSION, input.orderId.toString(), input.invoiceNumber,
            input.financingType.uppercase(), orderTotal.toPlainString(), lineTotal.toPlainString(),
            expectedSettlement.toPlainString(), input.paymentStatus.uppercase(), input.lineCount.toString()
        ).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return InvoiceIntegrityResult(status, score, lineTotal, lineDifference.max(settlementDifference), hash, warnings)
    }

    private fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_EVEN)
}
