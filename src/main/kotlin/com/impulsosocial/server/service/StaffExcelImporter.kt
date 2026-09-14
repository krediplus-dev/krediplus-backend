package com.impulsosocial.server.service

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal data class StaffExcelRawRow(
    val sheetName: String,
    val rowNumber: Int,
    val inferredRole: String?,
    val values: Map<String, String>
)

internal data class StaffExcelParsedWorkbook(
    val rows: List<StaffExcelRawRow>,
    val ignoredSheets: List<String>,
    val detectedSheets: Int
)

/**
 * Lector tolerante de nóminas .xlsx.
 *
 * El nombre físico del archivo no participa en ninguna decisión. Solo se inspecciona el
 * contenido del libro. Los encabezados pueden venir en cualquier orden y las columnas
 * adicionales se ignoran. También se admite que el rol venga en una columna o se infiera
 * de hojas llamadas Administradores, Almacenistas o Contador.
 */
internal object StaffExcelWorkbookParser {
    private const val MAX_ROWS = 2_000
    private const val HEADER_SCAN_ROWS = 12

    private val canonicalAliases: Map<String, Set<String>> = mapOf(
        "role" to setOf("rol", "tipo de cuenta", "tipo cuenta", "perfil", "rol operativo"),
        "fullName" to setOf("nombre completo", "nombres y apellidos", "nombre y apellido", "nombre completo del trabajador"),
        "firstName" to setOf("primer nombre", "nombre", "nombres"),
        "middleName" to setOf("segundo nombre", "nombre medio"),
        "lastName" to setOf("primer apellido", "apellido", "apellidos"),
        "secondLastName" to setOf("segundo apellido"),
        "username" to setOf("usuario", "nombre de usuario", "usuario operativo"),
        "email" to setOf("correo electronico", "correo", "e-mail", "email", "correo operativo"),
        "phone" to setOf("telefono", "celular", "telefono celular", "numero de telefono"),
        "birthDate" to setOf("fecha de nacimiento", "nacimiento", "fecha nacimiento"),
        "employmentType" to setOf("tipo de empleo", "empleo", "condicion laboral", "condicion de empleo"),
        "documentType" to setOf("tipo de documento", "documento tipo", "tipo documento"),
        "documentNumber" to setOf("numero de documento", "documento", "cedula", "cedula de identidad", "numero de cedula"),
        "state" to setOf("estado", "estado venezolano"),
        "municipality" to setOf("municipio"),
        "parish" to setOf("parroquia"),
        "community" to setOf("comunidad"),
        "address" to setOf("direccion", "direccion de residencia", "direccion completa"),
        "password" to setOf("contrasena", "contrasena inicial", "contrasena temporal", "clave", "clave inicial", "clave temporal", "password"),
        "pin" to setOf("pin", "pin de acceso", "pin operativo"),
        "adminSubRole" to setOf("subrol", "subrol administrador", "perfil administrativo", "subrol administrativo")
    ).mapValues { (_, aliases) -> aliases.map(::normalizeHeader).toSet() }

    private val aliasToCanonical: Map<String, String> = buildMap {
        canonicalAliases.forEach { (canonical, aliases) -> aliases.forEach { put(it, canonical) } }
    }

    private val recognizableHeaders = aliasToCanonical.keys

    fun parse(bytes: ByteArray): StaffExcelParsedWorkbook {
        if (bytes.isEmpty()) throw AppException("El archivo Excel está vacío.")
        val rows = mutableListOf<StaffExcelRawRow>()
        val ignored = mutableListOf<String>()

        try {
            WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
                val formatter = DataFormatter(Locale.forLanguageTag("es-VE"), true)
                val evaluator = workbook.creationHelper.createFormulaEvaluator()

                for (sheetIndex in 0 until workbook.numberOfSheets) {
                    val sheet = workbook.getSheetAt(sheetIndex)
                    val sheetName = sheet.sheetName.orEmpty().trim().ifBlank { "Hoja ${sheetIndex + 1}" }
                    if (isDocumentationSheet(sheetName)) continue
                    val inferredRole = inferRoleFromSheet(sheetName)
                    val headerRowIndex = findHeaderRow(sheet, formatter, evaluator)
                    if (headerRowIndex == null) {
                        if (!isDocumentationSheet(sheetName) && hasMeaningfulContent(sheet, formatter, evaluator)) {
                            ignored += "$sheetName: contiene datos, pero no se reconocieron suficientes encabezados del formato de personal."
                        }
                        continue
                    }
                    val headerRow = sheet.getRow(headerRowIndex)
                    if (headerRow == null) continue
                    val columns = mutableMapOf<Int, String>()
                    for (cellIndex in headerRow.firstCellNum.toInt().coerceAtLeast(0) until headerRow.lastCellNum.toInt().coerceAtLeast(0)) {
                        val raw = formatter.formatCellValue(headerRow.getCell(cellIndex), evaluator).trim()
                        aliasToCanonical[normalizeHeader(raw)]?.let { canonical ->
                            if (canonical !in columns.values) columns[cellIndex] = canonical
                        }
                    }
                    if (columns.isEmpty()) {
                        if (!isDocumentationSheet(sheetName) && hasMeaningfulContent(sheet, formatter, evaluator)) {
                            ignored += "$sheetName: no se pudieron asociar sus columnas con los campos de Kredi+."
                        }
                        continue
                    }

                    var sheetRows = 0
                    for (rowIndex in (headerRowIndex + 1)..sheet.lastRowNum) {
                        val row = sheet.getRow(rowIndex) ?: continue
                        val values = linkedMapOf<String, String>()
                        var nonBlank = false
                        columns.forEach { (cellIndex, canonical) ->
                            val value = formattedCell(row, cellIndex, canonical, formatter, evaluator)
                            if (value.isNotBlank()) nonBlank = true
                            values[canonical] = value
                        }
                        if (!nonBlank) continue
                        if (values.values.any { it.equals("Sin registros", true) }) continue
                        rows += StaffExcelRawRow(sheetName, rowIndex + 1, inferredRole, values)
                        sheetRows++
                        if (rows.size > MAX_ROWS) throw AppException("La importación admite hasta $MAX_ROWS filas por archivo.")
                    }
                    // Una hoja con encabezados pero sin filas no necesita advertencia.
                }
            }
        } catch (error: AppException) {
            throw error
        } catch (error: Throwable) {
            throw AppException("No fue posible leer el archivo. Verifica que sea un Excel .xlsx válido y no esté protegido con contraseña.")
        }

