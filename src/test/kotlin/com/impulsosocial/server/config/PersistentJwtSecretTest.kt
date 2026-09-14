package com.impulsosocial.server.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistentJwtSecretTest {
    @Test
    fun `la clave autogenerada se conserva entre reinicios`() {
        val root = Files.createTempDirectory("kredi-jwt-test")
        try {
            val secretFile = root.resolve("secrets").resolve("jwt-secret")
            val first = loadOrCreatePersistentJwtSecret(secretFile)
            val second = loadOrCreatePersistentJwtSecret(secretFile)

            assertTrue(first.toByteArray(Charsets.UTF_8).size >= 32)
            assertEquals(first, second)
            assertEquals(first, Files.readString(secretFile).trim())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
