package com.impulsosocial.server.db

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaLoanInvoiceCompatibilityTest {
    private fun schema(): String = requireNotNull(
        javaClass.classLoader.getResourceAsStream("db/schema.sql")
    ) { "No se encontró db/schema.sql" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    @Test
    fun `la reparacion de facturas excluye prestamos sin pedido`() {
        val sql = schema().replace(Regex("\\s+"), " ")
        val repairStart = sql.indexOf("INSERT INTO facturas(order_id, invoice_number)")
        assertTrue(repairStart >= 0, "Falta la reparación histórica de facturas")
        val repair = sql.substring(repairStart, minOf(sql.length, repairStart + 900))

        assertTrue(
            repair.contains("WHERE cl.order_id IS NOT NULL AND f.id IS NULL"),
            "La reparación debe excluir préstamos directos cuyo order_id sea NULL"
        )
        assertFalse(
            repair.contains("WHERE f.id IS NULL ON CONFLICT"),
            "No debe volver a insertarse un order_id NULL en facturas"
        )
    }

    @Test
    fun `los prestamos directos conservan factura propia sin pedido`() {
        val sql = schema()
        assertTrue(sql.contains("ALTER TABLE prestamos_credito ALTER COLUMN order_id DROP NOT NULL"))
        assertTrue(sql.contains("ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS invoice_number"))
        assertTrue(sql.contains("UPDATE prestamos_credito SET invoice_number='CRED-' || id::text"))
        assertTrue(sql.contains("VALUES (72,"), "La migración 72 debe permanecer registrada")
        assertTrue(
            sql.contains("evita crear facturas de pedido para préstamos directos sin order_id"),
            "La migración 72 debe conservar su propósito histórico sin depender de la versión actual"
        )
    }
}
