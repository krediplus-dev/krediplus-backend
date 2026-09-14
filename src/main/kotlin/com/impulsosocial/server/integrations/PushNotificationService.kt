package com.impulsosocial.server.integrations

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.impulsosocial.server.config.AppConfig
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/**
 * Envío FCM HTTP v1 sin credenciales embebidas en el código.
 * Se activa mediante una de estas opciones, sin credenciales embebidas:
 * - FIREBASE_SERVICE_ACCOUNT_BASE64: JSON completo codificado en Base64 (recomendado en producción).
 * - FIREBASE_SERVICE_ACCOUNT_JSON: JSON completo o ruta local al archivo (compatibilidad Windows).
 */
class PushNotificationService(private val config: AppConfig) {
    private val logger = LoggerFactory.getLogger(PushNotificationService::class.java)
    private val gson = Gson()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(7)).build()
    private val credentials = loadCredentials()
    private val cachedAccessToken = AtomicReference<OAuthToken?>(null)

    val enabled: Boolean get() = credentials != null

    init {
        if (credentials != null) {
            logger.info("FCM HTTP v1 configurado para el proyecto '{}'.", credentials.projectId)
        } else {
            logger.warn("FCM no está configurado. Define FIREBASE_SERVICE_ACCOUNT_BASE64 en el entorno del backend para habilitar notificaciones push.")
        }
    }

    /**
     * Envía la notificación y devuelve los tokens que Firebase confirmó como obsoletos.
     * Los errores UNREGISTERED/NotRegistered no son fallos operativos: indican que la app
     * fue desinstalada, reinstalada o Firebase rotó el token. El llamador debe eliminarlos
     * de la base de datos para no volver a intentar enviarlos indefinidamente.
     */
    fun send(
        tokens: Collection<String>,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): PushSendReport {
        val cleanTokens = tokens.map(String::trim).filter(String::isNotBlank).distinct()
        if (cleanTokens.isEmpty()) {
            logger.info("Notificación '{}' sin tokens registrados; queda disponible en el centro de notificaciones.", title)
            return PushSendReport()
        }
        if (credentials == null) {
            logger.warn("FCM desactivado: falta FIREBASE_SERVICE_ACCOUNT_BASE64 o FIREBASE_SERVICE_ACCOUNT_JSON válido. No se enviará push para '{}'.", title)
            return PushSendReport(failedTokens = cleanTokens.toSet(), configurationMissing = true)
        }

        val sentTokens = linkedSetOf<String>()
        val invalidTokens = linkedSetOf<String>()
        val failedTokens = linkedSetOf<String>()
        cleanTokens.forEach { token ->
            runCatching { sendWithRetry(token, title, body, data) }
                .onSuccess { outcome ->
                    when (outcome) {
                        SendOutcome.SENT -> sentTokens += token
                        SendOutcome.UNREGISTERED -> invalidTokens += token
                    }
                }
                .onFailure { error ->
                    failedTokens += token
                    logger.error("Error enviando push FCM para '{}'", title, error)
                }
        }
        return PushSendReport(
            sentTokens = sentTokens,
            invalidTokens = invalidTokens,
            failedTokens = failedTokens
        )
    }

    private fun sendWithRetry(token: String, title: String, body: String, data: Map<String, String>): SendOutcome {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return sendOne(token, title, body, data)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: FcmHttpException) {
                lastError = error
                if (!error.retryable || attempt == 2) throw error
                if (error.statusCode == 401) cachedAccessToken.set(null)
                Thread.sleep(FcmRetryPolicy.delayForAttempt(attempt) ?: 3_000L)
            } catch (error: IOException) {
                lastError = error
                if (attempt == 2) throw error
                Thread.sleep(FcmRetryPolicy.delayForAttempt(attempt) ?: 3_000L)
            }
        }
        throw lastError ?: IllegalStateException("No fue posible enviar la notificación FCM.")
    }

    private fun sendOne(token: String, title: String, body: String, data: Map<String, String>): SendOutcome {
        val creds = credentials ?: return SendOutcome.SENT
        val accessToken = accessToken(creds)
        val payload = mapOf(
            "message" to mapOf(
                "token" to token,
                // Cambios 32: mensaje solo de datos. Así Android no lo muestra de forma
                // automática cuando la cuenta cerró sesión; la app valida el destinatario.
                "data" to (data + mapOf("title" to title, "body" to body)),
                "android" to mapOf(
                    "priority" to "high",
                    "ttl" to "3600s"
                )
            )
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://fcm.googleapis.com/v1/projects/${creds.projectId}/messages:send"))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json; charset=UTF-8")
            .timeout(Duration.ofSeconds(12))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            val responseBody = response.body().orEmpty()
            if (isUnregisteredToken(response.statusCode(), responseBody)) {
                logger.info("Token FCM obsoleto detectado para '{}'; se retirará automáticamente de la base de datos.", title)
                return SendOutcome.UNREGISTERED
            }
            val statusCode = response.statusCode()
            throw FcmHttpException(
                statusCode,
                responseBody.take(600),
                FcmRetryPolicy.isRetryableStatus(statusCode)
            )
        }
        logger.info("Push FCM enviado correctamente para '{}'.", title)
        return SendOutcome.SENT
    }

    private fun isUnregisteredToken(statusCode: Int, responseBody: String): Boolean {
        if (statusCode !in listOf(400, 404)) return false
        val normalized = responseBody.uppercase()
        return normalized.contains("UNREGISTERED") ||
            normalized.contains("NOTREGISTERED") ||
            normalized.contains("REGISTRATION-TOKEN-NOT-REGISTERED")
    }

    private fun accessToken(creds: ServiceAccountCredentials): String {
        val now = Instant.now().epochSecond
        cachedAccessToken.get()?.takeIf { it.expiresAtEpochSeconds - 90 > now }?.let { return it.value }

        val assertion = createSignedAssertion(creds, now)
        val body = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:jwt-bearer") +
            "&assertion=" + urlEncode(assertion)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(12))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            val statusCode = response.statusCode()
            throw FcmHttpException(
                statusCode,
                response.body().orEmpty().take(600),
                FcmRetryPolicy.isRetryableStatus(statusCode)
            )
        }
        val json = JsonParser.parseString(response.body()).asJsonObject
        val token = json.get("access_token")?.asString ?: error("FCM no devolvió access_token.")
        val expiresIn = json.get("expires_in")?.asLong ?: 3600L
        cachedAccessToken.set(OAuthToken(token, now + expiresIn))
        return token
    }

    private fun createSignedAssertion(creds: ServiceAccountCredentials, now: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray(StandardCharsets.UTF_8))
        val payloadJson = gson.toJson(
            mapOf(
                "iss" to creds.clientEmail,
                "scope" to "https://www.googleapis.com/auth/firebase.messaging",
                "aud" to "https://oauth2.googleapis.com/token",
                "iat" to now,
                "exp" to now + 3600
            )
        )
        val payload = encoder.encodeToString(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$header.$payload"
        val privateKeyBytes = Base64.getMimeDecoder().decode(
            creds.privateKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "\n")
                .replace("\n", "")
                .trim()
        )
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.toByteArray(StandardCharsets.UTF_8))
        }.sign()
        return "$signingInput.${encoder.encodeToString(signature)}"
    }

    private fun loadCredentials(): ServiceAccountCredentials? {
        val jsonText = loadServiceAccountJson() ?: return null
        val json = runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull() ?: return null
        val projectId = json.get("project_id")?.asString?.trim().orEmpty()
        val clientEmail = json.get("client_email")?.asString?.trim().orEmpty()
        val privateKey = json.get("private_key")?.asString.orEmpty()
        if (projectId.isBlank() || clientEmail.isBlank() || privateKey.isBlank()) return null
        return ServiceAccountCredentials(projectId, clientEmail, privateKey)
    }

    private fun loadServiceAccountJson(): String? {
        config.firebaseServiceAccountBase64?.trim()?.takeIf { it.isNotEmpty() }?.let { encoded ->
            return runCatching {
                String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
            }.recoverCatching {
                String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8)
            }.getOrNull()
        }

        val configured = config.firebaseServiceAccountJson?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (configured.startsWith("{")) return configured
        val file = File(configured)
        return file.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)



    private class FcmHttpException(
        val statusCode: Int,
        responseBody: String,
        val retryable: Boolean
    ) : RuntimeException("FCM respondió $statusCode: $responseBody")

    data class PushSendReport(
        val sentTokens: Set<String> = emptySet(),
        val invalidTokens: Set<String> = emptySet(),
        val failedTokens: Set<String> = emptySet(),
        val configurationMissing: Boolean = false
    )

    private enum class SendOutcome {
        SENT,
        UNREGISTERED
    }

    private data class ServiceAccountCredentials(
        val projectId: String,
        val clientEmail: String,
        val privateKey: String
    )

    private data class OAuthToken(val value: String, val expiresAtEpochSeconds: Long)
}
