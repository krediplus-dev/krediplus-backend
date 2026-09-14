package com.impulsosocial.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UploadPolicyTest {
    @Test
    fun `detecta una imagen png por su contenido`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(300)
        val result = UploadPolicy.validate("payment-proofs", bytes)
        assertEquals("png", result.extension)
        assertTrue(result.image)
        assertEquals(64, result.sha256.length)
    }

    @Test
    fun `rechaza pdf como comprobante de pago`() {
        val bytes = "%PDF-1.7\n".toByteArray() + ByteArray(300)
        assertFailsWith<AppException> { UploadPolicy.validate("payment-proofs", bytes) }
    }

    @Test
    fun `rechaza pdf como logo de negocio`() {
        val bytes = "%PDF-1.7\n".toByteArray() + ByteArray(300)
        assertFailsWith<AppException> { UploadPolicy.validate("business-logos", bytes) }
    }

    @Test
    fun `permite pdf como documento`() {
        val bytes = "%PDF-1.7\n".toByteArray() + ByteArray(300)
        assertEquals("pdf", UploadPolicy.validate("documents", bytes).extension)
    }

    @Test
    fun `rechaza formatos ejecutables`() {
        val bytes = byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()) + ByteArray(300)
        assertFailsWith<AppException> { UploadPolicy.validate("documents", bytes) }
    }
}
