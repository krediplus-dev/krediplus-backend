package com.impulsosocial.server.service

import com.impulsosocial.server.db.Database
import com.impulsosocial.server.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

private val BUDGET_EXPENSE_TYPES = setOf(
    "INVENTORY_COST",
    "OPERATING_EXPENSE",
    "ADMINISTRATIVE_EXPENSE",
    "COMMERCIAL_EXPENSE",
    "FINANCIAL_EXPENSE",
    "EXTRAORDINARY_EXPENSE"
)

private val GROUP_MOVEMENT_TYPES = mapOf(
    "INVENTORY" to "INVENTORY_COST",
    "OPERATING" to "OPERATING_EXPENSE",
    "ADMINISTRATIVE" to "ADMINISTRATIVE_EXPENSE",
    "COMMERCIAL" to "COMMERCIAL_EXPENSE",
    "FINANCIAL" to "FINANCIAL_EXPENSE",
    "EXTRAORDINARY" to "EXTRAORDINARY_EXPENSE"
)

private val LEGACY_COST_CATEGORY_CODES = mapOf(
    "TRANSPORTATION" to "OP-STAFF-TRANSPORT",
    "LOGISTICS" to "OP-FREIGHT",
    "PER_DIEM" to "OP-PER-DIEM",
    "DELIVERY" to "OP-DELIVERY",
    "FUEL" to "OP-FUEL",
    "LOADING_UNLOADING" to "OP-LOAD",
    "OPERATIONAL_MAINTENANCE" to "OP-VEHICLE-MAINT",
    "OTHER_OPERATING" to "OP-OTHER",
    "PERSONNEL" to "ADMIN-SALARIES",
    "BAGS" to "OP-BAGS",
    "MARKETING" to "COMM-ADVERTISING",
    "SHIPPING" to "OP-SHIPPING",
    "PACKAGING" to "OP-PROTECTION",
    "OFFICE_SUPPLIES" to "ADMIN-STATIONERY",
    "ADMIN_SERVICES" to "ADMIN-CONSULTING",
    "BANK_FEES" to "FIN-BANK-FEES",
    "SOFTWARE_SUBSCRIPTIONS" to "ADMIN-SOFTWARE",
    "OTHER_ADMINISTRATIVE" to "ADMIN-OTHER"
)

internal data class BudgetMovementContext(
    val normalizedType: String,
    val categoryId: Long? = null,
    val categoryCode: String? = null,
    val categoryName: String? = null,
    val costGroup: String? = null,
    val costCenterId: Long? = null,
    val periodId: Long? = null,
    val budgetLineId: Long? = null,
    val commitmentId: Long? = null,
    val controlStatus: String = "LEGACY"
)

private data class Defaults(val costCenterId: Long, val periodId: Long)
private data class CategoryRow(
    val id: Long,
    val code: String,
    val parentCode: String?,
    val name: String,
    val group: String,
    val level: Int,
    val movementAllowed: Boolean,
    val receiptRequired: Boolean,
    val approvalRequired: Boolean
)

class BudgetPlanningService(private val database: Database) {

