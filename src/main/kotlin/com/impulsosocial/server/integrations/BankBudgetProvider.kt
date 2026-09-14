package com.impulsosocial.server.integrations

import java.time.OffsetDateTime

/**
 * Contrato preparado para integrar en el futuro APIs bancarias u Open Banking.
 * El presupuesto operativo se registra en la base de datos. Ningún proveedor externo se invoca
 * hasta que exista una integración bancaria configurada y confirmada.
 */
interface BankBudgetProvider {
    val providerId: String

    fun connectionStatus(): BankConnectionStatus

    fun fetchAvailableBudget(externalAccountId: String): BankBudgetSnapshot

    fun verifyIncomingTransaction(externalTransactionId: String): BankTransactionVerification
}

data class BankConnectionStatus(
    val connected: Boolean,
    val status: String,
    val checkedAt: String = OffsetDateTime.now().toString()
)

data class BankBudgetSnapshot(
    val externalAccountId: String,
    val availableUsd: Double,
    val currency: String = "USD",
    val synchronizedAt: String = OffsetDateTime.now().toString()
)

data class BankTransactionVerification(
    val externalTransactionId: String,
    val verified: Boolean,
    val amountUsd: Double? = null,
    val verifiedAt: String = OffsetDateTime.now().toString()
)

/**
 * Adaptador seguro de espera. Hace explícito que la estructura está lista, pero
 * impide llamadas bancarias ficticias y evita acreditar dinero sin confirmación real.
 */
class PreparedBankBudgetProvider(private val enabled: Boolean = false) : BankBudgetProvider {
    override val providerId: String = "PENDING_BANK_PROVIDER"

    override fun connectionStatus(): BankConnectionStatus =
        BankConnectionStatus(connected = false, status = if (enabled) "READY_FOR_BANK_API" else "DISABLED")

    override fun fetchAvailableBudget(externalAccountId: String): BankBudgetSnapshot =
        throw UnsupportedOperationException(
            if (enabled) "La API bancaria está habilitada, pero todavía no tiene un proveedor configurado."
            else "La integración bancaria está desactivada mediante BANK_INTEGRATION_ENABLED=false."
        )

    override fun verifyIncomingTransaction(externalTransactionId: String): BankTransactionVerification =
        BankTransactionVerification(externalTransactionId = externalTransactionId, verified = false)
}
