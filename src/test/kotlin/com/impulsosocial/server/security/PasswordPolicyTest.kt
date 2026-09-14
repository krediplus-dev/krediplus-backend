package com.impulsosocial.server.security

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class PasswordPolicyTest {
    @Test
    fun acceptsStrongPassword() {
        assertNull(PasswordPolicy.validationError("ClaveSegura1!", "maria", "maria@example.com"))
    }

    @Test
    fun rejectsMissingSecurityComponents() {
        assertNotNull(PasswordPolicy.validationError("abcdefgh"))
        assertNotNull(PasswordPolicy.validationError("ABCDEFGH1!"))
        assertNotNull(PasswordPolicy.validationError("Abcdefgh!"))
        assertNotNull(PasswordPolicy.validationError("Abcdefgh1"))
    }

    @Test
    fun rejectsUsernameInsidePassword() {
        assertNotNull(PasswordPolicy.validationError("Maria123!x", "maria", "otro@example.com"))
    }
}
