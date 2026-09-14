package com.impulsosocial.server.service

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadAccessPolicyTest {
    private val instant = Instant.parse("2026-08-19T12:00:00Z")
    private val policy = UploadAccessPolicy("secreto-de-prueba-suficientemente-largo") { instant }

    @Test
    fun `los documentos usan una url firmada que vence`() {
        val url = assertNotNull(policy.url("https://api.credicash.test", "documents/42/frente.png"))
        val uri = URI(url)
        val query = uri.rawQuery.split('&').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts[1]
        }

        assertTrue(policy.canRead("documents/42/frente.png", query["expires"], query["signature"]))
        val expiredPolicy = UploadAccessPolicy("secreto-de-prueba-suficientemente-largo") {
            instant.plusSeconds(31 * 60)
        }
        assertFalse(expiredPolicy.canRead("documents/42/frente.png", query["expires"], query["signature"]))
    }

    @Test
    fun `una firma no sirve para otro archivo`() {
        val uri = URI(assertNotNull(policy.url("https://api.credicash.test", "payment-proofs/7/pago.png")))
        val query = uri.rawQuery.split('&').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts[1]
        }
        assertFalse(policy.canRead("payment-proofs/7/otro.png", query["expires"], query["signature"]))
    }

    @Test
    fun `los recursos de catalogo siguen siendo publicos`() {
        assertTrue(policy.canRead("fair-products/2/producto.webp", null, null))
        assertFalse(assertNotNull(policy.url("https://api.credicash.test", "fair-products/2/producto.webp")).contains("signature="))
    }

    @Test
    fun `rechaza rutas ambiguas`() {
        assertNull(policy.url("https://api.credicash.test", "documents/../secreto.pdf"))
        assertFalse(policy.canRead("documents/../secreto.pdf", null, null))
    }

    @Test
    fun `una categoria desconocida es privada por defecto`() {
        val path = "future-sensitive/42/archivo.pdf"
        val url = assertNotNull(policy.url("https://api.credicash.test", path))
        assertTrue(url.contains("signature="))
        assertFalse(policy.canRead(path, null, null))
    }
}
