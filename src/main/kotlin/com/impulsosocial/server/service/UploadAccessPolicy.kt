package com.impulsosocial.server.service

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mantiene públicos los recursos de catálogo, pero exige una firma temporal para
 * documentos de identidad y comprobantes de pago. La firma permite que clientes
 * móviles y paneles web muestren el archivo sin convertir la carpeta completa en pública.
 */
class UploadAccessPolicy(
    secret: String,
    private val now: () -> Instant = Instant::now
) {
    private val signingKey = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM)

    fun url(publicBaseUrl: String, relativePath: String?): String? {
        val path = normalize(relativePath) ?: return null
        val encodedPath = path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
        }
        val base = "${publicBaseUrl.trimEnd('/')}/uploads/$encodedPath"
        if (!isPrivate(path)) return base

        val expiresAt = now().plus(SIGNED_URL_TTL).epochSecond
        return "$base?expires=$expiresAt&signature=${signature(path, expiresAt)}"
    }

    fun canRead(relativePath: String, expires: String?, providedSignature: String?): Boolean {
        val path = normalize(relativePath) ?: return false
        if (!isPrivate(path)) return true

        val expiresAt = expires?.toLongOrNull() ?: return false
        val currentEpoch = now().epochSecond
        if (expiresAt < currentEpoch || expiresAt > currentEpoch + MAX_ACCEPTED_FUTURE.seconds) return false
        val provided = providedSignature?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val expected = signature(path, expiresAt)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            provided.toByteArray(StandardCharsets.US_ASCII)
        )
    }

    fun isPrivate(relativePath: String): Boolean {
        val category = normalize(relativePath)?.substringBefore('/')?.lowercase() ?: return true
        return category !in PUBLIC_CATEGORIES
    }

    private fun signature(path: String, expiresAt: Long): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(signingKey)
        val digest = mac.doFinal("$expiresAt\n$path".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun normalize(relativePath: String?): String? {
        val normalized = relativePath
            ?.replace('\\', '/')
            ?.trim()
            ?.trimStart('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private val SIGNED_URL_TTL: Duration = Duration.ofMinutes(30)
        private val MAX_ACCEPTED_FUTURE: Duration = Duration.ofMinutes(31)
        private val PUBLIC_CATEGORIES = setOf(
            "fair-products",
            "fair-productos",
            "fair-covers",
            "combo-covers",
            "business-logos"
        )
    }
}
