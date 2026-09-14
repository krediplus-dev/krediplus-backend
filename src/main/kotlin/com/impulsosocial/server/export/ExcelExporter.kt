package com.impulsosocial.server.export

import com.impulsosocial.server.db.Database
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ExcelExporter(private val database: Database) {
    fun export(): ByteArray = database.dataSource.connection.use { connection ->
        XSSFWorkbook().use { workbook ->
            val headerStyle = createHeaderStyle(workbook)
            addQuerySheet(workbook, connection, "Nomina_Personas", headerStyle,
                """
                SELECT u.id,up.first_name AS primer_nombre,up.middle_name AS segundo_nombre,up.last_name AS primer_apellido,
                       up.second_last_name AS segundo_apellido,up.full_name AS nombre_completo,u.email,up.phone,up.birth_date,
                       u.role,u.verification_status,u.account_status,up.state,up.municipality,up.parish,up.community,up.address,
                       COALESCE(hc.porcentaje,100) AS historial_crediticio_porcentaje,
                       COALESCE(hc.pagos_atrasados,0) AS pagos_atrasados,
                       COALESCE(hc.estado,'ACTIVE') AS estado_historial_crediticio,
                       u.created_at,u.last_login_at
                FROM usuarios u
                LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                LEFT JOIN historial_crediticio_usuarios hc ON hc.user_id=u.id
                ORDER BY u.created_at DESC
                """.trimIndent())
            addQuerySheet(workbook, connection, "Verificaciones", headerStyle,
                """
                SELECT dv.id,dv.user_id,up.full_name,u.email,dv.document_type,dv.document_number,dv.status,dv.rejection_reason,
                       dv.submitted_at,dv.reviewed_at
                FROM verificaciones_documentos dv JOIN usuarios u ON u.id=dv.user_id LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                ORDER BY dv.submitted_at DESC
                """.trimIndent())
            addQuerySheet(workbook, connection, "Jornadas", headerStyle,
                "SELECT id,name,place,schedule_text,description,published,active,payment_mode,created_at,published_at FROM jornadas ORDER BY created_at DESC")
            addQuerySheet(workbook, connection, "Productos", headerStyle,
                "SELECT id,name,category,unit,base_price,stock,active,created_at,updated_at FROM productos ORDER BY name")
            addQuerySheet(workbook, connection, "Pedidos", headerStyle,
                """
                SELECT o.id,up.full_name AS usuario,u.email,f.name AS jornada,o.status,o.item_count,o.subtotal,o.total,
                       i.invoice_number,o.created_at
                FROM pedidos o JOIN usuarios u ON u.id=o.user_id LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                JOIN jornadas f ON f.id=o.fair_id LEFT JOIN facturas i ON i.order_id=o.id ORDER BY o.created_at DESC
                """.trimIndent())
            addQuerySheet(workbook, connection, "Pagos", headerStyle,
                "SELECT id,order_id,method,origin_bank,reference_number,transaction_at,amount_paid,status,verified_at,created_at FROM pagos ORDER BY created_at DESC")
            addQuerySheet(workbook, connection, "Facturas", headerStyle,
                """
                SELECT i.id,i.invoice_number,i.order_id,up.full_name AS beneficiario,u.email,f.name AS jornada,o.total,i.generated_at
                FROM facturas i JOIN pedidos o ON o.id=i.order_id JOIN usuarios u ON u.id=o.user_id
                LEFT JOIN perfiles_usuario up ON up.user_id=u.id JOIN jornadas f ON f.id=o.fair_id
                ORDER BY i.generated_at DESC
                """.trimIndent())
            addQuerySheet(workbook, connection, "Perfil Financiero", headerStyle,
                """
                SELECT u.id,up.full_name,u.email,fp.purchase_line,fp.user_level,fp.points,fp.points_expires_at,
                       fp.risk_status,fp.last_evaluated_at,fp.updated_at
                FROM perfiles_financieros_usuario fp JOIN usuarios u ON u.id=fp.user_id
                LEFT JOIN perfiles_usuario up ON up.user_id=u.id ORDER BY up.full_name
                """.trimIndent())
            addQuerySheet(workbook, connection, "Comunidades", headerStyle,
                "SELECT id,name,state,municipality,parish,families,active,created_at FROM comunidades ORDER BY name")
            addQuerySheet(workbook, connection, "Combos", headerStyle,
                "SELECT id,name,description,combo_price_usd,combo_price_bs,bcv_rate,active,created_at FROM combos ORDER BY created_at DESC")
            addQuerySheet(workbook, connection, "Inventario", headerStyle,
                "SELECT im.id,p.name AS producto,im.movement_type,im.quantity_delta,im.reference_type,im.reference_id,im.notes,im.created_at FROM movimientos_inventario im JOIN productos p ON p.id=im.product_id ORDER BY im.created_at DESC")
            addQuerySheet(workbook, connection, "Crédito Kredi+_Cuentas", headerStyle,
                """
                SELECT ca.user_id,
                       COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.email) AS nombre_completo,
                       u.email,ca.wallet_address,ca.level,ca.credit_limit_usd,ca.status,ca.granted_at,ca.updated_at
                FROM cuentas_credito ca
                JOIN usuarios u ON u.id=ca.user_id
                LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                ORDER BY ca.updated_at DESC
                """.trimIndent())
            addQuerySheet(workbook, connection, "Crédito Kredi+_Solicitudes", headerStyle,
                """
                SELECT cr.id,cr.user_id,
                       COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.email) AS nombre_completo,
                       u.email,cr.requested_amount_usd,cr.requested_installments,cr.purpose,cr.status,
                       cr.wallet_transaction_id,cr.source_wallet_address,
                       COALESCE(cr.destination_wallet_address,ca.wallet_address) AS destination_wallet_address,
                       cr.wallet_reference,cr.approved_amount_bs,cr.approval_bcv_rate,
                       cr.reviewed_by,cr.reviewed_at,cr.created_at
                FROM solicitudes_credito cr
                JOIN usuarios u ON u.id=cr.user_id
                LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                LEFT JOIN cuentas_credito ca ON ca.user_id=cr.user_id
                ORDER BY cr.created_at DESC
                """.trimIndent())
            addQuerySheet(workbook, connection, "Crédito Kredi+_Prestamos", headerStyle,
                "SELECT cl.id,cl.user_id,up.full_name,cl.order_id,cl.principal_usd,cl.principal_bs,cl.bcv_rate,cl.installment_count,cl.status,cl.created_at,cl.updated_at FROM prestamos_credito cl LEFT JOIN perfiles_usuario up ON up.user_id=cl.user_id ORDER BY cl.created_at DESC")
            addQuerySheet(workbook, connection, "Crédito Kredi+_Cuotas", headerStyle,
                "SELECT ci.id,ci.loan_id,ci.installment_number,ci.amount_usd,ci.original_amount_bs,ci.due_date,ci.status,ci.paid_at,ci.paid_by,ci.created_at FROM cuotas_credito ci ORDER BY ci.due_date DESC")
            addQuerySheet(workbook, connection, "Crédito Kredi+_Movimientos", headerStyle,
                "SELECT id,user_id,loan_id,order_id,installment_id,transaction_type,amount_usd,amount_bs,bcv_rate,balance_before_usd,balance_after_usd,description,performed_by,created_at FROM transacciones_credimpulso ORDER BY created_at DESC")
            addQuerySheet(workbook, connection, "Presupuesto_Movimientos", headerStyle,
                "SELECT id,contador_id,tipo,monto_usd,tasa_bcv,monto_bs,saldo_antes_usd,saldo_despues_usd,referencia,descripcion,estado,created_at FROM movimientos_presupuestarios ORDER BY created_at DESC")
            addQuerySheet(workbook, connection, "Presupuesto_Resumen", headerStyle,
                """SELECT c.contador_id,c.presupuesto_inicial_usd,c.saldo_disponible_usd,c.total_asignado_usd,
                          COALESCE((SELECT SUM(a.saldo_disponible_usd) FROM carteras_credimpulso_admin a),0) AS saldo_administradores_usd,
                          COALESCE((SELECT SUM(p.principal_usd) FROM prestamos_credito p WHERE p.status<>'CANCELLED'),0) AS prestamos_otorgados_usd,
                          COALESCE((SELECT SUM(q.amount_usd) FROM cuotas_credito q JOIN prestamos_credito p ON p.id=q.loan_id WHERE q.status='PAID' AND p.status<>'CANCELLED'),0) AS prestamos_recuperados_usd,
                          COALESCE((SELECT SUM(q.amount_usd) FROM cuotas_credito q JOIN prestamos_credito p ON p.id=q.loan_id WHERE q.status<>'PAID' AND p.status<>'CANCELLED'),0) AS prestamos_por_cobrar_usd,
                          c.updated_at
                   FROM carteras_presupuesto_contador c ORDER BY c.contador_id""")
            addCreditHistorySheets(workbook, connection, headerStyle)
            addQuerySheet(workbook, connection, "Auditoria", headerStyle,
                "SELECT id,user_id,action,entity_type,entity_id,description,metadata,created_at FROM registros_auditoria ORDER BY created_at DESC")
            ByteArrayOutputStream().use { output -> workbook.write(output); output.toByteArray() }
        }
    }


    fun exportPayroll(): ByteArray = database.dataSource.connection.use { connection ->
        XSSFWorkbook().use { workbook ->
            val headerStyle = createHeaderStyle(workbook)
            // Orden solicitado para la nómina: Beneficiarios, Administradores y Almacenistas.
            // El Contador queda al final como cuenta institucional protegida.
            addPayrollRoleSheet(workbook, connection, "Beneficiarios", "BENEFICIARY", headerStyle)
            addPayrollRoleSheet(workbook, connection, "Administradores", "ADMIN", headerStyle)
            addPayrollRoleSheet(workbook, connection, "Almacenistas", "WAREHOUSE", headerStyle)
            addPayrollRoleSheet(workbook, connection, "Contador", "ACCOUNTANT", headerStyle)
            ByteArrayOutputStream().use { output -> workbook.write(output); output.toByteArray() }
        }
    }

    /**
     * Formato oficial para la carga masiva de personal operativo. El nombre del archivo
     * nunca se usa para importar: Kredi+ reconoce los encabezados y valida el contenido.
     * Incluye la contraseña inicial y el PIN porque son las credenciales de primer acceso.
     */
    fun exportStaffImportFormat(): ByteArray = XSSFWorkbook().use { workbook ->
        val headers = listOf(
            "Rol", "Nombre completo", "Usuario", "Correo electrónico", "Teléfono",
            "Fecha de nacimiento", "Tipo de empleo", "Tipo de documento", "Número de documento",
            "Estado", "Municipio", "Parroquia", "Comunidad", "Dirección",
            "Contraseña inicial", "PIN", "Subrol administrador"
        )

        val headerStyle = createHeaderStyle(workbook)
        val requiredStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(headerStyle)
            fillForegroundColor = IndexedColors.DARK_TEAL.index
        }
        val infoStyle = workbook.createCellStyle().apply {
            wrapText = true
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.TOP
        }

        fun writeHeaders(sheet: org.apache.poi.ss.usermodel.Sheet) {
            val row = sheet.createRow(0)
            headers.forEachIndexed { index, title ->
                row.createCell(index).apply {
                    setCellValue(title)
                    cellStyle = if (index <= 15) requiredStyle else headerStyle
                }
            }
            sheet.createFreezePane(0, 1)
            sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.lastIndex))
            val widths = intArrayOf(17, 30, 20, 31, 19, 20, 21, 20, 22, 17, 18, 20, 22, 42, 27, 13, 23)
            widths.forEachIndexed { index, width -> sheet.setColumnWidth(index, width * 256) }
            sheet.defaultRowHeightInPoints = 20f
        }

        val personal = workbook.createSheet("Personal")
        writeHeaders(personal)
        personal.createRow(1).apply { heightInPoints = 24f }
        val helper = personal.dataValidationHelper
        fun addListValidation(column: Int, values: Array<String>) {
            val constraint = helper.createExplicitListConstraint(values)
            val regions = org.apache.poi.ss.util.CellRangeAddressList(1, 1000, column, column)
            helper.createValidation(constraint, regions).apply {
                suppressDropDownArrow = false
                errorStyle = org.apache.poi.ss.usermodel.DataValidation.ErrorStyle.STOP
                createErrorBox("Valor no válido", "Selecciona uno de los valores permitidos.")
                showErrorBox = true
                personal.addValidationData(this)
            }
        }
        addListValidation(0, arrayOf("Administrador", "Almacenista", "Contador"))
        addListValidation(6, arrayOf("Empleado público", "Empleado privado"))
        addListValidation(7, arrayOf("Cédula", "Pasaporte"))
        addListValidation(16, arrayOf("General", "Supervisor", "Analista", "Soporte", "Auditor", "Antifraude"))

        // Fechas y credenciales se mantienen legibles como texto en el formato de carga.
        personal.setDefaultColumnStyle(5, workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("dd/mm/yyyy") })
        personal.setDefaultColumnStyle(14, workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("@") })
        personal.setDefaultColumnStyle(15, workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("@") })

        val example = workbook.createSheet("EJEMPLO")
        writeHeaders(example)
        val examples = listOf(
            listOf("Administrador", "Adriana Solano Pérez", "ADM.EJEMPLO01", "admin.ejemplo01@example.com", "+58 412-7000001", "08/06/1984", "Empleado público", "Cédula", "V-30100001", "Monagas", "Maturín", "Boquerón", "Tipuro", "Av. Ejemplo 1, Maturín", "M0nagas#Acceso26!A", "410001", "General"),
            listOf("Almacenista", "Miguel Ortega Rosales", "ALM.EJEMPLO01", "almacen.ejemplo01@example.com", "+58 414-7100001", "15/11/1987", "Empleado público", "Cédula", "V-30200001", "Monagas", "Maturín", "Las Cocuizas", "Los Guaritos", "Calle Ejemplo 2, Maturín", "M0nagas#Acceso26!W", "420001", ""),
            listOf("Contador", "Lucía Mendoza Rivas", "CONT.EJEMPLO01", "contador.ejemplo01@example.com", "+58 416-7200001", "22/04/1990", "Empleado público", "Cédula", "V-30300001", "Monagas", "Maturín", "Jusepín", "Juanico", "Av. Ejemplo 3, Maturín", "M0nagas#Acceso26!C", "430001", "")
        )
        examples.forEachIndexed { rowIndex, values ->
            val row = example.createRow(rowIndex + 1)
            values.forEachIndexed { columnIndex, value -> row.createCell(columnIndex).setCellValue(value) }
        }

        val readme: org.apache.poi.ss.usermodel.Sheet = workbook.createSheet("LEEME")
        readme.setColumnWidth(0, 28 * 256)
        readme.setColumnWidth(1, 95 * 256)
        readme.createRow(0).createCell(0).apply { setCellValue("Formato de carga de personal Kredi+"); cellStyle = requiredStyle }
        val rules = listOf(
            "Nombre del archivo" to "No importa. Kredi+ reconoce el formato por los encabezados y el contenido del .xlsx.",
            "Roles permitidos" to "Administrador, Almacenista y Contador. Esta importación no crea Beneficiarios.",
            "Columnas" to "Pueden estar en cualquier orden y se permiten columnas adicionales.",
            "Contraseña inicial" to "Obligatoria. Debe tener entre 8 y 64 caracteres, con mayúscula, minúscula, número y carácter especial; no debe contener el usuario ni la parte principal del correo.",
            "PIN" to "Obligatorio. Debe contener exactamente 6 dígitos.",
            "Ejemplos" to "La hoja EJEMPLO contiene credenciales ficticias que cumplen la política de seguridad. Kredi+ la ignora durante la importación.",
            "Hoja Personal" to "Es la hoja lista para llenar. No elimines los encabezados. Las celdas ya tienen anchos, filtros y listas desplegables configuradas.",
            "Validación" to "Antes de guardar en la base de datos, Kredi+ muestra una vista previa y los errores de cada fila."
        )
        rules.forEachIndexed { index, (label, detail) ->
            val row = readme.createRow(index + 2)
            row.createCell(0).apply { setCellValue(label); cellStyle = headerStyle }
            row.createCell(1).apply { setCellValue(detail); cellStyle = infoStyle }
            row.heightInPoints = 34f
        }
        readme.createFreezePane(0, 2)

        ByteArrayOutputStream().use { output -> workbook.write(output); output.toByteArray() }
    }

    /**
     * Formato oficial de carga masiva de Beneficiarios para Administrador.
     * Coincide con FORMATO_PRUEBA_CARGA_PERSONAL_CREDICASH.xlsx entregado para 7.2.6.
     * El nombre físico del archivo no se valida: importamos por encabezados/contenido.
     */
    fun exportBeneficiaryImportFormat(): ByteArray = XSSFWorkbook().use { workbook ->
        val headers = listOf(
            "Nombre completo", "Número de documento", "Correo electrónico", "Teléfono",
            "Fecha de nacimiento", "Tipo de empleo", "Municipio", "Parroquia", "Comunidad"
        )
        val headerStyle = createHeaderStyle(workbook)
        val requiredStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(headerStyle)
            fillForegroundColor = IndexedColors.DARK_TEAL.index
        }
        val infoStyle = workbook.createCellStyle().apply {
            wrapText = true
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.TOP
        }
        fun writeHeaders(sheet: org.apache.poi.ss.usermodel.Sheet) {
            val row = sheet.createRow(0)
            headers.forEachIndexed { index, title ->
                row.createCell(index).apply { setCellValue(title); cellStyle = requiredStyle }
            }
            sheet.createFreezePane(0, 1)
            sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.lastIndex))
            val widths = intArrayOf(32, 22, 32, 20, 20, 22, 20, 20, 24)
            widths.forEachIndexed { index, width -> sheet.setColumnWidth(index, width * 256) }
            sheet.defaultRowHeightInPoints = 20f
        }

        val personal = workbook.createSheet("Personal")
        writeHeaders(personal)
        personal.createRow(1).apply { heightInPoints = 24f }
        val helper = personal.dataValidationHelper
        val employmentConstraint = helper.createExplicitListConstraint(arrayOf("Empleado público", "Empleado privado", "No aplica"))
        val regions = org.apache.poi.ss.util.CellRangeAddressList(1, 2000, 5, 5)
        helper.createValidation(employmentConstraint, regions).apply {
            suppressDropDownArrow = false
            errorStyle = org.apache.poi.ss.usermodel.DataValidation.ErrorStyle.STOP
            createErrorBox("Valor no válido", "Selecciona Empleado público, Empleado privado o No aplica.")
            showErrorBox = true
            personal.addValidationData(this)
        }
        personal.setDefaultColumnStyle(4, workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("dd/mm/yyyy") })

        val example = workbook.createSheet("EJEMPLO")
        writeHeaders(example)
        val exampleValues = listOf(
            "Valeria Méndez Salazar", "30400001", "beneficiario.ejemplo01@example.com",
            "+58 412-7300001", "18/09/1994", "Empleado público", "Maturín", "Boquerón", "Tipuro"
        )
        val exampleRow = example.createRow(1)
        exampleValues.forEachIndexed { index, value -> exampleRow.createCell(index).setCellValue(value) }

        val readme: org.apache.poi.ss.usermodel.Sheet = workbook.createSheet("LEEME")
        readme.setColumnWidth(0, 31 * 256)
        readme.setColumnWidth(1, 100 * 256)
        readme.createRow(0).createCell(0).apply { setCellValue("Formato de carga de Beneficiarios Kredi+"); cellStyle = requiredStyle }
        val rules = listOf(
            "Formato exacto" to "Personal usa 9 columnas: Nombre completo, Número de documento, Correo electrónico, Teléfono, Fecha de nacimiento, Tipo de empleo, Municipio, Parroquia y Comunidad.",
            "Nombre del archivo" to "No importa. Kredi+ reconoce el .xlsx por los encabezados y el contenido.",
            "Documento" to "Para esta nómina se interpreta Número de documento como Cédula. El RIF no forma parte de las personas y permanece solo en Negocios asociados.",
            "Credenciales" to "No necesitas columnas de usuario, contraseña ni PIN. Kredi+ genera el usuario interno y permite iniciar sesión con el correo. Contraseña inicial: Credi# + últimos 6 dígitos del documento + Aa1. PIN: esos mismos 6 dígitos.",
            "Aprobación" to "La carga no activa automáticamente al Beneficiario. Queda pendiente de revisión administrativa, visible para Administradores autorizados y para el Contador.",
            "Trazabilidad" to "Se guarda qué Administrador realizó la carga. Ese Administrador ve sus Beneficiarios; el Contador ve el universo completo y el responsable de cada alta.",
            "Hojas auxiliares" to "EJEMPLO, LEEME, instrucciones y hojas auxiliares se ignoran siempre durante la importación."
        )
        rules.forEachIndexed { index, (label, detail) ->
            val row = readme.createRow(index + 2)
            row.createCell(0).apply { setCellValue(label); cellStyle = headerStyle }
            row.createCell(1).apply { setCellValue(detail); cellStyle = infoStyle }
            row.heightInPoints = 38f
        }
        readme.createFreezePane(0, 2)
        ByteArrayOutputStream().use { output -> workbook.write(output); output.toByteArray() }
    }

    /** Formato oficial de carga masiva de productos para Almacenista. */
    fun exportProductImportFormat(): ByteArray = XSSFWorkbook().use { workbook ->
        val headers = listOf(
            "Producto", "Categoría principal", "Clasificación", "Marca", "Unidad",
            "Forma de precio", "Precio (USD)", "Existencia", "Existencia mínima", "Estado", "Detalles"
        )
        val headerStyle = createHeaderStyle(workbook)
        val infoStyle = workbook.createCellStyle().apply { wrapText = true; verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.TOP }
        val sheet: org.apache.poi.ss.usermodel.Sheet = workbook.createSheet("Productos")
        val row = sheet.createRow(0)
        headers.forEachIndexed { index, title -> row.createCell(index).apply { setCellValue(title); cellStyle = headerStyle } }
        sheet.createFreezePane(0, 1)
        sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, headers.lastIndex))
        val widths = intArrayOf(36, 22, 31, 22, 20, 18, 16, 14, 18, 14, 52)
        widths.forEachIndexed { index, width -> sheet.setColumnWidth(index, width * 256) }
        val helper = sheet.dataValidationHelper
        fun listValidation(column: Int, values: Array<String>) {
            val validation = helper.createValidation(helper.createExplicitListConstraint(values), org.apache.poi.ss.util.CellRangeAddressList(1, 2000, column, column))
            validation.errorStyle = org.apache.poi.ss.usermodel.DataValidation.ErrorStyle.STOP
            validation.createErrorBox("Valor no válido", "Selecciona uno de los valores permitidos.")
            validation.showErrorBox = true
            sheet.addValidationData(validation)
        }
        listValidation(1, arrayOf("Alimentos", "Otros productos"))
        listValidation(5, arrayOf("UNIT", "KG"))
        listValidation(9, arrayOf("Activo", "Inactivo"))
        sheet.setDefaultColumnStyle(6, workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("0.00") })

        val example: org.apache.poi.ss.usermodel.Sheet = workbook.createSheet("EJEMPLO")
        val exampleHeader = example.createRow(0)
        headers.forEachIndexed { index, title -> exampleHeader.createCell(index).apply { setCellValue(title); cellStyle = headerStyle } }
        val examples = listOf(
            listOf("Harina de maíz precocida 1 kg", "Alimentos", "Harinas y pastas", "P.A.N.", "paquete 1 kg", "UNIT", "1.45", "120", "30", "Activo", ""),
            listOf("Teléfono Redmi Note 14 8GB 256GB", "Otros productos", "Teléfonos", "Xiaomi", "unidad", "UNIT", "235.00", "8", "2", "Activo", "RAM 8 GB; almacenamiento 256 GB; pantalla AMOLED; dual SIM.")
        )
        examples.forEachIndexed { r, values ->
            val er = example.createRow(r + 1)
            values.forEachIndexed { c, value -> er.createCell(c).setCellValue(value) }
        }
        widths.forEachIndexed { index, width -> example.setColumnWidth(index, width * 256) }

        val readme: org.apache.poi.ss.usermodel.Sheet = workbook.createSheet("LEEME")
        readme.setColumnWidth(0, 28 * 256); readme.setColumnWidth(1, 95 * 256)
        val rules = listOf(
            "Nombre del archivo" to "No importa. Kredi+ reconoce el archivo por los encabezados y el contenido del .xlsx.",
            "Columnas" to "Pueden estar en cualquier orden y se permiten columnas adicionales.",
            "Categorías" to "Categoría principal debe ser Alimentos u Otros productos. La clasificación y la marca son obligatorias.",
            "Precio" to "Precio (USD) debe ser mayor que cero. UNIT es por unidad y KG es por kilogramo.",
            "Existencia" to "Existencia y Existencia mínima deben ser números enteros iguales o mayores que cero.",
            "Tecnología/Farmacia" to "La columna Detalles es obligatoria para productos tecnológicos y farmacéuticos.",
            "Validación" to "Kredi+ muestra una vista previa antes de guardar. Solo las filas válidas se importan al confirmar."
        )
        rules.forEachIndexed { index, (label, detail) ->
            val rr = readme.createRow(index)
            rr.createCell(0).apply { setCellValue(label); cellStyle = headerStyle }
            rr.createCell(1).apply { setCellValue(detail); cellStyle = infoStyle }
            rr.heightInPoints = 34f
        }
        ByteArrayOutputStream().use { output -> workbook.write(output); output.toByteArray() }
    }

    private fun addPayrollRoleSheet(
        workbook: Workbook,
        connection: Connection,
        sheetName: String,
        role: String,
        headerStyle: CellStyle
    ) {
        addQuerySheet(
            workbook,
            connection,
            sheetName,
            headerStyle,
            """
            SELECT
                COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.username) AS nombre_completo,
                u.username AS usuario,
                u.email,
                up.phone,
                up.birth_date,
                up.employment_type,
                COALESCE(dv.document_type,'') AS document_type,
                COALESCE(dv.document_number,'') AS document_number,
                u.verification_status,
                u.account_status,
                up.state,up.municipality,up.parish,up.community,up.address,
                CASE WHEN u.linked_account_user_id IS NULL THEN 'NO' ELSE 'SI' END AS cuenta_vinculada,
                COALESCE(ca.level,1) AS level,
                COALESCE(ca.credit_limit_usd,0) AS credit_limit_usd,
                COALESCE(hc.porcentaje,100) AS historial_crediticio_porcentaje,
                COALESCE(hc.pagos_atrasados,0) AS pagos_atrasados,
                u.created_at,u.last_login_at
            FROM usuarios u
            LEFT JOIN perfiles_usuario up ON up.user_id=u.id
            LEFT JOIN cuentas_credito ca ON ca.user_id=u.id
            LEFT JOIN historial_crediticio_usuarios hc ON hc.user_id=u.id
            LEFT JOIN LATERAL (
                SELECT document_type,document_number
                FROM verificaciones_documentos
                WHERE user_id=u.id
                ORDER BY submitted_at DESC,id DESC
                LIMIT 1
            ) dv ON TRUE
            WHERE u.role='$role'
            ORDER BY COALESCE(up.last_name,''),COALESCE(up.first_name,''),u.username
            """.trimIndent()
        )
    }

    fun exportCreditHistory(): ByteArray = database.dataSource.connection.use { connection ->
        XSSFWorkbook().use { workbook ->
            val headerStyle = createHeaderStyle(workbook)
            addCreditHistorySheets(workbook, connection, headerStyle)
            ByteArrayOutputStream().use { output -> workbook.write(output); output.toByteArray() }
        }
    }

    fun exportProducts(): ByteArray = database.dataSource.connection.use { connection ->
        XSSFWorkbook().use { workbook ->
            val headerStyle = createHeaderStyle(workbook)
            addProductsGroupedSheet(workbook, connection, headerStyle)
            addQuerySheet(
                workbook,
                connection,
                "Movimientos de inventario",
                headerStyle,
                """
                SELECT p.name AS producto,im.movement_type,im.quantity_delta,im.reference_type,im.notes,im.created_at
                FROM movimientos_inventario im
                JOIN productos p ON p.id=im.product_id
                ORDER BY p.name,im.created_at DESC
                """.trimIndent()
            )
            ByteArrayOutputStream().use { output -> workbook.write(output); output.toByteArray() }
        }
    }

    private fun addProductsGroupedSheet(workbook: Workbook, connection: Connection, headerStyle: CellStyle) {
        val sheet = workbook.createSheet("Productos por clasificación")
        val columns = listOf(
            "Producto", "Marca", "Unidad", "Forma de precio", "Precio (USD)",
            "Existencia", "Existencia mínima", "Entradas", "Salidas", "Estado", "Detalles"
        )
        connection.prepareStatement(
            """
            SELECT p.name,p.category,p.unit,p.pricing_mode,COALESCE(p.base_price_usd,p.base_price) AS precio_usd,
                   p.stock,COALESCE(p.minimum_stock,0) AS minimum_stock,p.active,p.technical_details,
                   COALESCE(SUM(CASE WHEN im.quantity_delta>0 THEN im.quantity_delta ELSE 0 END),0) AS entradas,
                   COALESCE(SUM(CASE WHEN im.quantity_delta<0 THEN ABS(im.quantity_delta) ELSE 0 END),0) AS salidas
            FROM productos p
            LEFT JOIN movimientos_inventario im ON im.product_id=p.id
            GROUP BY p.id,p.name,p.category,p.unit,p.pricing_mode,p.base_price_usd,p.base_price,p.stock,p.minimum_stock,p.active,p.technical_details
            ORDER BY p.category,p.name
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result ->
                var rowIndex = 0
                var currentClassification: String? = null
                var rowsInGroup = 0
                while (result.next()) {
                    val (_, classification, brand) = productCategoryParts(result.getString("category").orEmpty())
                    if (classification != currentClassification) {
                        if (currentClassification != null && rowsInGroup == 0) {
                            sheet.createRow(rowIndex++).createCell(0).setCellValue("Sin productos")
                        }
                        currentClassification = classification.ifBlank { "Sin clasificación" }
                        rowsInGroup = 0
                        val groupRow = sheet.createRow(rowIndex++)
                        groupRow.createCell(0).apply {
                            setCellValue("Clasificación: $currentClassification")
                            cellStyle = headerStyle
                        }
                        val header = sheet.createRow(rowIndex++)
                        columns.forEachIndexed { index, title ->
                            header.createCell(index).apply { setCellValue(title); cellStyle = headerStyle }
                        }
                    }
                    val row = sheet.createRow(rowIndex++)
                    val values = listOf(
                        result.getString("name").orEmpty(),
                        brand.ifBlank { "No especificada" },
                        result.getString("unit").orEmpty(),
                        translateValue(result.getString("pricing_mode").orEmpty()),
                        result.getBigDecimal("precio_usd")?.toPlainString().orEmpty(),
                        result.getInt("stock").toString(),
                        result.getInt("minimum_stock").toString(),
                        result.getInt("entradas").toString(),
                        result.getInt("salidas").toString(),
                        if (result.getBoolean("active")) "Activo" else "Inactivo",
                        result.getString("technical_details").orEmpty().ifBlank { "Sin detalles" }
                    )
                    values.forEachIndexed { index, value -> row.createCell(index).setCellValue(value) }
                    rowsInGroup++
                }
                if (rowIndex == 0) sheet.createRow(0).createCell(0).setCellValue("Sin productos registrados")
                columns.indices.forEach { column ->
                    sheet.autoSizeColumn(column)
                    if (sheet.getColumnWidth(column) > 12000) sheet.setColumnWidth(column, 12000)
                }
            }
        }
    }

    private fun productCategoryParts(raw: String): Triple<String, String, String> {
        val parts = raw.split(" · ").map(String::trim).filter(String::isNotBlank)
        if (parts.isEmpty()) return Triple("Sin categoría", "Sin clasificación", "")
        val main = if (parts.first() in setOf("Alimentos", "Otros productos")) parts.first() else "Inventario"
        val classification = if (parts.first() in setOf("Alimentos", "Otros productos")) parts.getOrNull(1).orEmpty() else parts.first()
        val brand = parts.firstOrNull { it.startsWith("Marca ", ignoreCase = true) }
            ?.substringAfter("Marca ", "")
            .orEmpty()
        return Triple(main, classification, brand)
    }

    private fun addCreditHistorySheets(workbook: Workbook, connection: Connection, headerStyle: CellStyle) {
        addQuerySheet(workbook, connection, "Historial_Resumen", headerStyle,
            """
            SELECT u.id AS user_id,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.email) AS nombre_completo,
                   u.email,COALESCE(ca.level,1) AS level,COALESCE(ca.credit_limit_usd,0) AS credit_limit_usd,
                   COALESCE(SUM(CASE WHEN ci.status<>'PAID' THEN ci.amount_usd ELSE 0 END),0) AS saldo_gastado_usd,
                   GREATEST(COALESCE(ca.credit_limit_usd,0)-COALESCE(SUM(CASE WHEN ci.status<>'PAID' THEN ci.amount_usd ELSE 0 END),0),0) AS saldo_disponible_usd,
                   hc.porcentaje AS historial_crediticio_porcentaje,hc.pagos_atrasados,hc.pagos_a_tiempo,
                   hc.estado AS estado_historial_crediticio,hc.suspendido_at,hc.motivo_suspension,hc.updated_at
            FROM historial_crediticio_usuarios hc
            JOIN usuarios u ON u.id=hc.user_id
            LEFT JOIN perfiles_usuario up ON up.user_id=u.id
            LEFT JOIN cuentas_credito ca ON ca.user_id=u.id
            LEFT JOIN prestamos_credito cl ON cl.user_id=u.id AND cl.status<>'CANCELLED'
            LEFT JOIN cuotas_credito ci ON ci.loan_id=cl.id
            GROUP BY u.id,up.first_name,up.middle_name,up.last_name,up.second_last_name,up.full_name,u.email,
                     ca.level,ca.credit_limit_usd,hc.porcentaje,hc.pagos_atrasados,hc.pagos_a_tiempo,hc.estado,
                     hc.suspendido_at,hc.motivo_suspension,hc.updated_at
            ORDER BY hc.porcentaje ASC,hc.updated_at DESC
            """.trimIndent())
        addQuerySheet(workbook, connection, "Historial_Eventos", headerStyle,
            """
            SELECT e.id,e.user_id,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.email) AS nombre_completo,
                   u.email,e.event_type,e.score_before,e.score_after,e.invoice_number,e.due_date,e.occurred_at,
                   e.loan_id,e.installment_id,e.order_id,e.details
            FROM eventos_historial_crediticio e
            JOIN usuarios u ON u.id=e.user_id
            LEFT JOIN perfiles_usuario up ON up.user_id=u.id
            ORDER BY e.occurred_at DESC
            """.trimIndent())
    }

    private fun addQuerySheet(workbook: Workbook, connection: Connection, name: String, headerStyle: CellStyle, sql: String) {
        val sheet = workbook.createSheet(name.take(31))
        runCatching {
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    val meta = result.metaData
                    val header = sheet.createRow(0)
                    for (column in 1..meta.columnCount) {
                        header.createCell(column - 1).apply {
                            setCellValue(translateHeader(meta.getColumnLabel(column)))
                            cellStyle = headerStyle
                        }
                    }
                    var rowIndex = 1
                    while (result.next()) {
                        val row = sheet.createRow(rowIndex++)
                        for (column in 1..meta.columnCount) {
                            row.createCell(column - 1).setCellValue(translateValue(result.getObject(column)?.toString().orEmpty()))
                        }
                    }
                    if (rowIndex == 1) sheet.createRow(1).createCell(0).setCellValue("Sin registros")
                    for (column in 0 until meta.columnCount) {
                        sheet.autoSizeColumn(column)
                        if (sheet.getColumnWidth(column) > 12000) sheet.setColumnWidth(column, 12000)
                    }
                    sheet.createFreezePane(0, 1)
                }
            }
        }.onFailure { error ->
            // Una tabla opcional de una instalación antigua no debe cancelar todo el Excel.
            val header = sheet.createRow(0)
            header.createCell(0).apply {
                setCellValue("Estado")
                cellStyle = headerStyle
            }
            sheet.createRow(1).createCell(0).setCellValue(
                "Esta sección todavía no tiene datos disponibles en la base instalada."
            )
            sheet.setColumnWidth(0, 12000)
        }
    }


    private fun translateHeader(raw: String): String {
        val key = raw.trim().lowercase()
        return HEADER_TRANSLATIONS[key] ?: key
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun translateValue(raw: String): String {
        if (raw.isBlank()) return "No registrado"
        val clean = raw.trim()
        VALUE_TRANSLATIONS[clean.uppercase()]?.let { return it }
        if (clean.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return runCatching { LocalDate.parse(clean).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrDefault(clean)
        }
        if (clean.length >= 16 && clean.take(10).matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && (clean.getOrNull(10) == 'T' || clean.getOrNull(10) == ' ')) {
            val date = runCatching { LocalDate.parse(clean.take(10)).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrNull()
            val time = clean.substring(11).take(5)
            if (date != null && time.matches(Regex("\\d{2}:\\d{2}"))) return "$date · $time"
        }
        if (clean.matches(Regex("[A-Z][A-Z0-9_ -]{2,}"))) {
            val tokenMap = mapOf(
                "PAYMENT" to "Pago", "PURCHASE" to "Compra", "ORDER" to "Pedido", "CREDIT" to "Crédito",
                "WALLET" to "Cartera", "TRANSFER" to "Transferencia", "INVENTORY" to "Inventario",
                "ADJUSTMENT" to "Ajuste", "INCOME" to "Ingreso", "EXPENSE" to "Gasto",
                "VERIFICATION" to "Verificación", "REVIEW" to "Revisión", "REQUEST" to "Solicitud",
                "CREATED" to "Creado", "UPDATED" to "Actualizado", "FINALIZED" to "Finalizado",
                "PRODUCT" to "Producto", "FAIR" to "Jornada", "COMMUNITY" to "Comunidad",
                "ACCOUNT" to "Cuenta", "USER" to "Usuario", "STAFF" to "Personal",
                "APPROVED" to "Aprobado", "REJECTED" to "Rechazado", "PENDING" to "Pendiente"
            )
            return clean.replace('-', '_').split('_', ' ').filter(String::isNotBlank)
                .joinToString(" ") { tokenMap[it] ?: it.lowercase().replaceFirstChar { ch -> ch.titlecase() } }
        }
        return clean
    }

    private companion object {
        val HEADER_TRANSLATIONS = mapOf(
            "id" to "ID",
            "user_id" to "ID de usuario",
            "order_id" to "ID de pedido",
            "loan_id" to "ID de préstamo",
            "installment_id" to "ID de cuota",
            "reference_id" to "ID de referencia",
            "primer_nombre" to "Primer nombre",
            "segundo_nombre" to "Segundo nombre",
            "primer_apellido" to "Primer apellido",
            "segundo_apellido" to "Segundo apellido",
            "full_name" to "Nombre completo",
            "nombre_completo" to "Nombre completo",
            "usuario" to "Usuario",
            "beneficiario" to "Beneficiario",
            "email" to "Correo electrónico",
            "phone" to "Teléfono",
            "birth_date" to "Fecha de nacimiento",
            "employment_type" to "Tipo de empleo",
            "cuenta_vinculada" to "Tiene cuenta vinculada",
            "account_kind" to "Tipo de cuenta",
            "classification" to "Clasificación",
            "clasificacion" to "Clasificación",
            "brand" to "Marca",
            "marca" to "Marca",
            "pricing_mode" to "Forma de precio",
            "technical_details" to "Detalles",
            "precio_usd" to "Precio (USD)",
            "minimum_stock" to "Existencia mínima",
            "entradas" to "Entradas",
            "salidas" to "Salidas",
            "role" to "Rol",
            "verification_status" to "Estado de verificación",
            "account_status" to "Estado de la cuenta",
            "state" to "Estado",
            "municipality" to "Municipio",
            "parish" to "Parroquia",
            "community" to "Comunidad",
            "address" to "Dirección",
            "created_at" to "Fecha de creación",
            "updated_at" to "Fecha de actualización",
            "last_login_at" to "Fecha de último ingreso",
            "document_type" to "Tipo de documento",
            "document_number" to "Número de documento",
            "status" to "Estado",
            "rejection_reason" to "Motivo de rechazo",
            "submitted_at" to "Fecha de envío",
            "reviewed_at" to "Fecha de revisión",
            "reviewed_by" to "Revisado por",
            "name" to "Nombre",
            "place" to "Lugar",
            "schedule_text" to "Fecha y horario",
            "description" to "Descripción",
            "published" to "Publicado",
            "active" to "Activo",
            "payment_mode" to "Modalidad de pago",
            "published_at" to "Fecha de publicación",
            "category" to "Categoría",
            "unit" to "Unidad",
            "base_price" to "Precio base",
            "stock" to "Existencia",
            "jornada" to "Jornada",
            "item_count" to "Cantidad de artículos",
            "subtotal" to "Subtotal",
            "total" to "Total",
            "invoice_number" to "Número de factura",
            "method" to "Método de pago",
            "origin_bank" to "Banco de origen",
            "reference_number" to "Número de referencia",
            "transaction_at" to "Fecha de transacción",
            "amount_paid" to "Monto pagado",
            "verified_at" to "Fecha de verificación",
            "generated_at" to "Fecha de generación",
            "purchase_line" to "Línea de compra",
            "user_level" to "Nivel del usuario",
            "points" to "Puntos",
            "points_expires_at" to "Vencimiento de puntos",
            "risk_status" to "Estado de riesgo",
            "last_evaluated_at" to "Última evaluación",
            "families" to "Cantidad de familias",
            "producto" to "Producto",
            "movement_type" to "Tipo de movimiento",
            "quantity_delta" to "Variación de cantidad",
            "reference_type" to "Tipo de referencia",
            "notes" to "Notas",
            "level" to "Nivel",
            "credit_limit_usd" to "Límite de crédito (USD)",
            "wallet_address" to "ID de cartera",
            "wallet_transaction_id" to "ID de transacción de cartera",
            "source_wallet_address" to "ID de cartera de origen",
            "destination_wallet_address" to "ID de cartera de destino",
            "wallet_reference" to "Referencia de cartera",
            "approved_amount_bs" to "Monto aprobado (Bs)",
            "approval_bcv_rate" to "Tasa BCV de aprobación",
            "granted_at" to "Fecha de otorgamiento",
            "requested_amount_usd" to "Monto solicitado (USD)",
            "requested_installments" to "Cuotas solicitadas",
            "purpose" to "Motivo de la solicitud",
            "principal_usd" to "Capital financiado (USD)",
            "principal_bs" to "Capital financiado (Bs)",
            "bcv_rate" to "Tasa BCV",
            "installment_count" to "Cantidad de cuotas",
            "installment_number" to "Número de cuota",
            "amount_usd" to "Monto (USD)",
            "amount_bs" to "Monto (Bs)",
            "original_amount_bs" to "Monto original (Bs)",
            "due_date" to "Fecha de vencimiento",
            "paid_at" to "Fecha de pago",
            "paid_by" to "Pago registrado por",
            "transaction_type" to "Tipo de transacción",
            "balance_before_usd" to "Saldo anterior (USD)",
            "balance_after_usd" to "Saldo posterior (USD)",
            "performed_by" to "Operación realizada por",
            "action" to "Acción",
            "entity_type" to "Tipo de entidad",
            "entity_id" to "ID de entidad",
            "metadata" to "Datos adicionales",
            "historial_crediticio_porcentaje" to "Historial crediticio (%)",
            "pagos_atrasados" to "Pagos atrasados",
            "pagos_a_tiempo" to "Pagos a tiempo",
            "estado_historial_crediticio" to "Estado del historial crediticio",
            "saldo_gastado_usd" to "Saldo gastado (USD)",
            "saldo_disponible_usd" to "Saldo disponible (USD)",
            "suspendido_at" to "Fecha de suspensión",
            "motivo_suspension" to "Motivo de suspensión",
            "event_type" to "Tipo de evento",
            "score_before" to "Porcentaje anterior",
            "score_after" to "Porcentaje posterior",
            "occurred_at" to "Fecha del evento",
            "details" to "Detalle"
        )

        val VALUE_TRANSLATIONS = mapOf(
            "TRUE" to "Sí",
            "FALSE" to "No",
            "ADMIN" to "Administrador",
            "ACCOUNTANT" to "Contador",
            "BENEFICIARY" to "Beneficiario",
            "WAREHOUSE" to "Almacenista",
            "OPERATIONAL" to "Cuenta operativa",
            "UNIT" to "Por unidad",
            "KG" to "Por kilogramo",
            "PREPARING" to "En preparación",
            "IN_PREPARATION" to "En preparación",
            "READY" to "Listo para entregar",
            "DELIVERED" to "Entregado",
            "STOCK_SHORTAGE" to "Faltante de inventario",
            "RETURNED" to "Devuelto",
            "CANCELLED" to "Cancelado",
            "CANCELED" to "Cancelado",
            "REPORTED" to "Reportado",
            "PROCESSING" to "Procesando",
            "COMPLETED" to "Completado",
            "OPEN" to "Abierto",
            "CLOSED" to "Cerrado",
            "YES" to "Sí",
            "SI" to "Sí",
            "NO" to "No",
            "ACTIVE" to "Activo",
            "INACTIVE" to "Inactivo",
            "PENDING" to "Pendiente",
            "PENDING_VERIFICATION" to "Pendiente de verificación",
            "NOT_SUBMITTED" to "No enviado",
            "VERIFIED" to "Verificado",
            "APPROVED" to "Aprobado",
            "REJECTED" to "Rechazado",
            "SUSPENDED" to "Suspendido",
            "BLOCKED" to "Bloqueado",
            "PAID" to "Pagado",
            "OVERDUE" to "Vencido",
            "NATIONAL_ID" to "Cédula de identidad",
            "PASSPORT" to "Pasaporte",
            "TAX_ID" to "Documento fiscal histórico",
            "MOBILE_PAYMENT" to "Pago móvil",
            "BANK_TRANSFER" to "Transferencia bancaria",
            "BOTH" to "Pago móvil y transferencia",
            "CREDIMPULSO" to "Crédito Kredi+",
            "PUBLIC_EMPLOYEE" to "Empleado público",
            "PRIVATE_EMPLOYEE" to "Empleado privado",
            "NOT_EVALUATED" to "No evaluado"
        )
    }

    private fun createHeaderStyle(workbook: Workbook): CellStyle = workbook.createCellStyle().apply {
        fillForegroundColor = IndexedColors.ORANGE.index
        fillPattern = FillPatternType.SOLID_FOREGROUND
        setFont(workbook.createFont().apply {
            bold = true
            color = IndexedColors.WHITE.index
        })
    }
}
