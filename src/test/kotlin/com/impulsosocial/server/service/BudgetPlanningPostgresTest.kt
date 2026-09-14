package com.impulsosocial.server.service

import com.impulsosocial.server.config.AppConfig
import com.impulsosocial.server.db.Database
import com.impulsosocial.server.model.BudgetCommitmentRequest
import com.impulsosocial.server.model.BudgetLineRequest
import com.impulsosocial.server.model.BudgetPeriodRequest
import com.impulsosocial.server.model.BudgetPeriodStatusRequest
import com.impulsosocial.server.model.CostCenterRequest
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BudgetPlanningPostgresTest {
    @Test
    fun `crea periodo partidas y compromiso sobre postgres real`() {
        val databaseUrl = System.getenv("TEST_DATABASE_URL")
        org.junit.Assume.assumeTrue("Requiere PostgreSQL de pruebas en TEST_DATABASE_URL", !databaseUrl.isNullOrBlank())
        val database = Database(
            AppConfig(
                dbUrl = databaseUrl!!,
                dbUser = System.getenv("TEST_DB_USER") ?: "credicash_test",
                dbPassword = System.getenv("TEST_DB_PASSWORD") ?: "credicash_test_password",
                dbConfigurationSource = "integration-test",
                jwtSecret = "integration-test-secret-that-is-longer-than-thirty-two-bytes",
                usesGeneratedJwtSecret = false,
                publicBaseUrl = "http://localhost:8080"
            )
        )
        try {
            database.initializeSchema()
            val accountantId = database.transaction { connection ->
                val userId = connection.prepareStatement(
                    """INSERT INTO usuarios(
                           username,email,password_hash,pin_hash,role,account_status,verification_status,
                           email_verified,phone_verified,admin_subrole,account_kind
                       ) VALUES ('CONTADOR_TEST','contador-test@credicash.local','hash','hash','ACCOUNTANT',
                                 'ACTIVE','VERIFIED',TRUE,TRUE,'ACCOUNTING','ACCOUNTANT')
                       RETURNING id"""
                ).use { statement -> statement.executeQuery().use { result -> result.next(); result.getLong(1) } }
                connection.prepareStatement("INSERT INTO contadores(user_id) VALUES (?)").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeUpdate()
                }
                userId
            }
            val service = BudgetPlanningService(database)
            val groups = service.catalog(accountantId)
            assertEquals(6, groups.size)
            val delivery = groups.asSequence()
                .flatMap { it.children.asSequence() }
                .flatMap { it.children.asSequence() }
                .first { it.code == "OP-DELIVERY" }
            val center = service.createCostCenter(
                accountantId,
                CostCenterRequest("LOGISTICA_TEST", "Logística de prueba", "LOGISTICS")
            )
            val month = YearMonth.now().plusMonths(2)
            val period = service.createPeriod(
                accountantId,
                BudgetPeriodRequest(
                    code = "TEST-$month",
                    name = "Prueba $month",
                    startDate = month.atDay(1).toString(),
                    endDate = month.atEndOfMonth().toString()
                )
            )
            val line = service.saveBudgetLine(
                accountantId,
                BudgetLineRequest(period.id, delivery.id, center.id, 1_000.00, "Delivery planificado")
            )
            service.changePeriodStatus(accountantId, period.id, BudgetPeriodStatusRequest("SUBMITTED"))
            service.changePeriodStatus(accountantId, period.id, BudgetPeriodStatusRequest("APPROVED"))
            service.changePeriodStatus(accountantId, period.id, BudgetPeriodStatusRequest("ACTIVE"))
            val commitment = service.createCommitment(
                accountantId,
                BudgetCommitmentRequest(
                    budgetLineId = line.id,
                    amountUsd = 300.00,
                    description = "Servicio de delivery para la jornada de prueba",
                    supplier = "Proveedor de prueba",
                    expectedPaymentDate = LocalDate.now().plusDays(15).toString(),
                    idempotencyKey = "integration-budget-commitment"
                )
            )
            assertEquals("COMMITTED", commitment.status)
            val dashboard = service.dashboard(accountantId, period.id)
            assertEquals(1_000.0, dashboard.currentUsd)
            assertEquals(300.0, dashboard.committedUsd)
            assertEquals(700.0, dashboard.availableUsd)
            assertTrue(dashboard.lines.single().executionPercent == 0.0)
        } finally {
            database.close()
        }
    }
}
