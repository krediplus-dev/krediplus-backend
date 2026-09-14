package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class AccountantRegistrationApprovalContractTest {
    private fun source(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `accountant expone el endpoint que usa Android para aprobar registros`() {
        val application = source("src/main/kotlin/com/impulsosocial/server/Application.kt")
        val accountantRoute = application.substringAfter("route(\"/accountant\")")
            .substringBefore("route(\"/accounts/{id}/suspension\")")

        assertTrue(accountantRoute.contains("post(\"/users/{id}/verification\")"))
        assertTrue(accountantRoute.contains("service.reviewUserVerification(call.userId(), id, call.receive())"))
        assertTrue(accountantRoute.contains("post(\"/verifications/{id}/review\")"))
    }

    @Test
    fun `accountant incluye permiso funcional de revision de usuarios`() {
        val service = source("src/main/kotlin/com/impulsosocial/server/service/AppService.kt")
        val accountantPermissions = service.substringAfter("Roles.ACCOUNTANT -> listOf(")
            .substringBefore(")")
        assertTrue(accountantPermissions.contains("\"REVIEW_USERS\""))
    }
}
