package com.impulsosocial.server.service

import kotlin.math.abs

/**
 * Motor explicable de detección de anomalías para reportes de pago.
 *
 * No consulta directamente una cuenta bancaria y, por diseño, nunca aprueba un pago.
 * Su función es priorizar revisiones, explicar señales de riesgo y recomendar controles
 * al administrador antes de que confirme el movimiento en la banca.
 */
data class PaymentFraudInput(
    val expectedAmountBs: Double,
    val reportedAmountBs: Double,
    val referenceNumber: String,
    val bankExists: Boolean,
    val proofPresent: Boolean,
    val proofDuplicateCount: Int,
    val proofVisualNearDuplicateCount: Int,
    val proofImageReadable: Boolean,
    val sameReferenceCount: Int,
    val sameReferenceOtherUsersCount: Int,
    val paidFromDifferentPhone: Boolean,
    val originPhoneMatchesProfile: Boolean,
    val priorRejectedReports: Int,
    val reportsLast24Hours: Int
)

data class PaymentFraudResult(
    val riskScore: Int,
    val riskLevel: String,
    val confidencePercent: Int,
    val recommendation: String,
    val reasons: List<String>,
    val suggestions: List<String>,
    val algorithmVersion: String = "PAYMENT-RISK-6.2.0"
)

