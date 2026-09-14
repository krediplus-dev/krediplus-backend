package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommercialQrImageContractTest {
    private fun projectFile(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `items QR obtienen imagen desde productos jornada y no desde productos`() {
        val service = projectFile("src/main/kotlin/com/impulsosocial/server/service/AppService.kt")
        assertTrue(service.contains("SELECT pj.image_path FROM productos_jornada pj"))
        assertFalse(service.contains("i.unit_price_usd,p.image_path FROM items_oferta_qr"))
    }
}
