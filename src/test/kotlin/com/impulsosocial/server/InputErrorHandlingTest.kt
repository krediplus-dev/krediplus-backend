package com.impulsosocial.server

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InputErrorHandlingTest {
    private data class SampleRequest(val amount: Double)

    @Test
    fun `json mal formado se informa como 400 en espanol`() = testApplication {
        application {
            install(ContentNegotiation) { gson() }
            install(StatusPages) { configureInputErrors() }
            routing { post("/sample") { call.respond(call.receive<SampleRequest>()) } }
        }
        val response = client.post("/sample") {
            contentType(ContentType.Application.Json)
            setBody("{\"amount\":")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("INVALID_REQUEST"))
        assertFalse(response.bodyAsText().contains("Exception"))
    }

    @Test
    fun `un argumento invalido no filtra excepciones internas`() = testApplication {
        application {
            install(ContentNegotiation) { gson() }
            install(StatusPages) { configureInputErrors() }
            routing { get("/sample") { require(false) { "detalle-interno-privado" } } }
        }
        val response = client.get("/sample")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("VALIDATION_ERROR"))
        assertFalse(response.bodyAsText().contains("detalle-interno-privado"))
    }
}