object PaymentFraudEngine {
    fun evaluate(input: PaymentFraudInput): PaymentFraudResult {
        var score = 0
        var evidence = 0
        val reasons = mutableListOf<String>()
        val suggestions = linkedSetOf<String>()

        val expected = input.expectedAmountBs.coerceAtLeast(0.0)
        val reported = input.reportedAmountBs.coerceAtLeast(0.0)
        val amountDifference = abs(reported - expected)
        val differencePercent = if (expected > 0.0) (amountDifference / expected) * 100.0 else 100.0

        evidence += 2
        when {
            expected <= 0.0 || reported <= 0.0 -> {
                score += 35
                reasons += "El monto esperado o reportado no es válido."
                suggestions += "No aprobar hasta confirmar el monto exacto de la obligación."
            }
            differencePercent > 10.0 -> {
                score += 32
                reasons += "El monto reportado difiere más de 10 % del monto esperado."
                suggestions += "Comparar el monto de la referencia bancaria con la factura o cuota."
            }
            differencePercent > 2.0 -> {
                score += 18
                reasons += "El monto reportado no coincide exactamente con el monto esperado."
                suggestions += "Solicitar al usuario una explicación por la diferencia de monto."
            }
            amountDifference >= 0.01 -> {
                score += 6
                reasons += "Existe una diferencia menor por redondeo o captura."
            }
        }

        evidence += 2
        if (!input.bankExists) {
            score += 25
            reasons += "El banco indicado no pertenece al directorio activo."
            suggestions += "Confirmar el banco emisor antes de revisar la referencia."
        }

        val digitsOnly = input.referenceNumber.all(Char::isDigit)
        when {
            !digitsOnly -> {
                score += 28
                reasons += "La referencia contiene caracteres distintos de números."
                suggestions += "Solicitar la referencia bancaria completa usando solo números."
            }
            input.referenceNumber.length < 6 -> {
                score += 14
                reasons += "La referencia es inusualmente corta."
                suggestions += "Comparar la referencia completa en el movimiento bancario."
            }
            input.referenceNumber.length > 30 -> {
                score += 10
                reasons += "La referencia tiene una longitud atípica."
            }
        }
        evidence += 1

        if (input.sameReferenceOtherUsersCount > 0) {
            score += 45
            reasons += "La misma referencia fue utilizada por otra cuenta."
            suggestions += "No aprobar automáticamente; revisar titulares, fecha, monto y banco."
            suggestions += "Conservar el comprobante y escalar el caso por posible reutilización."
        } else if (input.sameReferenceCount > 0) {
            score += 20
            reasons += "La referencia ya aparece en otro reporte o pago."
            suggestions += "Verificar que no se esté reportando dos veces el mismo movimiento."
        }
        evidence += 2

        if (!input.proofPresent) {
            score += 24
            reasons += "No se adjuntó comprobante."
            suggestions += "Solicitar una captura legible antes de decidir."
        } else {
            if (!input.proofImageReadable) {
                score += 14
                reasons += "La imagen del comprobante no pudo analizarse con suficiente claridad."
                suggestions += "Abrir el archivo y solicitar otra captura si los datos no son legibles."
            }
            when {
                input.proofDuplicateCount > 0 -> {
                    score += 42
                    reasons += "El mismo archivo de comprobante ya fue utilizado anteriormente."
                    suggestions += "Comparar visualmente el comprobante y confirmar si fue reutilizado."
                    suggestions += "No aprobar sin localizar el movimiento real en la banca."
                }
                input.proofVisualNearDuplicateCount > 0 -> {
                    score += 38
                    reasons += "La imagen se parece de forma significativa a un comprobante usado anteriormente."
                    suggestions += "Revisar si el comprobante fue redimensionado, recortado o vuelto a guardar."
                    suggestions += "Comparar referencia, monto, fecha y titular antes de aprobar."
                }
            }
        }
        evidence += 3

        if (input.paidFromDifferentPhone && !input.originPhoneMatchesProfile) {
            score += 12
            reasons += "El pago fue reportado desde un teléfono distinto al registrado."
            suggestions += "Confirmar la relación entre el pagador y el titular de la cuenta."
        } else if (!input.originPhoneMatchesProfile) {
            score += 7
            reasons += "El teléfono de origen no coincide con el perfil del usuario."
        }
        evidence += 1

        when {
            input.priorRejectedReports >= 3 -> {
                score += 24
                reasons += "La cuenta tiene tres o más reportes rechazados anteriormente."
                suggestions += "Revisar el historial completo antes de aprobar."
            }
            input.priorRejectedReports >= 1 -> {
                score += 10
                reasons += "La cuenta tiene reportes de pago rechazados anteriormente."
            }
        }
        evidence += 1

        when {
            input.reportsLast24Hours >= 6 -> {
                score += 18
                reasons += "Se detectó una frecuencia inusual de reportes en 24 horas."
                suggestions += "Comprobar si existen reportes duplicados o automatizados."
            }
            input.reportsLast24Hours >= 3 -> {
                score += 8
                reasons += "La cuenta ha enviado varios reportes durante las últimas 24 horas."
            }
        }
        evidence += 1

        val normalizedScore = score.coerceIn(0, 100)
        val riskLevel = when {
            normalizedScore >= 80 -> "CRITICAL"
            normalizedScore >= 60 -> "HIGH"
            normalizedScore >= 35 -> "MEDIUM"
            normalizedScore >= 15 -> "LOW"
            else -> "VERY_LOW"
        }
        val confidence = (45 + evidence * 4 + minOf(input.sameReferenceCount, 3) * 3 +
            minOf(input.priorRejectedReports, 3) * 2).coerceIn(45, 94)

        val recommendation = when (riskLevel) {
            "CRITICAL" -> "Posible reutilización o inconsistencia grave. No aprobar sin confirmar el movimiento en la banca y validar al pagador."
            "HIGH" -> "Revisión reforzada obligatoria. Confirmar referencia, monto, fecha, banco y titular antes de aprobar."
            "MEDIUM" -> "Revisar manualmente el comprobante y localizar la referencia en la banca antes de decidir."
            "LOW" -> "Las señales son leves, pero el administrador debe confirmar el movimiento bancario."
            else -> "No se detectaron anomalías relevantes. Aun así, confirma el movimiento en la banca antes de aprobar."
        }

        if (suggestions.isEmpty()) {
            suggestions += "Buscar la referencia en la banca y comparar monto, fecha, titular y teléfono."
        }
        suggestions += "Registrar una nota de revisión antes de aprobar o rechazar."

        return PaymentFraudResult(
            riskScore = normalizedScore,
            riskLevel = riskLevel,
            confidencePercent = confidence,
            recommendation = recommendation,
            reasons = reasons.ifEmpty { listOf("No se detectaron anomalías internas relevantes.") },
            suggestions = suggestions.toList()
        )
    }
}
