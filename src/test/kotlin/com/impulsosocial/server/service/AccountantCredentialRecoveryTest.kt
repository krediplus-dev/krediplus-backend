package com.impulsosocial.server.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountantCredentialRecoveryTest {
    @Test
    fun allowsRecoveryOnlyWhenProtectedPasswordMatches() {
        assertTrue(shouldRecoverAccountantCredentials("ClaveSegura1!", "ClaveSegura1!", true))
        assertFalse(shouldRecoverAccountantCredentials("OtraClave1!", "ClaveSegura1!", true))
    }

    @Test
    fun doesNotRecoverWithoutCompleteBootstrapConfiguration() {
        assertFalse(shouldRecoverAccountantCredentials("ClaveSegura1!", "ClaveSegura1!", false))
        assertFalse(shouldRecoverAccountantCredentials(null, "ClaveSegura1!", true))
        assertFalse(shouldRecoverAccountantCredentials("ClaveSegura1!", "", true))
    }
}
