package com.impulsosocial.server.integrations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FcmRetryPolicyTest {
    @Test
    fun retriesOnlyAuthenticationRateLimitTimeoutAndServerFailures() {
        listOf(401, 408, 429, 500, 503, 599).forEach { assertTrue(FcmRetryPolicy.isRetryableStatus(it)) }
        listOf(200, 400, 403, 404, 422).forEach { assertFalse(FcmRetryPolicy.isRetryableStatus(it)) }
    }
}
