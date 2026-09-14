package com.impulsosocial.server.security

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

data class RateLimitDecision(val allowed: Boolean, val retryAfterSeconds: Long = 0)

class RequestRateLimiter(
    private val maxRequests: Int,
    windowSeconds: Long,
    private val maxTrackedKeys: Int = 10_000,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val windowMillis = windowSeconds.coerceAtLeast(1) * 1_000L
    private val attempts = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun check(key: String): RateLimitDecision {
        val now = nowMillis()
        if (attempts.size >= maxTrackedKeys) removeExpired(now)
        val queue = attempts.computeIfAbsent(key.take(220)) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && now - queue.first() >= windowMillis) queue.removeFirst()
            if (queue.size >= maxRequests) {
                val remainingMillis = (windowMillis - (now - queue.first())).coerceAtLeast(1)
                return RateLimitDecision(false, ceil(remainingMillis / 1_000.0).toLong())
            }
            queue.addLast(now)
            return RateLimitDecision(true)
        }
    }

    private fun removeExpired(now: Long) {
        attempts.entries.removeIf { (_, queue) ->
            synchronized(queue) {
                while (queue.isNotEmpty() && now - queue.first() >= windowMillis) queue.removeFirst()
                queue.isEmpty()
            }
        }
        if (attempts.size >= maxTrackedKeys) attempts.keys.take(attempts.size - maxTrackedKeys + 1).forEach(attempts::remove)
    }
}
