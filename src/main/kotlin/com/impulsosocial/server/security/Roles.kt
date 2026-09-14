package com.impulsosocial.server.security

/**
 * Canonical role policy shared by login, JWT creation and authorization.
 * Unknown values are deliberately downgraded to BENEFICIARY.
 */
object Roles {
    const val ADMIN = "ADMIN"
    const val ACCOUNTANT = "ACCOUNTANT"
    const val WAREHOUSE = "WAREHOUSE"
    const val BENEFICIARY = "BENEFICIARY"

    fun canonical(value: String?): String {
        val normalized = value
            ?.trim()
            ?.uppercase()
            ?.replace('-', '_')
            ?.replace(' ', '_')
            .orEmpty()

        return when (normalized) {
            "ADMIN", "ADMINISTRATOR", "ADMINISTRADOR", "SUPERADMIN", "SUPER_ADMIN" -> ADMIN
            "ACCOUNTANT", "CONTADOR", "CONTADORA", "FINANCE", "FINANZAS" -> ACCOUNTANT
            "WAREHOUSE", "ALMACENISTA", "STOREKEEPER", "ALMACEN", "BODEGA" -> WAREHOUSE
            else -> BENEFICIARY
        }
    }

    fun isAdmin(value: String?): Boolean = canonical(value) == ADMIN
    fun isAccountant(value: String?): Boolean = canonical(value) == ACCOUNTANT
    fun isWarehouse(value: String?): Boolean = canonical(value) == WAREHOUSE
}
