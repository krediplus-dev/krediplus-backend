package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfileEditingContractTest {
    private fun source(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `perfil autenticado deja telefono y ubicacion solo lectura y permite correo verificado`() {
        val application = source("src/main/kotlin/com/impulsosocial/server/Application.kt")
        val service = source("src/main/kotlin/com/impulsosocial/server/service/AppService.kt")
        val models = source("src/main/kotlin/com/impulsosocial/server/model/ApiModels.kt")
        val schema = source("src/main/resources/db/schema.sql")

        assertTrue(application.contains("patch(\"/me/profile/contact\")"))
        assertTrue(application.contains("patch(\"/me/profile/location\")"))
        assertTrue(application.contains("El teléfono registrado es de solo consulta"))
        assertTrue(application.contains("La ubicación registrada es de solo consulta"))
        assertTrue(application.contains("post(\"/me/email-change/request\")"))
        assertTrue(application.contains("post(\"/me/email-change/confirm\")"))

        assertTrue(service.contains("fun updateProfileContact"))
        assertTrue(service.contains("fun updateProfileLocation"))
        assertTrue(service.contains("fun requestEmailChange"))
        assertTrue(service.contains("fun confirmEmailChange"))
        assertTrue(service.contains("consumeEmailChangeCode"))
        assertTrue(service.contains("ensureProfilePhoneAvailable"))

        assertTrue(models.contains("data class ProfileContactUpdateRequest"))
        assertTrue(models.contains("data class ProfileLocationUpdateRequest"))
        assertTrue(models.contains("data class EmailChangeConfirmRequest"))
        assertTrue(schema.contains("'EMAIL_CHANGE'"))
        assertTrue(schema.contains("VALUES (94,"))
    }
}
