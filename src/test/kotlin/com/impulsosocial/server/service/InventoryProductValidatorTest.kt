package com.impulsosocial.server.service

import kotlin.test.Test
import kotlin.test.assertFailsWith

class InventoryProductValidatorTest {
    @Test
    fun acceptsEveryPharmacyClassification() {
        InventoryProductValidator.pharmacyClassifications.forEach { classification ->
            InventoryProductValidator.validate(
                name = "Producto farmacéutico 500 mg",
                category = "Otros productos · $classification · Marca Genérico"
            )
        }
    }

    @Test
    fun acceptsPharmaceuticalNamesWithCompositionSymbols() {
        InventoryProductValidator.validate(
            name = "Amoxicilina + ácido clavulánico 500/125 mg",
            category = "Otros productos · Antibióticos · Marca Genérico"
        )
    }

    @Test
    fun keepsPharmacyCategoryWithinDatabaseLimit() {
        val category = "Otros productos · Antibióticos · Marca Genérico"
        kotlin.test.assertTrue(category.length <= 140)
        InventoryProductValidator.validate("Amoxicilina 500 mg", category)
    }

    @Test
    fun extractsPharmacyClassificationFromCompactCategory() {
        kotlin.test.assertEquals(
            "Antibióticos",
            InventoryProductValidator.classificationOf("Otros productos · Antibióticos · Marca Genérico")
        )
    }

    @Test
    fun rejectsUnknownOtherProductClassification() {
        assertFailsWith<AppException> {
            InventoryProductValidator.validate(
                name = "Producto",
                category = "Otros productos · Clasificación inexistente · Marca Genérico"
            )
        }
    }
}