    fun catalog(accountantId: Long): List<CostCategoryDto> {
        requireAccountant(accountantId)
        return database.dataSource.connection.use { connection ->
            val rows = connection.prepareStatement(
                """SELECT id,codigo,parent_codigo,nombre,grupo,nivel,permite_movimientos,
                          requiere_comprobante,requiere_aprobacion
                   FROM catalogo_costos WHERE activo=TRUE ORDER BY grupo,nivel,codigo"""
            ).use { statement ->
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(CategoryRow(
                        id = result.getLong(1),
                        code = result.getString(2),
                        parentCode = result.getString(3),
                        name = result.getString(4),
                        group = result.getString(5),
                        level = result.getInt(6),
                        movementAllowed = result.getBoolean(7),
                        receiptRequired = result.getBoolean(8),
                        approvalRequired = result.getBoolean(9)
                    ))
                } }
            }
            val children = rows.groupBy { it.parentCode }
            fun map(row: CategoryRow): CostCategoryDto = CostCategoryDto(
                id = row.id,
                code = row.code,
                name = row.name,
                group = row.group,
                level = row.level,
                movementAllowed = row.movementAllowed,
                receiptRequired = row.receiptRequired,
                approvalRequired = row.approvalRequired,
                children = children[row.code].orEmpty().map(::map)
            )
            children[null].orEmpty().map(::map)
        }
    }

    fun costCenters(accountantId: Long): List<CostCenterDto> {
        requireAccountant(accountantId)
        return database.transaction { connection ->
            ensureDefaultCostCenter(connection, accountantId)
            queryCostCenters(connection, accountantId)
        }
    }

    fun createCostCenter(accountantId: Long, request: CostCenterRequest): CostCenterDto {
        requireAccountant(accountantId)
        val code = request.code.trim().uppercase().replace(Regex("[^A-Z0-9_-]"), "-").take(40)
        if (!code.matches(Regex("[A-Z0-9][A-Z0-9_-]{1,39}"))) throw AppException("El código del centro de costo no es válido.")
        val name = request.name.trim().take(160)
        if (name.length < 3) throw AppException("Indica un nombre claro para el centro de costo.")
        val type = request.type.trim().uppercase()
        val allowedTypes = setOf("CENTRAL","WAREHOUSE","LOGISTICS","MARKETING","TECHNOLOGY","JOURNEY","BRANCH","ADMINISTRATOR","PROJECT","OTHER")
        if (type !in allowedTypes) throw AppException("Tipo de centro de costo inválido.")

        return database.transaction { connection ->
            request.parentId?.let { requireOwnedCostCenter(connection, accountantId, it) }
            request.responsibleUserId?.let { requireActiveUser(connection, it) }
            val id = connection.prepareStatement(
                """INSERT INTO centros_costo(contador_id,codigo,nombre,tipo,parent_id,responsable_usuario_id)
                   VALUES (?,?,?,?,?,?) RETURNING id"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setString(2, code)
                statement.setString(3, name)
                statement.setString(4, type)
                statement.setNullableLong(5, request.parentId)
                statement.setNullableLong(6, request.responsibleUserId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw AppException("No fue posible crear el centro de costo.")
                    result.getLong(1)
                }
            }
            queryCostCenters(connection, accountantId).first { it.id == id }
        }
    }

    fun periods(accountantId: Long): List<BudgetPeriodDto> {
        requireAccountant(accountantId)
        return database.dataSource.connection.use { connection -> queryPeriods(connection, accountantId) }
    }

    fun createPeriod(accountantId: Long, request: BudgetPeriodRequest): BudgetPeriodDto {
        requireAccountant(accountantId)
        val code = request.code.trim().uppercase().take(30)
        if (!code.matches(Regex("[A-Z0-9][A-Z0-9_.-]{1,29}"))) throw AppException("El código del periodo no es válido.")
        val name = request.name.trim().take(160)
        if (name.length < 3) throw AppException("Indica un nombre claro para el periodo.")
        val start = parseDate(request.startDate, "fecha de inicio")
        val end = parseDate(request.endDate, "fecha de cierre")
        if (end < start) throw AppException("La fecha de cierre no puede ser anterior al inicio.")
        val currency = request.currency.trim().uppercase()
        if (currency !in setOf("USD", "VES")) throw AppException("La moneda presupuestaria debe ser USD o VES.")

        return database.transaction { connection ->
            val overlaps = connection.prepareStatement(
                """SELECT EXISTS(SELECT 1 FROM periodos_presupuestarios
                   WHERE contador_id=? AND estado NOT IN ('CANCELLED','CLOSED')
                     AND daterange(fecha_inicio,fecha_fin,'[]') && daterange(?::date,?::date,'[]'))"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setObject(2, start)
                statement.setObject(3, end)
                statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
            }
            if (overlaps) throw AppException("Ya existe un periodo abierto que coincide con esas fechas.")
            val id = connection.prepareStatement(
                """INSERT INTO periodos_presupuestarios(
                       contador_id,codigo,nombre,fecha_inicio,fecha_fin,moneda,created_by
                   ) VALUES (?,?,?,?,?,?,?) RETURNING id"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setString(2, code)
                statement.setString(3, name)
                statement.setObject(4, start)
                statement.setObject(5, end)
                statement.setString(6, currency)
                statement.setLong(7, accountantId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
            queryPeriod(connection, accountantId, id)
        }
    }

    fun changePeriodStatus(accountantId: Long, periodId: Long, request: BudgetPeriodStatusRequest): BudgetPeriodDto {
        requireAccountant(accountantId)
        val target = request.status.trim().uppercase()
        return database.transaction { connection ->
            val current = queryPeriod(connection, accountantId, periodId)
            val transitions = mapOf(
                "DRAFT" to setOf("SUBMITTED", "CANCELLED"),
                "SUBMITTED" to setOf("DRAFT", "APPROVED", "CANCELLED"),
                "APPROVED" to setOf("ACTIVE", "CANCELLED"),
                "ACTIVE" to setOf("CLOSED"),
                "CLOSED" to emptySet(),
                "CANCELLED" to emptySet()
            )
            if (target !in transitions[current.status].orEmpty()) {
                throw AppException("No se puede cambiar el periodo de ${current.status} a $target.")
            }
            if (target in setOf("SUBMITTED", "APPROVED", "ACTIVE")) {
                val lines = connection.prepareStatement("SELECT COUNT(*) FROM partidas_presupuestarias WHERE periodo_id=? AND estado='ACTIVE'").use { statement ->
                    statement.setLong(1, periodId)
                    statement.executeQuery().use { result -> result.next(); result.getInt(1) }
                }
                if (lines == 0) throw AppException("Agrega al menos una partida antes de avanzar el periodo.")
            }
            connection.prepareStatement(
                """UPDATE periodos_presupuestarios SET estado=?,
                       approved_by=CASE WHEN ?='APPROVED' THEN ? ELSE approved_by END,
                       approved_at=CASE WHEN ?='APPROVED' THEN NOW() ELSE approved_at END,
                       closed_at=CASE WHEN ?='CLOSED' THEN NOW() ELSE closed_at END,
                       updated_at=NOW() WHERE id=? AND contador_id=?"""
            ).use { statement ->
                statement.setString(1, target)
                statement.setString(2, target)
                statement.setLong(3, accountantId)
                statement.setString(4, target)
                statement.setString(5, target)
                statement.setLong(6, periodId)
                statement.setLong(7, accountantId)
                statement.executeUpdate()
            }
            queryPeriod(connection, accountantId, periodId)
        }
    }

    fun saveBudgetLine(accountantId: Long, request: BudgetLineRequest): BudgetLineDto {
        requireAccountant(accountantId)
        val amount = MoneyMath.usd(request.approvedUsd)
        if (amount.signum() < 0) throw AppException("El monto aprobado no puede ser negativo.")
        return database.transaction { connection ->
            val period = queryPeriod(connection, accountantId, request.periodId)
            val autoActive = period.status == "ACTIVE" && period.code.startsWith("AUTO-")
            if (period.status != "DRAFT" && !autoActive) throw AppException("Las partidas solo se editan mientras el periodo está en borrador.")
            requireMovementCategory(connection, request.categoryId)
            requireOwnedCostCenter(connection, accountantId, request.costCenterId)
            if (autoActive) {
                val exists = connection.prepareStatement(
                    """SELECT EXISTS(SELECT 1 FROM partidas_presupuestarias
                       WHERE periodo_id=? AND categoria_costo_id=? AND centro_costo_id=?)"""
                ).use { statement ->
                    statement.setLong(1, request.periodId)
                    statement.setLong(2, request.categoryId)
                    statement.setLong(3, request.costCenterId)
                    statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
                }
                if (exists) throw AppException("La partida de un periodo activo debe modificarse mediante un ajuste presupuestario.")
            }
            val id = connection.prepareStatement(
                """INSERT INTO partidas_presupuestarias(
                       periodo_id,categoria_costo_id,centro_costo_id,monto_aprobado_usd,notas,created_by
                   ) VALUES (?,?,?,?,?,?)
                   ON CONFLICT(periodo_id,categoria_costo_id,centro_costo_id) DO UPDATE SET
                       monto_aprobado_usd=EXCLUDED.monto_aprobado_usd,
                       notas=EXCLUDED.notas,updated_at=NOW()
                   RETURNING id"""
            ).use { statement ->
                statement.setLong(1, request.periodId)
                statement.setLong(2, request.categoryId)
                statement.setLong(3, request.costCenterId)
                statement.setBigDecimal(4, amount)
                statement.setString(5, request.notes?.trim()?.take(500))
                statement.setLong(6, accountantId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
            queryBudgetLines(connection, request.periodId).first { it.id == id }
        }
    }

    fun createCommitment(accountantId: Long, request: BudgetCommitmentRequest): BudgetCommitmentDto {
        requireAccountant(accountantId)
        val amount = MoneyMath.positive(MoneyMath.usd(request.amountUsd), "Monto comprometido")
        val description = request.description.trim().take(500)
        if (description.length < 5) throw AppException("Describe claramente el compromiso presupuestario.")
        val idempotencyKey = request.idempotencyKey?.trim()?.takeIf(String::isNotBlank)?.take(120)
            ?: "COMMITMENT-$accountantId-${UUID.randomUUID()}"

        return database.transaction { connection ->
            val existing = connection.prepareStatement(
                "SELECT id FROM compromisos_presupuestarios WHERE contador_id=? AND idempotency_key=?"
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setString(2, idempotencyKey)
                statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
            }
            if (existing != null) return@transaction queryCommitment(connection, accountantId, existing)

            val line = queryBudgetLineForUpdate(connection, accountantId, request.budgetLineId)
            if (line.status != "ACTIVE") throw AppException("La partida no está disponible para nuevos compromisos.")
            val available = line.availableUsd.toMoney()
            if (amount > available) throw AppException("La partida solo tiene US$ ${available.toPlainString()} disponibles.")
            val approvalRequired = connection.prepareStatement(
                """SELECT c.requiere_aprobacion FROM catalogo_costos c
                   JOIN partidas_presupuestarias p ON p.categoria_costo_id=c.id WHERE p.id=?"""
            ).use { statement ->
                statement.setLong(1, request.budgetLineId)
                statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
            }
            val status = if (approvalRequired || amount >= BigDecimal("1000.00")) "PENDING" else "COMMITTED"
            val reference = "COM-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8).uppercase()}"
            val id = connection.prepareStatement(
                """INSERT INTO compromisos_presupuestarios(
                       referencia,partida_id,contador_id,monto_usd,descripcion,proveedor,numero_factura,
                       fecha_pago_esperada,estado,idempotency_key,created_by
                   ) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id"""
            ).use { statement ->
                statement.setString(1, reference)
                statement.setLong(2, request.budgetLineId)
                statement.setLong(3, accountantId)
                statement.setBigDecimal(4, amount)
                statement.setString(5, description)
                statement.setString(6, request.supplier?.trim()?.take(220))
                statement.setString(7, request.invoiceNumber?.trim()?.take(120))
                statement.setObject(8, request.expectedPaymentDate?.let { parseDate(it, "fecha esperada de pago") })
                statement.setString(9, status)
                statement.setString(10, idempotencyKey)
                statement.setLong(11, accountantId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
            if (status == "PENDING") {
                connection.prepareStatement(
                    """INSERT INTO solicitudes_doble_aprobacion(
                           action_type,entity_type,entity_id,amount_usd,description,requested_by
                       ) VALUES ('BUDGET_COMMITMENT','BUDGET_COMMITMENT',?,?,?,?)"""
                ).use { statement ->
                    statement.setLong(1, id)
                    statement.setBigDecimal(2, amount)
                    statement.setString(3, "Autorizar compromiso $reference: $description")
                    statement.setLong(4, accountantId)
                    statement.executeUpdate()
                }
            }
            queryCommitment(connection, accountantId, id)
        }
    }

    fun changeCommitmentStatus(
        accountantId: Long,
        commitmentId: Long,
        request: BudgetCommitmentStatusRequest
    ): BudgetCommitmentDto {
        requireAccountant(accountantId)
        val target = request.status.trim().uppercase()
        return database.transaction { connection ->
            val current = queryCommitment(connection, accountantId, commitmentId)
            val transitions = mapOf(
                "PENDING" to setOf("CANCELLED"),
                "APPROVED" to setOf("COMMITTED", "CANCELLED"),
                "COMMITTED" to setOf("CANCELLED"),
                "PAID" to setOf("REVERSED"),
                "CANCELLED" to emptySet(),
                "REVERSED" to emptySet()
            )
            if (target !in transitions[current.status].orEmpty()) {
                throw AppException("No se puede cambiar el compromiso de ${current.status} a $target.")
            }
            if (target == "REVERSED" && current.expenseMovementId != null) {
                throw AppException("Primero debe reversarse el movimiento financiero asociado.")
            }
            connection.prepareStatement(
                "UPDATE compromisos_presupuestarios SET estado=?,motivo_estado=?,updated_at=NOW() WHERE id=? AND contador_id=?"
            ).use { statement ->
                statement.setString(1, target)
                statement.setString(2, request.reason?.trim()?.take(500))
                statement.setLong(3, commitmentId)
                statement.setLong(4, accountantId)
                statement.executeUpdate()
            }
            queryCommitment(connection, accountantId, commitmentId)
        }
    }

    fun adjustBudget(accountantId: Long, request: BudgetAdjustmentRequest): BudgetControlDashboardDto {
        requireAccountant(accountantId)
        val type = request.type.trim().uppercase()
        if (type !in setOf("INCREASE", "REDUCTION", "TRANSFER")) throw AppException("Tipo de ajuste presupuestario inválido.")
        val amount = MoneyMath.positive(MoneyMath.usd(request.amountUsd), "Monto del ajuste")
        val reason = request.reason.trim().take(500)
        if (reason.length < 8) throw AppException("Explica el motivo del ajuste presupuestario.")
        val key = request.idempotencyKey?.trim()?.takeIf(String::isNotBlank)?.take(120)
            ?: "ADJUSTMENT-$accountantId-${UUID.randomUUID()}"

        val periodId = database.transaction { connection ->
            val duplicatePeriod = connection.prepareStatement(
                """SELECT COALESCE(po.periodo_id,pd.periodo_id)
                   FROM ajustes_presupuestarios a
                   LEFT JOIN partidas_presupuestarias po ON po.id=a.partida_origen_id
                   LEFT JOIN partidas_presupuestarias pd ON pd.id=a.partida_destino_id
                   WHERE a.contador_id=? AND a.idempotency_key=?"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setString(2, key)
                statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
            }
            if (duplicatePeriod != null) return@transaction duplicatePeriod

            val source = request.sourceLineId?.let { queryBudgetLineForUpdate(connection, accountantId, it) }
            val destination = request.destinationLineId?.let { queryBudgetLineForUpdate(connection, accountantId, it) }
            when (type) {
                "INCREASE" -> if (source != null || destination == null) throw AppException("El aumento requiere únicamente una partida de destino.")
                "REDUCTION" -> if (source == null || destination != null) throw AppException("La reducción requiere únicamente una partida de origen.")
                "TRANSFER" -> if (source == null || destination == null || source.id == destination.id) throw AppException("La transferencia requiere dos partidas diferentes.")
            }
            if (source != null && amount > source.availableUsd.toMoney()) {
                throw AppException("La partida de origen no tiene fondos libres suficientes.")
            }
            if (source != null && destination != null && source.periodId != destination.periodId) {
                throw AppException("Las transferencias deben realizarse dentro del mismo periodo.")
            }
            source?.let { line ->
                connection.prepareStatement(
                    "UPDATE partidas_presupuestarias SET monto_modificado_usd=monto_modificado_usd-?,updated_at=NOW() WHERE id=?"
                ).use { statement -> statement.setBigDecimal(1, amount); statement.setLong(2, line.id); statement.executeUpdate() }
            }
            destination?.let { line ->
                connection.prepareStatement(
                    "UPDATE partidas_presupuestarias SET monto_modificado_usd=monto_modificado_usd+?,updated_at=NOW() WHERE id=?"
                ).use { statement -> statement.setBigDecimal(1, amount); statement.setLong(2, line.id); statement.executeUpdate() }
            }
            connection.prepareStatement(
                """INSERT INTO ajustes_presupuestarios(
                       referencia,contador_id,tipo,partida_origen_id,partida_destino_id,monto_usd,
                       motivo,idempotency_key,created_by
                   ) VALUES (?,?,?,?,?,?,?,?,?)"""
            ).use { statement ->
                statement.setString(1, "AJU-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8).uppercase()}")
                statement.setLong(2, accountantId)
                statement.setString(3, type)
                statement.setNullableLong(4, request.sourceLineId)
                statement.setNullableLong(5, request.destinationLineId)
                statement.setBigDecimal(6, amount)
                statement.setString(7, reason)
                statement.setString(8, key)
                statement.setLong(9, accountantId)
                statement.executeUpdate()
            }
            source?.periodId ?: destination?.periodId ?: throw AppException("No fue posible identificar el periodo.")
        }
        return dashboard(accountantId, periodId)
    }

    fun dashboard(accountantId: Long, periodId: Long? = null): BudgetControlDashboardDto {
        requireAccountant(accountantId)
        return database.transaction { connection ->
            ensureDefaultCostCenter(connection, accountantId)
            val selectedPeriodId = periodId ?: connection.prepareStatement(
                """SELECT id FROM periodos_presupuestarios WHERE contador_id=?
                   ORDER BY CASE WHEN estado='ACTIVE' THEN 0 ELSE 1 END,fecha_inicio DESC LIMIT 1"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
            } ?: ensureDefaults(connection, accountantId).periodId
            val period = queryPeriod(connection, accountantId, selectedPeriodId)
            val lines = queryBudgetLines(connection, selectedPeriodId)
            val approved = lines.sumOfMoney { it.approvedUsd }
            val modified = lines.sumOfMoney { it.modifiedUsd }
            val current = lines.sumOfMoney { it.currentUsd }
            val committed = lines.sumOfMoney { it.committedUsd }
            val executed = lines.sumOfMoney { it.executedUsd }
            val available = current.subtract(committed).subtract(executed).money()
            val execution = percent(executed, current)
            val alerts = buildList {
                lines.forEach { line ->
                    when {
                        line.executionPercent >= 100.0 -> add(BudgetAlertDto("BUDGET_EXHAUSTED", "CRITICAL", "${line.categoryName} agotó su presupuesto.", line.id, line.executionPercent))
                        line.executionPercent >= 85.0 -> add(BudgetAlertDto("BUDGET_AT_85", "HIGH", "${line.categoryName} alcanzó el 85 % de ejecución.", line.id, line.executionPercent))
                        line.executionPercent >= 70.0 -> add(BudgetAlertDto("BUDGET_AT_70", "MEDIUM", "${line.categoryName} alcanzó el 70 % de ejecución.", line.id, line.executionPercent))
                    }
                    if (line.availableUsd < 0.0) add(BudgetAlertDto("OVER_BUDGET", "CRITICAL", "${line.categoryName} presenta sobreejecución.", line.id, line.executionPercent))
                }
                val unbudgeted = scalarLong(connection,
                    """SELECT COUNT(*) FROM movimientos_presupuestarios
                       WHERE contador_id=? AND periodo_presupuestario_id=? AND estado='COMPLETED'
                         AND tipo IN ('INVENTORY_COST','OPERATING_EXPENSE','ADMINISTRATIVE_EXPENSE','COMMERCIAL_EXPENSE','FINANCIAL_EXPENSE','EXTRAORDINARY_EXPENSE')
                         AND partida_presupuestaria_id IS NULL""", accountantId, selectedPeriodId)
                if (unbudgeted > 0) add(BudgetAlertDto("UNBUDGETED_EXPENSES", "HIGH", "Hay $unbudgeted gastos sin partida presupuestaria."))
                val missingReceipts = scalarLong(connection,
                    """SELECT COUNT(*) FROM movimientos_presupuestarios m
                       JOIN catalogo_costos c ON c.id=m.categoria_costo_id
                       WHERE m.contador_id=? AND m.periodo_presupuestario_id=? AND m.estado='COMPLETED'
                         AND c.requiere_comprobante=TRUE AND COALESCE(BTRIM(m.comprobante_path),'')=''""", accountantId, selectedPeriodId)
                if (missingReceipts > 0) add(BudgetAlertDto("MISSING_RECEIPTS", "HIGH", "Hay $missingReceipts gastos que requieren comprobante."))
                val otherExpenses = scalarLong(connection,
                    """SELECT COUNT(*) FROM movimientos_presupuestarios m
                       JOIN catalogo_costos c ON c.id=m.categoria_costo_id
                       WHERE m.contador_id=? AND m.periodo_presupuestario_id=? AND m.estado='COMPLETED'
                         AND c.codigo LIKE '%-OTHER'""", accountantId, selectedPeriodId)
                if (otherExpenses > 0) add(BudgetAlertDto("OTHER_EXPENSES", "MEDIUM", "Hay $otherExpenses gastos clasificados como Otros; revisa su justificación."))
            }
            BudgetControlDashboardDto(
                period = period,
                approvedUsd = approved.toDouble(),
                modifiedUsd = modified.toDouble(),
                currentUsd = current.toDouble(),
                committedUsd = committed.toDouble(),
                executedUsd = executed.toDouble(),
                availableUsd = available.toDouble(),
                executionPercent = execution,
                lines = lines,
                commitments = queryCommitments(connection, accountantId, selectedPeriodId),
                alerts = alerts,
                calculatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
            )
        }
    }

    internal fun prepareMovementContext(
        connection: Connection,
        accountantId: Long,
        requestedType: String,
        request: BudgetMovementRequest,
        amount: BigDecimal
    ): BudgetMovementContext {
        if (requestedType !in BUDGET_EXPENSE_TYPES) return BudgetMovementContext(requestedType)
        val defaults = ensureDefaults(connection, accountantId)
        val explicitCategory = request.costCategoryCode?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        val legacyCode = request.expenseCategory?.trim()?.uppercase()?.let(LEGACY_COST_CATEGORY_CODES::get)
        val fallback = when (requestedType) {
            "INVENTORY_COST" -> "INV-ADJUSTMENTS"
            "OPERATING_EXPENSE" -> "OP-OTHER"
            "ADMINISTRATIVE_EXPENSE" -> "ADMIN-OTHER"
            "COMMERCIAL_EXPENSE" -> "COMM-OTHER"
            "FINANCIAL_EXPENSE" -> "FIN-OTHER"
            else -> "EXT-AUTHORIZED-ADJUSTMENT"
        }
        val categoryCode = explicitCategory ?: legacyCode ?: fallback
        val category = connection.prepareStatement(
            """SELECT id,codigo,nombre,grupo,permite_movimientos FROM catalogo_costos
               WHERE codigo=? AND activo=TRUE"""
        ).use { statement ->
            statement.setString(1, categoryCode)
            statement.executeQuery().use { result ->
                if (!result.next()) throw AppException("La categoría de costo $categoryCode no existe o está desactivada.")
                CategoryRow(result.getLong(1), result.getString(2), null, result.getString(3), result.getString(4), 3, result.getBoolean(5), true, false)
            }
        }
        if (!category.movementAllowed) throw AppException("Selecciona una subcategoría de costo que permita movimientos.")
        val normalizedType = GROUP_MOVEMENT_TYPES[category.group]
            ?: throw AppException("El grupo de costo no tiene un tipo financiero configurado.")
        if (explicitCategory != null && requestedType != normalizedType) {
            throw AppException("La categoría ${category.name} corresponde a ${category.group} y no al tipo $requestedType.")
        }

        val costCenterId = request.costCenterId?.also { requireOwnedCostCenter(connection, accountantId, it) }
            ?: defaults.costCenterId
        request.responsibleUserId?.let { requireActiveUser(connection, it) }
        val periodId = request.budgetPeriodId?.also { queryPeriod(connection, accountantId, it) }
            ?: defaults.periodId
        val lineId = request.budgetLineId?.also { id ->
            val line = queryBudgetLineForUpdate(connection, accountantId, id)
            if (line.periodId != periodId || line.categoryId != category.id || line.costCenterId != costCenterId) {
                throw AppException("La partida no coincide con el periodo, la categoría y el centro de costo del gasto.")
            }
        } ?: connection.prepareStatement(
            """SELECT id FROM partidas_presupuestarias
               WHERE periodo_id=? AND categoria_costo_id=? AND centro_costo_id=? AND estado='ACTIVE'"""
        ).use { statement ->
            statement.setLong(1, periodId)
            statement.setLong(2, category.id)
            statement.setLong(3, costCenterId)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }

        val commitmentId = request.commitmentId
        val controlStatus = when {
            commitmentId != null -> {
                if (lineId == null) throw AppException("El compromiso debe estar asociado a una partida.")
                val commitment = queryCommitment(connection, accountantId, commitmentId)
                if (commitment.budgetLineId != lineId || commitment.status !in setOf("APPROVED", "COMMITTED")) {
                    throw AppException("El compromiso no está disponible para esta partida.")
                }
                if (commitment.amountUsd.toMoney() != amount.money()) {
                    throw AppException("El gasto debe coincidir con los US$ ${commitment.amountUsd} comprometidos.")
                }
                "COMMITMENT_SETTLED"
            }
            lineId == null -> "UNBUDGETED"
            else -> {
                val line = queryBudgetLineForUpdate(connection, accountantId, lineId)
                if (amount > line.availableUsd.toMoney()) {
                    throw AppException("La partida solo tiene US$ ${line.availableUsd.toMoney().toPlainString()} disponibles.")
                }
                "WITHIN_BUDGET"
            }
        }
        return BudgetMovementContext(
            normalizedType = normalizedType,
            categoryId = category.id,
            categoryCode = category.code,
            categoryName = category.name,
            costGroup = category.group,
            costCenterId = costCenterId,
            periodId = periodId,
            budgetLineId = lineId,
            commitmentId = commitmentId,
            controlStatus = controlStatus
        )
    }

    internal fun settleCommitment(connection: Connection, accountantId: Long, commitmentId: Long?, movementId: Long) {
        if (commitmentId == null) return
        val updated = connection.prepareStatement(
            """UPDATE compromisos_presupuestarios SET estado='PAID',movimiento_gasto_id=?,updated_at=NOW()
               WHERE id=? AND contador_id=? AND estado IN ('APPROVED','COMMITTED')"""
        ).use { statement ->
            statement.setLong(1, movementId)
            statement.setLong(2, commitmentId)
            statement.setLong(3, accountantId)
            statement.executeUpdate()
        }
        if (updated != 1) throw AppException("No fue posible liquidar el compromiso presupuestario.")
    }

    private fun requireAccountant(accountantId: Long) {
        val valid = database.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT EXISTS(SELECT 1 FROM contadores c JOIN usuarios u ON u.id=c.user_id
                   WHERE c.user_id=? AND u.role='ACCOUNTANT' AND u.account_status='ACTIVE')"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
            }
        }
        if (!valid) throw ForbiddenException("Solo el Contador activo puede administrar el presupuesto.")
    }

    private fun ensureDefaults(connection: Connection, accountantId: Long): Defaults {
        val centerId = ensureDefaultCostCenter(connection, accountantId)
        val today = LocalDate.now(ZoneOffset.UTC)
        val existingPeriodId = connection.prepareStatement(
            """SELECT id FROM periodos_presupuestarios
               WHERE contador_id=? AND ?::date BETWEEN fecha_inicio AND fecha_fin AND estado='ACTIVE'
               ORDER BY fecha_inicio DESC LIMIT 1"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.setObject(2, today)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }
        if (existingPeriodId != null) return Defaults(centerId, existingPeriodId)

        val overlappingPeriod = connection.prepareStatement(
            """SELECT estado FROM periodos_presupuestarios
               WHERE contador_id=? AND ?::date BETWEEN fecha_inicio AND fecha_fin AND estado<>'CANCELLED'
               ORDER BY fecha_inicio DESC LIMIT 1"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.setObject(2, today)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        if (overlappingPeriod != null) {
            throw AppException("No hay un periodo presupuestario activo para la fecha actual. El periodo existente está en estado $overlappingPeriod.")
        }

        val month = YearMonth.from(today)
        val periodId = connection.prepareStatement(
            """INSERT INTO periodos_presupuestarios(
                   contador_id,codigo,nombre,fecha_inicio,fecha_fin,estado,moneda,created_by,approved_by,approved_at
               ) VALUES (?,?,?,?,?,'ACTIVE','USD',?,?,NOW()) RETURNING id"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.setString(2, "AUTO-$month-${UUID.randomUUID().toString().take(6).uppercase()}")
            statement.setString(3, "Operación $month")
            statement.setObject(4, month.atDay(1))
            statement.setObject(5, month.atEndOfMonth())
            statement.setLong(6, accountantId)
            statement.setLong(7, accountantId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
        return Defaults(centerId, periodId)
    }

    private fun ensureDefaultCostCenter(connection: Connection, accountantId: Long): Long =
        connection.prepareStatement(
            """INSERT INTO centros_costo(contador_id,codigo,nombre,tipo)
               VALUES (?,'CENTRAL','Administración central','CENTRAL')
               ON CONFLICT(contador_id,codigo) DO UPDATE SET activo=TRUE,updated_at=NOW()
               RETURNING id"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }

    private fun queryCostCenters(connection: Connection, accountantId: Long): List<CostCenterDto> =
        connection.prepareStatement(
            """SELECT id,codigo,nombre,tipo,parent_id,responsable_usuario_id,activo,created_at
               FROM centros_costo WHERE contador_id=? ORDER BY activo DESC,nombre"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(CostCenterDto(
                    id = result.getLong(1), code = result.getString(2), name = result.getString(3),
                    type = result.getString(4), parentId = result.getLongOrNull(5),
                    responsibleUserId = result.getLongOrNull(6), active = result.getBoolean(7),
                    createdAt = result.getTimestamp(8).toInstant().toString()
                ))
            } }
        }

    private fun queryPeriods(connection: Connection, accountantId: Long): List<BudgetPeriodDto> =
        connection.prepareStatement(
            """SELECT id,codigo,nombre,fecha_inicio,fecha_fin,estado,moneda,created_at
               FROM periodos_presupuestarios WHERE contador_id=? ORDER BY fecha_inicio DESC"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(result.toBudgetPeriodDto())
            } }
        }

    private fun queryPeriod(connection: Connection, accountantId: Long, periodId: Long): BudgetPeriodDto =
        connection.prepareStatement(
            """SELECT id,codigo,nombre,fecha_inicio,fecha_fin,estado,moneda,created_at
               FROM periodos_presupuestarios WHERE id=? AND contador_id=?"""
        ).use { statement ->
            statement.setLong(1, periodId)
            statement.setLong(2, accountantId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("Periodo presupuestario no encontrado.")
                result.toBudgetPeriodDto()
            }
        }

    private fun queryBudgetLines(connection: Connection, periodId: Long): List<BudgetLineDto> =
        connection.prepareStatement(
            """SELECT p.id,p.periodo_id,c.id,c.codigo,c.nombre,c.grupo,cc.id,cc.nombre,
                      p.monto_aprobado_usd,p.monto_modificado_usd,
                      COALESCE((SELECT SUM(cp.monto_usd) FROM compromisos_presupuestarios cp
                                WHERE cp.partida_id=p.id AND cp.estado IN ('APPROVED','COMMITTED')),0),
                      COALESCE((SELECT SUM(m.monto_usd) FROM movimientos_presupuestarios m
                                WHERE m.partida_presupuestaria_id=p.id AND m.estado='COMPLETED'
                                  AND m.tipo IN ('INVENTORY_COST','OPERATING_EXPENSE','ADMINISTRATIVE_EXPENSE','COMMERCIAL_EXPENSE','FINANCIAL_EXPENSE','EXTRAORDINARY_EXPENSE')),0),
                      p.estado
               FROM partidas_presupuestarias p
               JOIN catalogo_costos c ON c.id=p.categoria_costo_id
               JOIN centros_costo cc ON cc.id=p.centro_costo_id
               WHERE p.periodo_id=? ORDER BY c.grupo,c.codigo,cc.nombre"""
        ).use { statement ->
            statement.setLong(1, periodId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val approved = result.getBigDecimal(9).money()
                    val modified = result.getBigDecimal(10).money()
                    val current = approved.add(modified).money()
                    val committed = result.getBigDecimal(11).money()
                    val executed = result.getBigDecimal(12).money()
                    val available = current.subtract(committed).subtract(executed).money()
                    add(BudgetLineDto(
                        id = result.getLong(1), periodId = result.getLong(2), categoryId = result.getLong(3),
                        categoryCode = result.getString(4), categoryName = result.getString(5), costGroup = result.getString(6),
                        costCenterId = result.getLong(7), costCenterName = result.getString(8),
                        approvedUsd = approved.toDouble(), modifiedUsd = modified.toDouble(), currentUsd = current.toDouble(),
                        committedUsd = committed.toDouble(), executedUsd = executed.toDouble(), availableUsd = available.toDouble(),
                        executionPercent = percent(executed, current), status = result.getString(13)
                    ))
                }
            } }
        }

    private fun queryBudgetLineForUpdate(connection: Connection, accountantId: Long, lineId: Long): BudgetLineDto {
        connection.prepareStatement(
            """SELECT p.id FROM partidas_presupuestarias p
               JOIN periodos_presupuestarios pe ON pe.id=p.periodo_id
               WHERE p.id=? AND pe.contador_id=? FOR UPDATE"""
        ).use { statement ->
            statement.setLong(1, lineId)
            statement.setLong(2, accountantId)
            statement.executeQuery().use { result -> if (!result.next()) throw NotFoundException("Partida presupuestaria no encontrada.") }
        }
        return queryBudgetLinesForLine(connection, lineId)
    }

    private fun queryBudgetLinesForLine(connection: Connection, lineId: Long): BudgetLineDto {
        val periodId = connection.prepareStatement("SELECT periodo_id FROM partidas_presupuestarias WHERE id=?").use { statement ->
            statement.setLong(1, lineId)
            statement.executeQuery().use { result -> if (!result.next()) throw NotFoundException("Partida presupuestaria no encontrada."); result.getLong(1) }
        }
        return queryBudgetLines(connection, periodId).firstOrNull { it.id == lineId }
            ?: throw NotFoundException("Partida presupuestaria no encontrada.")
    }

    private fun queryCommitments(connection: Connection, accountantId: Long, periodId: Long): List<BudgetCommitmentDto> =
        connection.prepareStatement(
            """SELECT cp.id,cp.referencia,cp.partida_id,cp.monto_usd,cp.descripcion,cp.proveedor,
                      cp.numero_factura,cp.fecha_pago_esperada,cp.estado,cp.movimiento_gasto_id,cp.created_at
               FROM compromisos_presupuestarios cp
               JOIN partidas_presupuestarias p ON p.id=cp.partida_id
               WHERE cp.contador_id=? AND p.periodo_id=? ORDER BY cp.created_at DESC"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.setLong(2, periodId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(result.toBudgetCommitmentDto())
            } }
        }

    private fun queryCommitment(connection: Connection, accountantId: Long, commitmentId: Long): BudgetCommitmentDto =
        connection.prepareStatement(
            """SELECT id,referencia,partida_id,monto_usd,descripcion,proveedor,numero_factura,
                      fecha_pago_esperada,estado,movimiento_gasto_id,created_at
               FROM compromisos_presupuestarios WHERE id=? AND contador_id=?"""
        ).use { statement ->
            statement.setLong(1, commitmentId)
            statement.setLong(2, accountantId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("Compromiso presupuestario no encontrado.")
                result.toBudgetCommitmentDto()
            }
        }

    private fun requireMovementCategory(connection: Connection, categoryId: Long) {
        val valid = connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM catalogo_costos WHERE id=? AND activo=TRUE AND permite_movimientos=TRUE)"
        ).use { statement ->
            statement.setLong(1, categoryId)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
        if (!valid) throw AppException("Selecciona una subcategoría de costo activa.")
    }

    private fun requireOwnedCostCenter(connection: Connection, accountantId: Long, centerId: Long) {
        val valid = connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM centros_costo WHERE id=? AND contador_id=? AND activo=TRUE)"
        ).use { statement ->
            statement.setLong(1, centerId)
            statement.setLong(2, accountantId)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
        if (!valid) throw AppException("El centro de costo no existe o está desactivado.")
    }

    private fun requireActiveUser(connection: Connection, userId: Long) {
        val valid = connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM usuarios WHERE id=? AND account_status='ACTIVE')"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
        if (!valid) throw AppException("El responsable seleccionado no es un usuario activo.")
    }

    private fun scalarLong(connection: Connection, sql: String, vararg values: Long): Long =
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setLong(index + 1, value) }
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }

    private fun parseDate(value: String, label: String): LocalDate =
        runCatching { LocalDate.parse(value.trim()) }.getOrElse { throw AppException("La $label debe usar el formato AAAA-MM-DD.") }
}

