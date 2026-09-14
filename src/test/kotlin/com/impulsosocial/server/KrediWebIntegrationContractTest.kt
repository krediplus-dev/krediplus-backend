package com.impulsosocial.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class KrediWebIntegrationContractTest {
    private fun projectFile(relative: String): String = Files.readString(Path.of(relative))

    @Test
    fun `el visor cloudflare tiene contrato publico y diagnostico`() {
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        assertTrue(application.contains("get(\"/api/v1/explorer/health\")"))
        assertTrue(application.contains("get(\"/api/v1/explorer/transactions\")"))
        assertTrue(application.contains("get(\"/api/v1/explorer/events\")"))
        assertTrue(application.contains("X-Kredi-Explorer"))
        assertTrue(application.contains("install(CORS)"))
        assertTrue(application.contains("anyHost()"))
        assertTrue(application.contains("exposeHeader(\"X-Kredi-Explorer\")"))
        assertTrue(application.contains("options(\"/api/v1/explorer/transactions\")"))
        assertTrue(application.contains("options(\"/api/v1/explorer/events\")"))
        assertTrue(!application.contains("endsWith(\".pages.dev\")"))
    }


    @Test
    fun `cors del visor depende del plugin de ktor sin headers duplicados`() {
        val application = projectFile("src/main/kotlin/com/impulsosocial/server/Application.kt")
        assertTrue(application.contains("install(CORS)"))
        assertTrue(application.contains("anyHost()"))
        assertTrue(application.contains("allowHeader(\"Last-Event-ID\")"))
        assertTrue(application.contains("exposeHeader(\"X-Kredi-Explorer\")"))
        assertFalse(application.contains("response.header(\"Access-Control-Allow-Origin\""))
    }
}
