package com.impulsosocial.server.db

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BudgetPlanningSchemaTest {
    private val schema by lazy { Files.readString(Path.of("src/main/resources/db/schema.sql")) }

    @Test
    fun `define la estructura completa de planificacion presupuestaria`() {
        listOf(
            "catalogo_costos",
            "centros_costo",
            "periodos_presupuestarios",
            "partidas_presupuestarias",
            "compromisos_presupuestarios",
            "ajustes_presupuestarios"
        ).forEach { table -> assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS $table"), "Falta $table") }
        assertTrue(schema.contains("VALUES (83, 'Credicash 1.1.0"))
    }

    @Test
    fun `separa inventario operacion administracion comercial financiero y extraordinario`() {
        listOf(
            "'INVENTORY',NULL,'Costos de mercancía e inventario'",
            "'OPERATING',NULL,'Gastos operativos'",
            "'ADMINISTRATIVE',NULL,'Gastos administrativos'",
            "'COMMERCIAL',NULL,'Gastos comerciales y marketing'",
            "'FINANCIAL',NULL,'Gastos financieros'",
            "'EXTRAORDINARY',NULL,'Gastos extraordinarios'"
        ).forEach { seed -> assertTrue(schema.contains(seed), "Falta el grupo $seed") }
    }

    @Test
    fun `conserva y reclasifica las categorias historicas`() {
        assertTrue(schema.contains("WHEN 'BAGS' THEN 'OP-BAGS'"))
        assertTrue(schema.contains("WHEN 'MARKETING' THEN 'COMM-ADVERTISING'"))
        assertTrue(schema.contains("WHEN 'BANK_FEES' THEN 'FIN-BANK-FEES'"))
        assertTrue(schema.contains("WHERE m.categoria_costo_id IS NULL"))
    }

    @Test
    fun `el gasto puede vincularse con periodo partida centro y compromiso`() {
        listOf(
            "categoria_costo_id",
            "centro_costo_id",
            "periodo_presupuestario_id",
            "partida_presupuestaria_id",
            "compromiso_id",
            "estado_control_presupuesto"
        ).forEach { column -> assertTrue(schema.contains("ADD COLUMN IF NOT EXISTS $column"), "Falta $column") }
    }

    @Test
    fun `el esquema completo conserva sentencias sql separables`() {
        val statements = SqlScriptParser.split(schema)
        assertTrue(statements.size > 200)
        assertTrue(statements.any { it.contains("VALUES (83, 'Credicash 1.1.0") })
    }
}
