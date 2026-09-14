package com.impulsosocial.server

import com.impulsosocial.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond

/** Los errores de formato del cliente no deben mostrarse como averías del servidor. */
internal fun StatusPagesConfig.configureInputErrors() {
    exception<BadRequestException> { call, _ ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(
            "Los datos enviados no tienen un formato válido. Revisa los campos y vuelve a intentarlo.",
            "INVALID_REQUEST"
        ))
    }
    exception<ContentTransformationException> { call, _ ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(
            "No fue posible leer la solicitud. Envía los datos con el formato indicado.",
            "INVALID_REQUEST"
        ))
    }
    exception<IllegalArgumentException> { call, _ ->
        // AppException tiene su propio manejador con la validación específica en español.
        // Las excepciones de bibliotecas se sanitizan para no exponer datos o detalles internos.
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(
            "Uno de los valores enviados no es válido. Revisa montos, fechas e identificadores.",
            "VALIDATION_ERROR"
        ))
    }
}
