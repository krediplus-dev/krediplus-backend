package com.impulsosocial.server.service

import java.util.Locale

data class NotificationMessage(
    val title: String,
    val body: String,
    val type: String,
    val data: Map<String, String>
)

object WalletNotificationFactory {
    const val RECEIVED_TYPE = "CREDIMPULSO_WALLET_TRANSFER"
    const val ACCOUNTANT_BUDGET_TYPE = "ACCOUNTANT_BUDGET_ASSIGNED"
    const val CREDIT_APPROVED_TYPE = "CREDIT_REQUEST_APPROVED"

    fun received(amountUsd: Double, availableBalanceUsd: Double, reference: String): NotificationMessage =
        walletCreditMessage(
            title = "Saldo recibido en tu cartera",
            prefix = "Recibiste",
            amountUsd = amountUsd,
            availableBalanceUsd = availableBalanceUsd,
            reference = reference,
            type = RECEIVED_TYPE,
            destination = "CREDIT_WALLET"
        )

    fun accountantBudgetAssigned(
        amountUsd: Double,
        availableBalanceUsd: Double,
        reference: String
    ): NotificationMessage = walletCreditMessage(
        title = "Presupuesto recibido en tu cartera",
        prefix = "El Contador acreditó",
        amountUsd = amountUsd,
        availableBalanceUsd = availableBalanceUsd,
        reference = reference,
        type = ACCOUNTANT_BUDGET_TYPE,
        destination = "ADMIN_CREDIT_WALLET"
    )

    fun creditApproved(
        amountUsd: Double,
        amountBs: Double,
        availableBalanceUsd: Double,
        reference: String
    ): NotificationMessage {
        val base = walletCreditMessage(
            title = "Crédito recibido en tu cartera",
            prefix = "Tu préstamo aprobado acreditó",
            amountUsd = amountUsd,
            availableBalanceUsd = availableBalanceUsd,
            reference = reference,
            type = CREDIT_APPROVED_TYPE,
            destination = "CREDIT_WALLET"
        )
        val amountVes = String.format(Locale.US, "%.2f", amountBs)
        return base.copy(
            body = base.body.replace(". Saldo disponible", " (Bs $amountVes según BCV). Saldo disponible"),
            data = base.data + ("amountBs" to amountVes)
        )
    }

    private fun walletCreditMessage(
        title: String,
        prefix: String,
        amountUsd: Double,
        availableBalanceUsd: Double,
        reference: String,
        type: String,
        destination: String
    ): NotificationMessage {
        val amount = String.format(Locale.US, "%.2f", amountUsd)
        val balance = String.format(Locale.US, "%.2f", availableBalanceUsd)
        val cleanReference = reference.trim().take(120)
        return NotificationMessage(
            title = title,
            body = "$prefix US$ $amount. Saldo disponible: US$ $balance. Ref: $cleanReference.",
            type = type,
            data = mapOf(
                "amountUsd" to amount,
                "availableBalanceUsd" to balance,
                "reference" to cleanReference,
                "destination" to destination
            )
        )
    }
}
