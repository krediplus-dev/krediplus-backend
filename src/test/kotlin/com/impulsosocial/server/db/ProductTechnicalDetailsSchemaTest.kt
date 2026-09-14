package com.impulsosocial.server.db

import kotlin.test.Test
import kotlin.test.assertTrue

class ProductTechnicalDetailsSchemaTest {
    @Test
    fun productTechnicalDetailsUsesTextColumnAndMigration73Exists() {
        val schema = checkNotNull(javaClass.classLoader.getResource("db/schema.sql")).readText()
        assertTrue(schema.contains("technical_details TEXT NOT NULL DEFAULT ''"))
        assertTrue(schema.contains("INSERT INTO versiones_esquema(version, description)"))
        assertTrue(schema.contains("VALUES (73, 'Credicash 6.6.22"))
    }
}
