package com.impulsosocial.server.integrations

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.impulsosocial.server.config.AppConfig
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.slf4j.LoggerFactory

data class RecaptchaAssessment(
    val valid: Boolean,
    val action: String?,
    val score: Double,
    val reason: String? = null
)

/**
 * Verifica en el servidor los tokens emitidos por reCAPTCHA Enterprise para Android.
 * La API key del servidor permanece exclusivamente en el backend.
 */
class RecaptchaService(private val config: AppConfig) {
    private val logger = LoggerFactory.getLogger(RecaptchaService::class.java)
    private val gson = Gson()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(7))
        .build()

    val configured: Boolean
        get() = config.recaptchaProjectId.isNotBlank() &&
            config.recaptchaApiKey.isNotBlank() &&
            config.recaptchaSiteKey.isNotBlank()

    fun assess(token: String?, expectedAction: String): RecaptchaAssessment {
        if (!configured) {
            if (config.recaptchaRequired) {
                logger.error(
                    "reCAPTCHA Enterprise es obligatorio para la acción {}, pero faltan RECAPTCHA_PROJECT_ID, RECAPTCHA_API_KEY o RECAPTCHA_SITE_KEY.",
                    expectedAction
                )
                return RecaptchaAssessment(
                    valid = false,
                    action = expectedAction,
                    score = 0.0,
                    reason = "RECAPTCHA_CONFIGURATION_MISSING"
                )
            }
            logger.warn(
                "reCAPTCHA Enterprise está desactivado mediante RECAPTCHA_REQUIRED=false; se aplicará la protección visual local y el bloqueo de intentos para la acción {}.",
                expectedAction
            )
            return RecaptchaAssessment(valid = true, action = expectedAction, score = 1.0, reason = "LOCAL_VISUAL_FALLBACK")
        }

        val cleanToken = token?.trim().orEmpty()
        if (cleanToken.isBlank()) {
            if (config.recaptchaRequired) {
                logger.warn(
                    "reCAPTCHA Enterprise es obligatorio para la acción {}, pero el cliente no envió un token.",
                    expectedAction
                )
                return RecaptchaAssessment(false, null, 0.0, "TOKEN_MISSING")
            }
            // La integración Enterprise puede estar configurada en el proveedor cloud mientras el APK
            // utiliza únicamente el desafío visual local. En modo opcional, la ausencia de
            // token no debe convertir una verificación visual completada en un rechazo.
            logger.info(
                "La acción {} usará el respaldo visual local porque no se recibió token Enterprise y RECAPTCHA_REQUIRED=false.",
                expectedAction
            )
            return RecaptchaAssessment(
                valid = true,
                action = expectedAction,
                score = 1.0,
                reason = "LOCAL_VISUAL_FALLBACK_WITH_ENTERPRISE_CONFIGURED"
            )
        }

        val payload = mapOf(
            "event" to mapOf(
                "token" to cleanToken,
                "siteKey" to config.recaptchaSiteKey,
                "expectedAction" to expectedAction
            )
        )
        val encodedApiKey = URLEncoder.encode(config.recaptchaApiKey, StandardCharsets.UTF_8)
        val url = "https://recaptchaenterprise.googleapis.com/v1/projects/${config.recaptchaProjectId}/assessments?key=$encodedApiKey"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json; charset=UTF-8")
            .timeout(Duration.ofSeconds(12))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            logger.error("reCAPTCHA Enterprise respondió HTTP {}.", response.statusCode())
            error("No fue posible validar reCAPTCHA con Google.")
        }

        val json = JsonParser.parseString(response.body()).asJsonObject
        val tokenProperties = json.getAsJsonObject("tokenProperties")
        val riskAnalysis = json.getAsJsonObject("riskAnalysis")
        val valid = tokenProperties?.get("valid")?.asBoolean ?: false
        val action = tokenProperties?.get("action")?.asString
        val invalidReason = tokenProperties?.get("invalidReason")?.asString
        val score = riskAnalysis?.get("score")?.asDouble ?: 0.0

        val actionMatches = action.equals(expectedAction, ignoreCase = true)
        val accepted = valid && actionMatches && score >= config.recaptchaMinScore
        return RecaptchaAssessment(
            valid = accepted,
            action = action,
            score = score,
            reason = when {
                !valid -> invalidReason ?: "TOKEN_INVALID"
                !actionMatches -> "ACTION_MISMATCH"
                score < config.recaptchaMinScore -> "LOW_SCORE"
                else -> null
            }
        )
    }
}
