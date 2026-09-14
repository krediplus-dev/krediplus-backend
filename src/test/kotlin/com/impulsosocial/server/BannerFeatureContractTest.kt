package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BannerFeatureContractTest {
    private fun projectFile(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `banners soportan jornada opcional vigencia y orden`() {
        val schema = projectFile("src/main/resources/db/schema.sql")
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS banners_inicio"))
        assertTrue(schema.contains("fair_id BIGINT REFERENCES jornadas(id) ON DELETE SET NULL"))
        assertTrue(schema.contains("sort_order INTEGER NOT NULL DEFAULT 0"))
        assertTrue(schema.contains("starts_at TIMESTAMPTZ"))
        assertTrue(schema.contains("ends_at TIMESTAMPTZ"))
    }

    @Test
    fun `api publica carrusel y administrador puede gestionarlo`() {
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        val service = projectFile("src/main/kotlin/com/impulsosocial/server/service/AppService.kt")
        assertTrue(application.contains("get(\"/banners\")"))
        assertTrue(application.contains("post(\"/banners\")"))
        assertTrue(application.contains("put(\"/banners/{id}\")"))
        assertTrue(application.contains("delete(\"/banners/{id}\")"))
        assertTrue(application.contains("post(\"/banners/{id}/image\")"))
        assertTrue(service.contains("b.starts_at IS NULL OR b.starts_at<=NOW()"))
        assertTrue(service.contains("b.ends_at IS NULL OR b.ends_at>=NOW()"))
    }

    @Test
    fun `orden de banner se resuelve automaticamente en backend`() {
        val service = projectFile("src/main/kotlin/com/impulsosocial/server/service/AppService.kt")
        assertTrue(service.contains("LOCK TABLE banners_inicio IN SHARE ROW EXCLUSIVE MODE"))
        assertTrue(service.contains("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM banners_inicio"))
        assertTrue(service.contains("SELECT sort_order FROM banners_inicio WHERE id=?"))
    }
}
