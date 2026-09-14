package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailPasswordRecoveryContractTest {
    private fun source(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `recuperacion usa correo corporativo y solo endpoints actuales`() {
        val application = source("src/main/kotlin/com/impulsosocial/server/Application.kt")
        val service = source("src/main/kotlin/com/impulsosocial/server/service/AppService.kt")
        val config = source("src/main/kotlin/com/impulsosocial/server/config/AppConfig.kt")
        val smtp = source("src/main/kotlin/com/impulsosocial/server/integrations/SmtpEmailService.kt")

        assertTrue(application.contains("post(\"/recovery/identify\")"))
        assertTrue(application.contains("post(\"/password-recovery/request\")"))
        assertTrue(application.contains("post(\"/password-recovery/reset\")"))
        assertTrue(application.contains("post(\"/pin-recovery/request\")"))
        assertTrue(application.contains("post(\"/pin-recovery/reset\")"))
        assertFalse(application.contains("post(\"/forgot-password\")"))
        assertFalse(application.contains("post(\"/reset-password\")"))

        assertTrue(service.contains("emailService.sendPasswordResetCode"))
        assertTrue(service.contains("VALUES (?,'EMAIL'"))
        assertTrue(service.contains("requestRecoveryCode(request, \"PASSWORD_RESET\""))
        assertTrue(smtp.contains("fun sendPasswordResetCode"))
        assertTrue(config.contains("EMAIL_RECOVERY_ENABLED"))
        assertTrue(config.contains("RESEND_API_KEY"))
        assertTrue(config.contains("SMTP_HOST"))
        assertTrue(config.contains("SMTP_PASS"))
        assertTrue(smtp.contains("https://api.resend.com/emails") || config.contains("https://api.resend.com/emails"))
        assertTrue(smtp.contains("Authorization"))
    }

    @Test
    fun `backend no contiene canal de recuperacion anterior`() {
        val root = Path.of("src")
        val legacyTerm = "tele" + "gram"
        Files.walk(root).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { file ->
                val text = runCatching { Files.readString(file) }.getOrDefault("")
                assertFalse(text.contains(legacyTerm, ignoreCase = true), "Referencia heredada en $file")
            }
        }
    }
}
