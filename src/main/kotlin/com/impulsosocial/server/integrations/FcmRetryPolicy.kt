package com.impulsosocial.server.integrations

/** Reglas puras para reintentar únicamente errores temporales de Firebase y red. */
object FcmRetryPolicy {
    private val delaysMillis = longArrayOf(500L, 1_500L, 3_000L)

    fun isRetryableStatus(statusCode: Int): Boolean =
        statusCode == 401 || statusCode == 408 || statusCode == 429 || statusCode in 500..599

    fun delayForAttempt(attempt: Int): Long? = delaysMillis.getOrNull(attempt)
}
