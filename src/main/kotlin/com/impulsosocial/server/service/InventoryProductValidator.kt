package com.impulsosocial.server.service

import java.text.Normalizer

/**
 * Valida nombres y categorías del inventario compartido.
 * Incluye alimentos, otros productos, tecnología y farmacia.
 */
object InventoryProductValidator {
    val foodClassifications = setOf(
        "Alimentos básicos", "Granos y cereales", "Harinas y pastas", "Aceites y grasas",
        "Lácteos", "Proteínas", "Enlatados", "Condimentos", "Frutas y hortalizas",
        "Bebidas", "Panadería", "Otros alimentos"
    )

    val technologyClassifications = setOf(
        "Teléfonos", "Tabletas", "Computadoras", "Componentes de PC",
        "Accesorios tecnológicos", "Electrodomésticos", "Audio y video"
    )

    val pharmacyClassifications = setOf(
        "Farmacia",
        "Analgésicos y antipiréticos",
        "Antibióticos",
        "Antialérgicos",
        "Antiinflamatorios",
        "Gastrointestinales",
        "Respiratorios",
        "Dermatológicos",
        "Vitaminas y suplementos",
        "Cardiovasculares",
        "Antidiabéticos",
        "Oftálmicos",
        "Antisépticos y desinfectantes",
        "Material médico",
        "Cuidado personal",
        "Otros productos farmacéuticos"
    )

    val otherClassifications = setOf(
        "Ropa", "Zapatos", "Camisas", "Pantalones", "Ropa íntima", "Juguetes"
    ) + technologyClassifications + pharmacyClassifications

    fun classificationOf(category: String): String {
        val parts = category.split(" · ").map(String::trim).filter(String::isNotBlank)
        return when {
            parts.firstOrNull() == "Otros productos" || parts.firstOrNull() == "Alimentos" -> parts.getOrNull(1).orEmpty()
            else -> parts.firstOrNull().orEmpty()
        }
    }

    fun validate(name: String, category: String) {
        val allowedPunctuation = setOf('-', '+', '/', '.', ',', '%', '(', ')')
        if (name.any { !(it.isLetterOrDigit() || it.isWhitespace() || it in allowedPunctuation) }) {
            throw AppException("El nombre del producto contiene caracteres no permitidos.")
        }
        val cleanName = normalize(name)
        if (cleanName.length !in 2..120) {
            throw AppException("El nombre del producto debe tener entre 2 y 120 caracteres.")
        }

        val parts = category.split(" · ").map(String::trim).filter(String::isNotBlank)
        if (parts.isEmpty()) throw AppException("La categoría del producto es obligatoria.")

        val mainCategory: String
        val classification: String
        when {
            parts.first() == "Alimentos" -> {
                mainCategory = "Alimentos"
                classification = parts.getOrNull(1).orEmpty()
            }
            parts.first() == "Otros productos" -> {
                mainCategory = "Otros productos"
                classification = parts.getOrNull(1).orEmpty()
            }
            parts.first() in foodClassifications -> {
                mainCategory = "Alimentos"
                classification = parts.first()
            }
            parts.first() in otherClassifications || parts.first() == "Tecnología" -> {
                mainCategory = "Otros productos"
                classification = parts.first()
            }
            else -> throw AppException("Selecciona una categoría válida de inventario.")
        }

        if (mainCategory == "Otros productos") {
            if (classification !in otherClassifications && classification != "Tecnología") {
                throw AppException("Indica una clasificación válida para Otros productos.")
            }
            if (parts.none { it.startsWith("Marca ") }) {
                throw AppException("Selecciona la marca del producto.")
            }
            return
        }

        if (classification !in foodClassifications) {
            throw AppException("Indica una clasificación válida para Alimentos.")
        }
        if (parts.none { it.startsWith("Marca ") }) {
            throw AppException("Selecciona la marca del producto.")
        }

        val rules = mapOf(
            "Alimentos básicos" to setOf("azucar","sal","cafe","papelon"),
            "Granos y cereales" to setOf("arroz","lenteja","caraota","frijol","garbanzo","avena","maiz","cereal"),
            "Harinas y pastas" to setOf("harina","pasta","espagueti","macarron","fideo","lasana"),
            "Aceites y grasas" to setOf("aceite","margarina","mantequilla","manteca"),
            "Lácteos" to setOf("leche","queso","yogur","suero"),
            "Proteínas" to setOf("pollo","carne","pescado","huevo","atun","sardina","cerdo","res","pavo","jamon"),
            "Enlatados" to setOf("enlatado","guisante","champinon"),
            "Condimentos" to setOf("pimienta","comino","oregano","adobo","cubito","condimento","sazonador"),
            "Frutas y hortalizas" to setOf("tomate","cebolla","papa","zanahoria","platano","manzana","naranja","yuca","ajo","pimenton","berenjena","lechuga","repollo","aguacate"),
            "Bebidas" to setOf("agua","jugo","refresco","malta","bebida","energizante"),
            "Panadería" to setOf("pan","galleta","torta","bizcocho","ponque"),
            "Otros alimentos" to emptySet()
        )
        val compoundOtherFoods = setOf(
            "salsa de tomate", "pasta de tomate", "pure de tomate", "ketchup", "mayonesa",
            "mostaza", "mermelada", "vinagre", "gelatina", "sopa instantanea"
        )
        if (classification == "Otros alimentos" && compoundOtherFoods.any(cleanName::contains)) return

        val aliases = mapOf(
            "arros" to "arroz", "azucarrr" to "azucar", "espaguetti" to "espagueti",
            "yogurt" to "yogur", "arina" to "harina", "aseite" to "aceite",
            "lentejas" to "lenteja", "caraotas" to "caraota", "frijoles" to "frijol",
            "fideos" to "fideo", "huevos" to "huevo", "guisantes" to "guisante",
            "champinones" to "champinon", "galletas" to "galleta"
        )
        val tokens = cleanName.split(" ").map { aliases[it] ?: it }
        val allKnown = rules.values.flatten().toSet()
        val matched = tokens.firstOrNull { it in allKnown }
        if (matched == null && classification != "Otros alimentos") {
            throw AppException("No reconocemos ese alimento. Revisa el nombre o utiliza Otros alimentos.")
        }
        if (matched != null && matched !in rules.getValue(classification) && classification != "Otros alimentos") {
            val expected = rules.entries.firstOrNull { matched in it.value }?.key
            throw AppException("El alimento no pertenece a $classification. Clasificación automática esperada: ${expected ?: "Otros alimentos"}.")
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.trim().lowercase(),
        Normalizer.Form.NFD
    )
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9+/%()., -]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
