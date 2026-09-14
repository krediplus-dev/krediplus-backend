package com.impulsosocial.server.db

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class AccountManagementSchemaTest {
    @Test
    fun `suspension columns are part of critical schema`() {
        val databaseSource = File("src/main/kotlin/com/impulsosocial/server/db/Database.kt").readText()
        assertTrue(databaseSource.contains("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ"))
        assertTrue(databaseSource.contains("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(500)"))
        assertTrue(databaseSource.contains("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspended_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL"))
        assertTrue(databaseSource.contains("\"suspended_at\", \"suspension_reason\", \"suspended_by\""))
    }

    @Test
    fun `recreatable account state does not block clean deletion`() {
        val schema = File("src/main/resources/db/schema.sql").readText()
        assertTrue(schema.contains("Migración 84 / Robustez de eliminación"))
        assertTrue(schema.contains("FOREIGN KEY (user_id) REFERENCES usuarios(id) ON DELETE CASCADE"))
        assertTrue(schema.contains("FOREIGN KEY (suspended_by) REFERENCES usuarios(id) ON DELETE SET NULL"))
        val databaseSource = File("src/main/kotlin/com/impulsosocial/server/db/Database.kt").readText()
        assertTrue(databaseSource.contains("version = 84"))
        assertTrue(databaseSource.contains("La migración 84 de robustez de operaciones de cuenta"))
    }
}
