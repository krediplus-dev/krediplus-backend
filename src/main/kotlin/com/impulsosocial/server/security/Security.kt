package com.impulsosocial.server.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.impulsosocial.server.config.AppConfig
import de.mkammerer.argon2.Argon2Factory
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import java.util.UUID


object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64
    const val REQUIREMENTS = "Mínimo 8 caracteres, con una mayúscula, una minúscula, un número y un carácter especial."

    private val blockedPasswords = setOf(
        "password", "password123", "password123!", "qwerty123!", "12345678!aa",
        "credicash1!", "administrador1!", "admin123!a", "contraseña1!"
    )

    fun validationError(password: String, username: String? = null, email: String? = null): String? {
        if (password.length < MIN_LENGTH) return "La contraseña debe tener al menos $MIN_LENGTH caracteres."
        if (password.length > MAX_LENGTH) return "La contraseña no puede superar $MAX_LENGTH caracteres."
        if (password.none(Char::isUpperCase)) return "La contraseña debe incluir al menos una letra mayúscula."
        if (password.none(Char::isLowerCase)) return "La contraseña debe incluir al menos una letra minúscula."
        if (password.none(Char::isDigit)) return "La contraseña debe incluir al menos un número."
        if (password.none { !it.isLetterOrDigit() && !it.isWhitespace() }) return "La contraseña debe incluir al menos un carácter especial, por ejemplo ! @ # \$ % & *."
        val normalized = password.lowercase()
        if (normalized in blockedPasswords) return "La contraseña es demasiado fácil de adivinar. Usa una combinación diferente."
        val usernameValue = username?.trim()?.lowercase().orEmpty()
        if (usernameValue.length >= 4 && normalized.contains(usernameValue)) return "La contraseña no debe contener tu nombre de usuario."
        val emailName = email?.substringBefore('@')?.trim()?.lowercase().orEmpty()
        if (emailName.length >= 4 && normalized.contains(emailName)) return "La contraseña no debe contener la parte principal de tu correo."
        return null
    }
}

class PasswordSecurity {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    fun hash(secret: String): String {
        val chars = secret.toCharArray()
        return try {
            // OWASP Argon2id baseline: >=19 MiB, >=2 iterations, parallelism 1.
            argon2.hash(2, 19_456, 1, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }

    fun verify(hash: String, secret: String): Boolean {
        val chars = secret.toCharArray()
        return try {
            argon2.verify(hash, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }
}

class JwtService(private val config: AppConfig) {
    init {
        require(config.jwtSecret.toByteArray(StandardCharsets.UTF_8).size >= 32) {
            "JWT_SECRET debe contener al menos 32 bytes aleatorios."
        }
    }

    val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    fun createAccessToken(userId: Long, role: String, sessionId: UUID, client: String): String {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(config.jwtAccessTokenTtlMinutes * 60L)
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(userId.toString())
            .withClaim("userId", userId)
            .withClaim("role", Roles.canonical(role))
            .withClaim("sessionId", sessionId.toString())
            .withClaim("client", client.trim().uppercase())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
    }
}

data class AuthenticatedUser(val id: Long, val role: String)
