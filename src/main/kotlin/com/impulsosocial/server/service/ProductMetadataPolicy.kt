package com.impulsosocial.server.service

data class NormalizedProductMetadata(
    val category: String,
    val details: String
)

/**
 * Mantiene la categoría dentro del contrato de base de datos y extrae los datos técnicos que
 * clientes anteriores anexaban a la categoría usando el separador " · ".
 */
object ProductMetadataPolicy {
    fun normalize(category: String, details: String?): NormalizedProductMetadata {
        val parts = category.split(" · ")
            .map(String::trim)
            .filter(String::isNotBlank)

        val structural = mutableListOf<String>()
        val extractedDetails = mutableListOf<String>()

        parts.forEachIndexed { index, part ->
            when {
                index <= 2 -> structural += part
                part.startsWith("Voltaje ", ignoreCase = true) -> structural += part
                else -> extractedDetails += part
            }
        }

        val compactCategory = structural.joinToString(" · ").trim()
        val combinedDetails = buildList {
            details.orEmpty().trim().takeIf(String::isNotBlank)?.let(::add)
            extractedDetails.joinToString(" · ").trim().takeIf(String::isNotBlank)?.let(::add)
        }.distinct().joinToString(" · ")

        return NormalizedProductMetadata(compactCategory, combinedDetails)
    }
}
