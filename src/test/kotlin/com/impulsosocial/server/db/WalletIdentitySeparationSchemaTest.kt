package com.impulsosocial.server.db

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class WalletIdentitySeparationSchemaTest {
    @Test
    fun `admin and beneficiary wallets have independent scopes`() {
        val schema = File("src/main/resources/db/schema.sql").readText()
        assertTrue(schema.contains("wallet_scope VARCHAR(30) NOT NULL DEFAULT 'ADMIN_OPERATIONAL'"))
        assertTrue(schema.contains("wallet_scope VARCHAR(30) NOT NULL DEFAULT 'BENEFICIARY'"))
        assertTrue(schema.contains("trg_wallet_scope_admin_identity"))
        assertTrue(schema.contains("trg_wallet_scope_beneficiary_identity"))
        assertTrue(schema.contains("Nunca se consolida por person_group_id"))
    }
}
