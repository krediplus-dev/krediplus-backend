package com.impulsosocial.server

import com.impulsosocial.server.config.AppConfig
import com.impulsosocial.server.db.Database
import java.sql.SQLException
import kotlin.math.min
import kotlin.system.exitProcess

/**
 * Migración manual de Kredi+.
 *
 * El despliegue actual no ejecuta esta clase automáticamente. El servidor aplica
 * el esquema al iniciar, con reintentos, y /health/ready solo responde 200 cuando
 * PostgreSQL y la migración 69 están listos. Esta utilidad queda disponible para
 * diagnóstico o mantenimiento controlado.
 */
fun main() {
    val maxAttempts = (System.getenv("MIGRATION_MAX_ATTEMPTS")?.toIntOrNull() ?: 6).coerceIn(1, 20)
    var lastError: Throwable? = null

    for (attempt in 1..maxAttempts) {
        val startedAt = System.currentTimeMillis()
        val database = Database(AppConfig())
        try {
            println("Kredi+ $CREDICASH_APP_VERSION: migración PostgreSQL manual, intento $attempt de $maxAttempts...")
            database.ensureAuthenticationSchema()
            database.initializeSchema()
            database.ensureAuthenticationSchema()
            database.verifyRequiredSchema()
            val elapsed = System.currentTimeMillis() - startedAt
            println("Kredi+ $CREDICASH_APP_VERSION: migración completada correctamente en ${elapsed} ms.")
            return
        } catch (error: Throwable) {
            lastError = error
            val sqlState = generateSequence(error) { it.cause }
                .filterIsInstance<SQLException>()
                .firstOrNull()
                ?.sqlState
            System.err.println(
                "Kredi+ $CREDICASH_APP_VERSION: intento $attempt falló" +
                    (sqlState?.let { " (SQLSTATE=$it)" } ?: "") +
                    ": ${error.message.orEmpty()}"
            )
        } finally {
            database.close()
        }

        if (attempt < maxAttempts) {
            val waitMillis = min(15_000L, 2_000L * attempt)
            System.err.println("Se reintentará en ${waitMillis} ms...")
            Thread.sleep(waitMillis)
        }
    }

    System.err.println("Kredi+ $CREDICASH_APP_VERSION: la migración manual no pudo completarse tras $maxAttempts intentos.")
    lastError?.printStackTrace()
    exitProcess(1)
}
