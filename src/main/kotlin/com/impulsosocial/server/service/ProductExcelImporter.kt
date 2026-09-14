package com.impulsosocial.server.service

import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.util.Locale

internal data class ProductExcelRawRow(
    val sheetName: String,
    val rowNumber: Int,
    val values: Map<String, String>
)

internal data class ProductExcelParsedWorkbook(
    val rows: List<ProductExcelRawRow>,
    val ignoredSheets: List<String>
)

/**
 * Lector tolerante para la carga masiva de productos del Almacenista.
 * El nombre del archivo, el nombre de la hoja y el orden de las columnas no importan.
 * Se permiten columnas adicionales y se reconocen variantes habituales de encabezados.
 */
internal object ProductExcelWorkbookParser {
    private const val MAX_ROWS = 2_000
    private const val HEADER_SCAN_ROWS = 12

    private val canonicalAliases: Map<String, Set<String>> = mapOf(
        "name" to setOf("producto", "nombre", "nombre del producto", "descripcion del producto"),
        "mainCategory" to setOf("categoria principal", "categoria", "tipo de producto"),
        "classification" to setOf("clasificacion", "subcategoria", "clasificacion del producto"),
        "brand" to setOf("marca", "fabricante"),
        "unit" to setOf("unidad", "presentacion", "unidad de medida"),
        "pricingMode" to setOf("forma de precio", "modalidad de precio", "tipo de precio", "pricing mode"),
        "priceUsd" to setOf("precio usd", "precio (usd)", "precio en usd", "precio dolares", "precio en dolares", "precio"),
        "stock" to setOf("existencia", "stock", "cantidad", "cantidad inicial"),
        "minimumStock" to setOf("existencia minima", "stock minimo", "minimum stock", "minimo"),
        "status" to setOf("estado", "estatus", "activo"),
        "details" to setOf("detalles", "ficha tecnica", "especificaciones", "detalle tecnico", "descripcion tecnica"),
        "combinedCategory" to setOf("categoria credicash", "categoria completa", "clasificacion credicash")
    ).mapValues { (_, aliases) -> aliases.map(::normalizeHeader).toSet() }

    private val aliasToCanonical = buildMap<String, String> {
        canonicalAliases.forEach { (canonical, aliases) -> aliases.forEach { put(it, canonical) } }
    }

    fun parse(bytes: ByteArray): ProductExcelParsedWorkbook {
        if (bytes.isEmpty()) throw AppException("El archivo Excel está vacío.")
        val rows = mutableListOf<ProductExcelRawRow>()
        val ignored = mutableListOf<String>()
        try {
            WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
                val formatter = DataFormatter(Locale.forLanguageTag("es-VE"), true)
                val evaluator = workbook.creationHelper.createFormulaEvaluator()
                for (sheetIndex in 0 until workbook.numberOfSheets) {
                    val sheet = workbook.getSheetAt(sheetIndex)
                    val sheetName = sheet.sheetName.orEmpty().trim().ifBlank { "Hoja ${sheetIndex + 1}" }
                    if (isDocumentationSheet(sheetName)) continue
                    val headerRowIndex = findHeaderRow(sheet, formatter, evaluator)
                    if (headerRowIndex == null) {
                        if (hasMeaningfulContent(sheet, formatter, evaluator)) {
                            ignored += "$sheetName: contiene datos, pero no se reconocieron suficientes encabezados de productos."
                        }
                        continue
                    }
                    val headerRow = sheet.getRow(headerRowIndex) ?: continue
                    val columns = linkedMapOf<Int, String>()
                    for (cellIndex in headerRow.firstCellNum.toInt().coerceAtLeast(0) until headerRow.lastCellNum.toInt().coerceAtLeast(0)) {
                        val raw = formatter.formatCellValue(headerRow.getCell(cellIndex), evaluator).trim()
                        aliasToCanonical[normalizeHeader(raw)]?.let { canonical ->
                            if (canonical !in columns.values) columns[cellIndex] = canonical
                        }
                    }
                    for (rowIndex in (headerRowIndex + 1)..sheet.lastRowNum) {
                        val row = sheet.getRow(rowIndex) ?: continue
                        val values = linkedMapOf<String, String>()
                        var nonBlank = false
                        columns.forEach { (cellIndex, canonical) ->
                            val value = formatter.formatCellValue(row.getCell(cellIndex), evaluator).trim()
                            values[canonical] = value
                            if (value.isNotBlank()) nonBlank = true
                        }
                        if (!nonBlank || values.values.any { it.equals("Sin registros", true) }) continue
                        rows += ProductExcelRawRow(sheetName, rowIndex + 1, values)
                        if (rows.size > MAX_ROWS) throw AppException("La importación admite hasta $MAX_ROWS productos por archivo.")
                    }
                }
            }
        } catch (error: AppException) {
            throw error
        } catch (_: Throwable) {
            throw AppException("No fue posible leer el archivo. Verifica que sea un Excel .xlsx válido y no esté protegido con contraseña.")
        }
        if (rows.isEmpty()) {
            throw AppException("No se encontraron productos importables. El nombre del archivo no importa; verifica los encabezados y el contenido del .xlsx.")
        }
        return ProductExcelParsedWorkbook(rows, ignored.distinct())
    }

    private fun findHeaderRow(sheet: org.apache.poi.ss.usermodel.Sheet, formatter: DataFormatter, evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator): Int? {
        val last = minOf(sheet.lastRowNum, HEADER_SCAN_ROWS - 1)
        var best: Pair<Int, Int>? = null
        for (rowIndex in 0..last) {
            val row = sheet.getRow(rowIndex) ?: continue
            var recognized = 0
            for (cellIndex in row.firstCellNum.toInt().coerceAtLeast(0) until row.lastCellNum.toInt().coerceAtLeast(0)) {
                if (normalizeHeader(formatter.formatCellValue(row.getCell(cellIndex), evaluator)) in aliasToCanonical) recognized++
            }
            if (recognized >= 4 && (best == null || recognized > best.second)) best = rowIndex to recognized
        }
        return best?.first
    }

    private fun isDocumentationSheet(name: String): Boolean = normalizeHeader(name) in setOf(
        "leeme", "leer", "instrucciones", "readme", "ayuda", "notas", "informacion", "info", "clasificaciones"
    )

    private fun hasMeaningfulContent(sheet: org.apache.poi.ss.usermodel.Sheet, formatter: DataFormatter, evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator): Boolean {
        var cells = 0
        for (rowIndex in 0..minOf(sheet.lastRowNum, 30)) {
            val row = sheet.getRow(rowIndex) ?: continue
            for (cellIndex in row.firstCellNum.toInt().coerceAtLeast(0) until row.lastCellNum.toInt().coerceAtLeast(0)) {
                if (formatter.formatCellValue(row.getCell(cellIndex), evaluator).trim().isNotBlank() && ++cells >= 4) return true
            }
        }
        return false
    }

    internal fun normalizeHeader(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
