package com.impulsosocial.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletNotificationFactoryTest {
    @Test
    fun receivedBalanceNotificationContainsAmountBalanceReferenceAndType() {
        val notification = WalletNotificationFactory.received(25.5, 80.75, "CRED-123")
        assertEquals("CREDIMPULSO_WALLET_TRANSFER", notification.type)
        assertEquals("25.50", notification.data["amountUsd"])
        assertEquals("80.75", notification.data["availableBalanceUsd"])
        assertEquals("CRED-123", notification.data["reference"])
        assertTrue(notification.body.contains("US$ 25.50"))
        assertTrue(notification.body.contains("US$ 80.75"))
    }

    @Test
    fun accountantFundingUsesWalletEventAndResultingBalance() {
        val notification = WalletNotificationFactory.accountantBudgetAssigned(150.0, 640.25, "ASG-321")
        assertEquals("ACCOUNTANT_BUDGET_ASSIGNED", notification.type)
        assertEquals("ADMIN_CREDIT_WALLET", notification.data["destination"])
        assertTrue(notification.body.contains("US$ 150.00"))
        assertTrue(notification.body.contains("US$ 640.25"))
        assertTrue(notification.body.contains("ASG-321"))
    }
    @Test
    fun approvedCreditIncludesUsdVesAvailableBalanceAndReference() {
        val notification = WalletNotificationFactory.creditApproved(40.0, 5800.0, 95.5, "APR-123")
        assertEquals("CREDIT_REQUEST_APPROVED", notification.type)
        assertEquals("5800.00", notification.data["amountBs"])
        assertEquals("95.50", notification.data["availableBalanceUsd"])
        assertTrue(notification.body.contains("US$ 40.00"))
        assertTrue(notification.body.contains("Bs 5800.00"))
        assertTrue(notification.body.contains("US$ 95.50"))
        assertTrue(notification.body.contains("APR-123"))
    }

}