        if (rows.isEmpty()) {
            throw AppException(
                "No se encontraron filas importables. El nombre del archivo no importa; verifica que el Excel tenga encabezados reconocibles y filas de personal válidas."
            )
        }
        return StaffExcelParsedWorkbook(rows, ignored.distinct(), rows.map { it.sheetName }.distinct().size)
    }


    private fun isDocumentationSheet(name: String): Boolean {
        val normalized = normalizeHeader(name)
        return normalized in setOf(
            "leeme", "leer", "instrucciones", "instruccion", "readme", "ayuda",
            "notas", "nota", "informacion", "info", "formato", "plantilla", "ejemplo", "ejemplos"
        )
    }

    private fun hasMeaningfulContent(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        formatter: DataFormatter,
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator
    ): Boolean {
        val lastRow = minOf(sheet.lastRowNum, 30)
        var nonBlankCells = 0
        for (rowIndex in 0..lastRow) {
            val row = sheet.getRow(rowIndex) ?: continue
            val first = row.firstCellNum.toInt().coerceAtLeast(0)
            val last = row.lastCellNum.toInt().coerceAtLeast(0)
            for (cellIndex in first until last) {
                if (formatter.formatCellValue(row.getCell(cellIndex), evaluator).trim().isNotBlank()) {
                    nonBlankCells++
                    if (nonBlankCells >= 4) return true
                }
            }
        }
        return false
    }

    private fun findHeaderRow(sheet: org.apache.poi.ss.usermodel.Sheet, formatter: DataFormatter, evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator): Int? {
        val last = minOf(sheet.lastRowNum, HEADER_SCAN_ROWS - 1)
        var best: Pair<Int, Int>? = null
        for (rowIndex in 0..last) {
            val row = sheet.getRow(rowIndex) ?: continue
            var recognized = 0
            for (cellIndex in row.firstCellNum.toInt().coerceAtLeast(0) until row.lastCellNum.toInt().coerceAtLeast(0)) {
                val normalized = normalizeHeader(formatter.formatCellValue(row.getCell(cellIndex), evaluator))
                if (normalized in recognizableHeaders) recognized++
            }
            if (recognized >= 4 && (best == null || recognized > best.second)) best = rowIndex to recognized
        }
        return best?.first
    }

    private fun formattedCell(
        row: Row,
        cellIndex: Int,
        canonical: String,
        formatter: DataFormatter,
        evaluator: org.apache.poi.ss.usermodel.FormulaEvaluator
    ): String {
        val cell = row.getCell(cellIndex) ?: return ""
        if (canonical == "birthDate" && cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.localDateTimeCellValue.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
        val raw = formatter.formatCellValue(cell, evaluator).trim()
        if (canonical == "birthDate") return normalizeDateText(raw)
        return raw
    }

    private fun normalizeDateText(value: String): String {
        val text = value.trim()
        if (text.isBlank()) return ""
        val patterns = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy")
        for (pattern in patterns) {
            try {
                return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern)).format(DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: DateTimeParseException) {
            }
        }
        return text
    }

    private fun inferRoleFromSheet(name: String): String? {
        val normalized = normalizeHeader(name)
        return when {
            "administr" in normalized -> "ADMIN"
            "almacen" in normalized || "bodega" in normalized -> "WAREHOUSE"
            "contador" in normalized || "contable" in normalized -> "ACCOUNTANT"
            "benefici" in normalized -> "BENEFICIARY"
            else -> null
        }
    }

    internal fun normalizeHeader(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
