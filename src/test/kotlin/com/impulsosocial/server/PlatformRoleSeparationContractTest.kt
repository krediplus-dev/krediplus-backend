package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformRoleSeparationContractTest {
    private fun projectFile(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `mobile solo autentica beneficiarios y desktop solo roles internos`() {
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        assertTrue(application.contains("post(\"/mobile/login\")"))
        assertTrue(application.contains("post(\"/desktop/login\")"))
        assertTrue(application.contains("post(\"/mobile/verify-pin\")"))
        assertTrue(application.contains("post(\"/desktop/verify-pin\")"))
        assertTrue(application.contains("Roles.BENEFICIARY"))
        assertTrue(application.contains("Roles.ADMIN, Roles.ACCOUNTANT, Roles.WAREHOUSE"))
        assertTrue(application.contains("jwt.createAccessToken(userId, role, sessionId, \"MOBILE\")"))
        assertTrue(application.contains("jwt.createAccessToken(userId, role, sessionId, \"DESKTOP\")"))
    }

    @Test
    fun `jwt conserva el canal y la validacion lo hace obligatorio`() {
        val security = projectFile("src/main/kotlin/com/impulsosocial/server/security/Security.kt")
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        assertTrue(security.contains("withClaim(\"client\""))
        assertTrue(application.contains("tokenClient == \"MOBILE\""))
        assertTrue(application.contains("tokenClient == \"DESKTOP\""))
        assertFalse(application.contains("/auth/login\""))
        assertFalse(application.contains("/auth/verify-pin\""))
    }
}
