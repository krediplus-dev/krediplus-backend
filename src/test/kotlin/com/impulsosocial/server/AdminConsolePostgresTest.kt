package com.impulsosocial.server

import com.impulsosocial.server.config.AppConfig
import com.impulsosocial.server.db.Database
import com.impulsosocial.server.security.PasswordSecurity
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class AdminConsolePostgresTest {
    @Test
    fun `consola crea administrador con identidad y rol validos y permite actualizarlo`() {
        val url = System.getenv("TEST_DATABASE_URL")
        org.junit.Assume.assumeTrue("Requiere PostgreSQL de pruebas en TEST_DATABASE_URL", !url.isNullOrBlank())
        val database = Database(AppConfig(
            dbUrl = url!!,
            dbUser = System.getenv("TEST_DB_USER") ?: "credicash_test",
            dbPassword = System.getenv("TEST_DB_PASSWORD") ?: "credicash_test_password",
            jwtSecret = "integration-test-secret-with-more-than-thirty-two-bytes",
            usesGeneratedJwtSecret = false
        ))
        val uniqueId = UUID.randomUUID().toString().take(8)
        val input = ConsoleAdminInput(
            "admin-$uniqueId@example.invalid", "Ana", "Maria", "Prueba", "Local",
            "QA-$uniqueId", LocalDate.of(1990, 1, 1), "KpPrueba!9862", "825047"
        )
        try {
            database.initializeSchema()
            val security = PasswordSecurity()
            createOrUpdateAdmin(database, security, input)
            createOrUpdateAdmin(database, security, input.copy(firstName = "Lucia"))
            database.dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT username,role,admin_subrole,account_kind,password_hash FROM usuarios WHERE email=?").use { statement ->
                    statement.setString(1, input.email)
                    statement.executeQuery().use { row ->
                        assertTrue(row.next())
                        assertTrue(row.getString("username").isNotBlank())
                        assertEquals("ADMIN", row.getString("role"))
                        assertEquals("GENERAL", row.getString("admin_subrole"))
                        assertEquals("OPERATIONAL", row.getString("account_kind"))
                        assertTrue(security.verify(row.getString("password_hash"), input.password))
                        assertFalse(row.next())
                    }
                }
            }
        } finally { database.close() }
    }
}
