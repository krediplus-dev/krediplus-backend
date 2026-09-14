package com.impulsosocial.server.service

import java.math.BigDecimal
import java.math.RoundingMode

data class PaymentAmountComparison(
    val differenceBs: BigDecimal,
    val differencePercent: BigDecimal,
    val materialDifference: Boolean
)

/** Reglas deterministas para reportes y decisiones de comprobantes de pago. */
object PaymentReviewPolicy {
    private val allowedMethods = setOf("MOBILE_PAYMENT", "BANK_TRANSFER")

    fun normalizeReference(raw: String): String {
        val reference = raw.filterNot(Char::isWhitespace)
        if (reference.length !in 4..30 || !reference.all(Char::isDigit)) {
            throw AppException("Ingresa la referencia completa usando entre 4 y 30 números.")
        }
        return reference
    }

    fun normalizeMethod(raw: String): String = raw.trim().uppercase().also {
        if (it !in allowedMethods) throw AppException("Selecciona un método de pago válido.")
    }

    fun compare(reported: BigDecimal, expected: BigDecimal): PaymentAmountComparison {
        if (reported.signum() <= 0 || expected.signum() <= 0) {
            throw AppException("Los montos del pago deben ser mayores que cero.")
        }
        val difference = reported.subtract(expected).abs().setScale(2, RoundingMode.HALF_EVEN)
        val percent = difference.multiply(BigDecimal("100"))
            .divide(expected, 2, RoundingMode.HALF_EVEN)
        return PaymentAmountComparison(
            differenceBs = difference,
            differencePercent = percent,
            materialDifference = difference > BigDecimal("1.00") && percent > BigDecimal("1.00")
        )
    }

    fun validateDecision(
        currentStatus: String,
        approved: Boolean,
        bankConfirmed: Boolean,
        riskScore: Int,
        reportedAmountBs: BigDecimal,
        expectedAmountBs: BigDecimal,
        notes: String?
    ): PaymentAmountComparison {
        if (currentStatus != "REPORTED") throw AppException("Este reporte ya fue revisado.")
        val normalizedNotes = notes?.trim().orEmpty()
        if (approved && !bankConfirmed) {
            throw AppException("Para aprobar debes confirmar que localizaste el movimiento en la banca.")
        }
        if (!approved && normalizedNotes.isBlank()) {
            throw AppException("Explica brevemente por qué rechazaste el reporte.")
        }
        if (approved && riskScore >= 60 && normalizedNotes.isBlank()) {
            throw AppException("Este reporte tiene riesgo alto. Registra cómo confirmaste el pago antes de aprobar.")
        }
        val comparison = compare(reportedAmountBs, expectedAmountBs)
        if (approved && comparison.materialDifference && normalizedNotes.isBlank()) {
            throw AppException(
                "El monto reportado difiere del monto esperado. Registra la verificación realizada antes de aprobar."
            )
        }
        return comparison
    }
}
