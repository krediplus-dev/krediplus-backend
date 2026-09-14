package com.impulsosocial.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductMetadataPolicyTest {
    @Test
    fun extractsLegacyPharmacyDetailsFromOversizedCategory() {
        val legacyCategory = listOf(
            "Otros productos",
            "Antibióticos",
            "Marca Genérico",
            "Principio activo: Amoxicilina + ácido clavulánico",
            "Composición: 500 mg/125 mg",
            "Presentación: tabletas x 14",
            "Vía: oral",
            "Venta: con récipe",
            "Registro sanitario: MPPS-123456",
            "Vence: 12/2028"
        ).joinToString(" · ")

        val normalized = ProductMetadataPolicy.normalize(legacyCategory, null)

        assertEquals("Otros productos · Antibióticos · Marca Genérico", normalized.category)
        assertTrue(normalized.category.length <= 140)
        assertTrue(normalized.details.contains("Principio activo: Amoxicilina"))
        assertTrue(normalized.details.contains("Vence: 12/2028"))
    }

    @Test
    fun keepsNewSeparatedDetails() {
        val normalized = ProductMetadataPolicy.normalize(
            "Otros productos · Analgésicos y antipiréticos · Marca Genérico",
            "Principio activo: Acetaminofén · Composición: 500 mg"
        )
        assertEquals("Otros productos · Analgésicos y antipiréticos · Marca Genérico", normalized.category)
        assertEquals("Principio activo: Acetaminofén · Composición: 500 mg", normalized.details)
    }
}