private fun java.sql.ResultSet.toBudgetPeriodDto(): BudgetPeriodDto = BudgetPeriodDto(
    id = getLong(1), code = getString(2), name = getString(3),
    startDate = getObject(4, LocalDate::class.java).toString(),
    endDate = getObject(5, LocalDate::class.java).toString(),
    status = getString(6), currency = getString(7), createdAt = getTimestamp(8).toInstant().toString()
)

private fun java.sql.ResultSet.toBudgetCommitmentDto(): BudgetCommitmentDto = BudgetCommitmentDto(
    id = getLong(1), reference = getString(2), budgetLineId = getLong(3), amountUsd = getBigDecimal(4).toDouble(),
    description = getString(5), supplier = getString(6), invoiceNumber = getString(7),
    expectedPaymentDate = getObject(8, LocalDate::class.java)?.toString(), status = getString(9),
    expenseMovementId = getLongOrNull(10), createdAt = getTimestamp(11).toInstant().toString()
)

private fun java.sql.ResultSet.getLongOrNull(index: Int): Long? = getLong(index).let { if (wasNull()) null else it }

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)
}

private fun BigDecimal?.money(): BigDecimal = (this ?: BigDecimal.ZERO).setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)
private fun Double.toMoney(): BigDecimal = MoneyMath.usd(this)
private fun Iterable<BudgetLineDto>.sumOfMoney(selector: (BudgetLineDto) -> Double): BigDecimal =
    fold(BigDecimal.ZERO) { total, item -> total.add(selector(item).toMoney(), MoneyMath.CONTEXT) }.money()

private fun percent(value: BigDecimal, total: BigDecimal): Double =
    if (total.signum() <= 0) 0.0 else value.multiply(BigDecimal("100"), MoneyMath.CONTEXT)
        .divide(total, 2, RoundingMode.HALF_EVEN).toDouble()
