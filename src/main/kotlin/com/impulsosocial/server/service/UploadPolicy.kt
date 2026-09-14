package com.impulsosocial.server.service

import java.security.MessageDigest

data class ValidatedUpload(
    val extension: String,
    val mimeType: String,
    val sha256: String,
    val image: Boolean
)

/**
 * Política única para archivos recibidos por la API.
 * La extensión se deriva del contenido real y nunca del nombre enviado por el cliente.
 */
object UploadPolicy {
    private const val MIB = 1024 * 1024

    private val imageOnlyCategories = setOf("payment-proofs", "fair-products", "fair-productos", "business-logos")
    private val documentCategories = setOf("documents")

    fun validate(category: String, bytes: ByteArray): ValidatedUpload {
        if (bytes.isEmpty()) throw AppException("El archivo está vacío.")

        val normalizedCategory = category.trim().lowercase()
        val maxBytes = when (normalizedCategory) {
            "payment-proofs" -> 10 * MIB
            "fair-products", "fair-productos", "business-logos" -> 10 * MIB
            "documents" -> 12 * MIB
            else -> 10 * MIB
        }
        if (bytes.size > maxBytes) {
            throw AppException("El archivo supera el límite permitido de ${maxBytes / MIB} MB.")
        }

        val detected = detect(bytes)
            ?: throw AppException("El formato del archivo no es compatible. Usa JPG, PNG, WEBP o PDF.")

        if (normalizedCategory in imageOnlyCategories && !detected.image) {
            throw AppException("Esta función admite únicamente imágenes JPG, PNG o WEBP.")
        }
        if (normalizedCategory in documentCategories && !detected.image && detected.extension != "pdf") {
            throw AppException("Los documentos deben enviarse como imagen o PDF.")
        }
        if (detected.image && bytes.size < 256) {
            throw AppException("La imagen está incompleta o dañada.")
        }

        return detected.copy(sha256 = sha256(bytes))
    }

    private fun detect(bytes: ByteArray): ValidatedUpload? = when {
        startsWith(bytes, 0xFF, 0xD8, 0xFF) -> ValidatedUpload("jpg", "image/jpeg", "", true)
        startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ->
            ValidatedUpload("png", "image/png", "", true)
        bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" ->
            ValidatedUpload("webp", "image/webp", "", true)
        bytes.size >= 5 && ascii(bytes, 0, 5) == "%PDF-" ->
            ValidatedUpload("pdf", "application/pdf", "", false)
        else -> null
    }

    private fun startsWith(bytes: ByteArray, vararg signature: Int): Boolean {
        if (bytes.size < signature.size) return false
        return signature.indices.all { index -> (bytes[index].toInt() and 0xFF) == signature[index] }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
