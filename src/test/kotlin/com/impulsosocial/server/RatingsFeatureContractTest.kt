package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class RatingsFeatureContractTest {
    private fun projectFile(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `schema guarda una sola calificacion por usuario y entidad`() {
        val schema = projectFile("src/main/resources/db/schema.sql")
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS calificaciones_banner"))
        assertTrue(schema.contains("UNIQUE(banner_id, user_id)"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS calificaciones_compra"))
        assertTrue(schema.contains("UNIQUE(order_id, user_id)"))
        assertTrue(schema.contains("version, description") && schema.contains("VALUES (86,"))
    }

    @Test
    fun `api permite votar banners calificar compras y consultar estadisticas`() {
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        assertTrue(application.contains("post(\"/banners/{id}/rating\")"))
        assertTrue(application.contains("post(\"/purchases/{id}/rating\")"))
        assertTrue(application.contains("get(\"/ratings/insights\")"))
    }

    @Test
    fun `servicio usa upsert para impedir votos duplicados`() {
        val service = projectFile("src/main/kotlin/com/impulsosocial/server/service/AppService.kt")
        assertTrue(service.contains("ON CONFLICT(banner_id,user_id) DO UPDATE"))
        assertTrue(service.contains("ON CONFLICT(order_id,user_id) DO UPDATE"))
    }
}
