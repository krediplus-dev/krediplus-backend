package com.impulsosocial.server.integrations

import com.impulsosocial.server.CREDICASH_APP_VERSION
import com.google.gson.Gson
import com.impulsosocial.server.config.AppConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import org.slf4j.LoggerFactory

enum class RecoveryEmailKind { PASSWORD, PIN, EMAIL_CHANGE }

/** Correo transaccional de Kredi+. Prioriza API HTTPS y conserva SMTP como respaldo. */
class SmtpEmailService(private val config: AppConfig) {
    private val logger = LoggerFactory.getLogger(SmtpEmailService::class.java)
    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.smtpConnectionTimeoutMs.toLong()))
        .build()

    private val resendConfigured: Boolean
        get() = config.resendApiKey.isNotBlank()

    private val smtpConfigured: Boolean
        get() = config.smtpHost.isNotBlank() &&
            config.smtpUser.isNotBlank() &&
            config.smtpPass.isNotBlank()

    val configured: Boolean
        get() = config.emailRecoveryEnabled &&
            config.mailFromAddress.isNotBlank() &&
            (resendConfigured || smtpConfigured)

    fun sendPasswordResetCode(destination: String, code: String, expiresInMinutes: Long) =
        sendRecoveryCode(destination, code, expiresInMinutes, RecoveryEmailKind.PASSWORD)

    fun sendPinResetCode(destination: String, code: String, expiresInMinutes: Long) =
        sendRecoveryCode(destination, code, expiresInMinutes, RecoveryEmailKind.PIN)

    fun sendEmailChangeCode(destination: String, code: String, expiresInMinutes: Long) =
        sendRecoveryCode(destination, code, expiresInMinutes, RecoveryEmailKind.EMAIL_CHANGE)

    fun sendRecoveryCode(destination: String, code: String, expiresInMinutes: Long, kind: RecoveryEmailKind) {
        check(configured) { "El servicio de recuperación por correo no está configurado." }
        if (resendConfigured) {
            sendViaResend(destination, code, expiresInMinutes, kind)
        } else {
            sendViaSmtp(destination, code, expiresInMinutes, kind)
        }
        logger.info("Kredi+ recovery: correo de recuperación enviado a {}.", maskEmail(destination))
    }

    private fun sendViaResend(destination: String, code: String, expiresInMinutes: Long, kind: RecoveryEmailKind) {
        val subject = subject(kind)
        val payload = gson.toJson(
            mapOf(
                "from" to "${config.mailFromName} <${config.mailFromAddress}>",
                "to" to listOf(destination),
                "subject" to subject,
                "html" to recoveryHtml(code, expiresInMinutes, kind)
            )
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.resendApiUrl))
            .timeout(Duration.ofMillis(config.smtpReadTimeoutMs.toLong()))
            .header("Authorization", "Bearer ${config.resendApiKey}")
            .header("Content-Type", "application/json")
            .header("User-Agent", "KrediPlus-Backend/$CREDICASH_APP_VERSION")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val safeBody = response.body().take(800)
            throw IllegalStateException("Proveedor de correo rechazó la solicitud (HTTP ${response.statusCode()}): $safeBody")
        }
    }

    private fun sendViaSmtp(destination: String, code: String, expiresInMinutes: Long, kind: RecoveryEmailKind) {
        val properties = Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", config.smtpConnectionTimeoutMs.toString())
            put("mail.smtp.timeout", config.smtpReadTimeoutMs.toString())
            put("mail.smtp.writetimeout", config.smtpReadTimeoutMs.toString())
            if (config.smtpSecure) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }

        val session = Session.getInstance(properties, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(config.smtpUser, config.smtpPass)
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.mailFromAddress, config.mailFromName, "UTF-8"))
            setRecipient(Message.RecipientType.TO, InternetAddress(destination))
            setSubject(subject(kind), "UTF-8")
            setContent(recoveryHtml(code, expiresInMinutes, kind), "text/html; charset=UTF-8")
        }
        Transport.send(message)
    }

    private fun subject(kind: RecoveryEmailKind): String = when (kind) {
        RecoveryEmailKind.PASSWORD -> "Código para recuperar tu contraseña de Kredi+"
        RecoveryEmailKind.PIN -> "Código para recuperar tu PIN de Kredi+"
        RecoveryEmailKind.EMAIL_CHANGE -> "Código para cambiar tu correo de Kredi+"
    }

    private fun recoveryHtml(code: String, minutes: Long, kind: RecoveryEmailKind): String {
        val title = when (kind) {
            RecoveryEmailKind.PASSWORD -> "Recuperar contraseña"
            RecoveryEmailKind.PIN -> "Recuperar PIN"
            RecoveryEmailKind.EMAIL_CHANGE -> "Confirmar nuevo correo"
        }
        val action = when (kind) {
            RecoveryEmailKind.PASSWORD -> "crear una nueva contraseña"
            RecoveryEmailKind.PIN -> "crear un nuevo PIN de 6 dígitos"
            RecoveryEmailKind.EMAIL_CHANGE -> "confirmar este correo como tu nueva dirección en Kredi+"
        }
        return """
            <!doctype html>
            <html lang="es">
            <body style="margin:0;background:#f6f7f9;font-family:Arial,Helvetica,sans-serif;color:#161616">
              <div style="max-width:560px;margin:32px auto;background:#ffffff;border:1px solid #e5e7eb;border-radius:18px;padding:32px">
                <div style="font-size:28px;font-weight:800;margin-bottom:8px">Kredi<span style="color:#f47b20">+</span></div>
                <h1 style="font-size:22px;margin:18px 0 10px">$title</h1>
                <p style="line-height:1.55;color:#4b5563">Usa este código en Kredi+ para $action.</p>
                <div style="font-size:34px;font-weight:800;letter-spacing:8px;text-align:center;background:#fff7ed;border:1px solid #fed7aa;border-radius:14px;padding:20px;margin:24px 0">$code</div>
                <p style="line-height:1.55;color:#4b5563">El código vence en <strong>$minutes minutos</strong> y solo puede utilizarse una vez.</p>
                <p style="line-height:1.55;color:#6b7280;font-size:13px">Si no solicitaste este cambio, ignora este mensaje. Kredi+ nunca te pedirá este código por teléfono, mensajería o redes sociales.</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun maskEmail(email: String): String {
        val parts = email.split('@', limit = 2)
        if (parts.size != 2) return "***"
        val local = parts[0]
        val masked = when {
            local.length <= 1 -> "*"
            local.length == 2 -> "${local.first()}*"
            else -> "${local.first()}***${local.last()}"
        }
        return "$masked@${parts[1]}"
    }
}
