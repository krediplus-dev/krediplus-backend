package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RailwayRuntimeConfigurationTest {
    private fun projectFile(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `railway usa docker y readiness de aplicacion`() {
        val railway = projectFile("railway.toml")
        val dockerfile = projectFile("Dockerfile")
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        assertTrue(railway.contains("builder = \"DOCKERFILE\""))
        assertTrue(railway.contains("healthcheckPath = \"/health/ready\""))
        assertTrue(dockerfile.contains("EXPOSE 8080"))
        assertTrue(dockerfile.contains("ENV PORT=8080"))
        assertTrue(application.contains("get(\"/health/live\")"))
        assertTrue(application.contains("get(\"/health/ready\")"))
    }

    @Test
    fun `railway prioriza database url y conserva alternativas postgres`() {
        val config = projectFile("src/main/kotlin/com/impulsosocial/server/config/AppConfig.kt")
        val databaseUrlIndex = config.indexOf("\"DATABASE_URL\",")
        val customIndex = config.indexOf("\"CREDICASH_DATABASE_URL\",")
        val pgIndex = config.indexOf("databaseEnvironmentFromPgVariables()")
        assertTrue(databaseUrlIndex >= 0, "Falta soporte para DATABASE_URL")
        assertTrue(customIndex > databaseUrlIndex, "DATABASE_URL debe ser la opción principal")
        assertTrue(pgIndex > customIndex, "PG* debe conservarse como alternativa")
        assertTrue(config.contains("RAILWAY_PUBLIC_DOMAIN"), "Debe reconocer el dominio público de Railway")
        assertTrue(config.contains("value.replace(\"+\", \"%2B\")"), "El decodificador no debe convertir + en espacio")
    }

    @Test
    fun `uploads apuntan al volumen persistente documentado`() {
        val dockerfile = projectFile("Dockerfile")
        val example = projectFile(".env.example")
        val readme = projectFile("README.md")
        assertTrue(dockerfile.contains("UPLOAD_DIR=/data/uploads"))
        assertTrue(example.contains("UPLOAD_DIR=./data/uploads"))
        assertTrue(readme.contains("/data/uploads"))
        assertTrue(readme.contains("/data"))
    }

    @Test
    fun `el apagado cierra listeners antes del pool`() {
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        val notifier = projectFile("src/main/kotlin/com/impulsosocial/server/LedgerRealtimeNotifier.kt")
        val stopIndex = application.indexOf("ledgerRealtimeNotifier.stop()")
        val databaseCloseIndex = application.indexOf("database.close()")
        assertTrue(stopIndex >= 0)
        assertTrue(databaseCloseIndex > stopIndex)
        assertTrue(notifier.contains("activeConnection.getAndSet(null)"))
        assertTrue(notifier.contains("listenerJob.getAndSet(null)?.cancel()"))
    }

    @Test
    fun `listeners esperan a migraciones listas`() {
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        val initIndex = application.indexOf("initializeDatabaseInBackground(")
        val listenerIndex = application.indexOf("ledgerRealtimeNotifier.start(startupScope)", initIndex)
        assertTrue(initIndex >= 0)
        assertTrue(listenerIndex > initIndex)
        assertTrue(application.contains("bootstrapAccountantValidationError()"))
    }

    @Test
    fun `la version estable es coherente en compilacion runtime docker y openapi`() {
        val build = projectFile("build.gradle.kts")
        val runtime = projectFile("src/main/kotlin/com/impulsosocial/server/Version.kt")
        val dockerfile = projectFile("Dockerfile")
        val openApi = projectFile("src/main/resources/openapi/credicash.yaml")

        val buildVersion = Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"").find(build)?.groupValues?.get(1)
        val runtimeVersion = Regex("CREDICASH_APP_VERSION\\s*=\\s*\"([^\"]+)\"").find(runtime)?.groupValues?.get(1)
        val dockerVersion = Regex("CREDICASH_BACKEND_VERSION=\"([^\"]+)\"").find(dockerfile)?.groupValues?.get(1)
        val openApiVersion = Regex("(?m)^\\s*version:\\s*([^\\s]+)").find(openApi)?.groupValues?.get(1)

        assertNotNull(buildVersion, "No se pudo leer la version de build.gradle.kts")
        assertNotNull(runtimeVersion, "No se pudo leer CREDICASH_APP_VERSION")
        assertNotNull(dockerVersion, "No se pudo leer CREDICASH_BACKEND_VERSION del Dockerfile")
        assertNotNull(openApiVersion, "No se pudo leer la version del OpenAPI")
        assertEquals(buildVersion, runtimeVersion, "Gradle y runtime deben publicar la misma version")
        assertEquals(buildVersion, dockerVersion, "Gradle y Docker deben publicar la misma version")
        assertEquals(buildVersion, openApiVersion, "Gradle y OpenAPI deben publicar la misma version")
    }

    @Test
    fun `sesiones y desafios de pin tienen vencimiento y limite`() {
        val config = projectFile("src/main/kotlin/com/impulsosocial/server/config/AppConfig.kt")
        val security = projectFile("src/main/kotlin/com/impulsosocial/server/security/Security.kt")
        val service = projectFile("src/main/kotlin/com/impulsosocial/server/service/AppService.kt")
        val schema = projectFile("src/main/resources/db/schema.sql")
        assertTrue(config.contains("JWT_ACCESS_TOKEN_TTL_MINUTES"))
        assertTrue(config.contains("PERSISTENT_SESSION_TTL_DAYS"))
        assertTrue(security.contains("withExpiresAt"))
        assertTrue(service.contains("attempts=attempts+1"))
        assertTrue(schema.contains("attempts INTEGER NOT NULL DEFAULT 0"))
    }

    @Test
    fun `api monetaria del contador permanece retirada y runtime protegido`() {
        val openApi = projectFile("src/main/resources/openapi/credicash.yaml")
        val config = projectFile("src/main/kotlin/com/impulsosocial/server/config/AppConfig.kt")
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        assertTrue(Regex("(?m)^\\s*version:\\s*[^\\s]+$").containsMatchIn(openApi))
        assertTrue(!openApi.contains("/accountant/budget/dashboard:"), "El presupuesto monetario retirado no debe publicarse como API activa.")
        assertTrue(!openApi.contains("/accountant/budget/commitments:"), "Los compromisos monetarios retirados no deben publicarse como API activa.")
        assertTrue(application.contains("get(\"/budget/dashboard\") { call.requireAccountant(service); call.respond(HttpStatusCode.Gone"))
        assertTrue(application.contains("post(\"/budget/commitments\") { call.requireAccountant(service); call.respond(HttpStatusCode.Gone"))
        assertTrue(config.contains("REQUIRE_STABLE_JWT_SECRET"))
        assertTrue(config.contains("CORS_ALLOWED_ORIGINS"))
        assertTrue(application.contains("RequestRateLimiter"))
        assertTrue(application.contains("callIdMdc(\"requestId\")"))
    }
}
