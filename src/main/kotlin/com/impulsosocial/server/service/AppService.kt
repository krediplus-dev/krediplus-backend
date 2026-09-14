package com.impulsosocial.server.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.impulsosocial.server.CREDICASH_APP_VERSION
import com.impulsosocial.server.config.AppConfig
import com.impulsosocial.server.db.Database
import com.impulsosocial.server.model.*
import com.impulsosocial.server.integrations.PushNotificationService
import com.impulsosocial.server.integrations.BcvRateService
import com.impulsosocial.server.integrations.BcvRate
import com.impulsosocial.server.integrations.RecaptchaService
import com.impulsosocial.server.integrations.SmtpEmailService
import com.impulsosocial.server.security.PasswordSecurity
import com.impulsosocial.server.security.PasswordPolicy
import com.impulsosocial.server.security.PinPolicy
import com.impulsosocial.server.security.Roles
import java.io.File
import java.math.RoundingMode
import java.math.BigDecimal
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.imageio.ImageIO
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.Locale
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class AppException(message: String) : IllegalArgumentException(message)
class ForbiddenException(message: String = "No tienes permiso para realizar esta acción.") : IllegalStateException(message)
class NotFoundException(message: String) : NoSuchElementException(message)

data class LoginDecision(val response: LoginResponse)

private data class RegistrationCreation(val userId: Long, val registrationToken: String)
private data class RecoveryUser(val id: Long, val username: String, val email: String)
private data class RecoveryCodeRow(val id: Long, val hash: String, val attempts: Int)
private enum class RecoveryCodeResult { VALID, INVALID, LOCKED, MISSING }
private data class UserProvenance(val createdByUserId: Long?, val createdByUsername: String?, val createdByName: String?, val registrationSource: String?)
data class StoredUpload(val relativePath: String, val absoluteFile: File)

private data class AdminWalletCompliance(
    val blocked: Boolean,
    val reason: String?,
    val evaluatedInstallments: Int,
    val approvedInstallments: Int,
    val evaluatedUsers: Int
)

private data class NotificationDeliveryTarget(
    val notificationId: Long,
    val userId: Long,
    val token: String
)


private data class PromotionApplication(
    val id: Long,
    val name: String,
    val discountType: String,
    val discountValue: BigDecimal,
    val targetType: String,
    val targetId: Long,
    val discountAmount: BigDecimal
)

internal fun shouldBlockAdminWallet(
    evaluatedInstallments: Int,
    approvedInstallments: Int,
    evaluatedUsers: Int
): Boolean = evaluatedUsers >= 3 && evaluatedInstallments >= 3 && approvedInstallments < 2

internal fun shouldRecoverAccountantCredentials(
    recoveryPassword: String?,
    configuredPassword: String,
    bootstrapConfigured: Boolean
): Boolean {
    if (!bootstrapConfigured || recoveryPassword == null || configuredPassword.isBlank()) return false
    return MessageDigest.isEqual(
        recoveryPassword.toByteArray(Charsets.UTF_8),
        configuredPassword.toByteArray(Charsets.UTF_8)
    )
}

private val OPERATING_EXPENSE_CATEGORIES = linkedMapOf(
    "TRANSPORTATION" to "Transporte",
    "LOGISTICS" to "Logística",
    "PER_DIEM" to "Viáticos",
    "DELIVERY" to "Delivery",
    "FUEL" to "Combustible",
    "LOADING_UNLOADING" to "Carga y descarga",
    "OPERATIONAL_MAINTENANCE" to "Mantenimiento operativo",
    "OTHER_OPERATING" to "Otros operativos"
)

private val ADMINISTRATIVE_EXPENSE_CATEGORIES = linkedMapOf(
    "PERSONNEL" to "Personal",
    "BAGS" to "Bolsas",
    "MARKETING" to "Marketing",
    "SHIPPING" to "Envíos",
    "PACKAGING" to "Empaques",
    "OFFICE_SUPPLIES" to "Papelería y oficina",
    "ADMIN_SERVICES" to "Servicios administrativos",
    "BANK_FEES" to "Comisiones bancarias",
    "SOFTWARE_SUBSCRIPTIONS" to "Software y suscripciones",
    "OTHER_ADMINISTRATIVE" to "Otros administrativos"
)

private fun expenseCategoryLabel(type: String, category: String?): String? = when (type.uppercase()) {
    "OPERATING_EXPENSE" -> OPERATING_EXPENSE_CATEGORIES[category?.uppercase()]
    "ADMINISTRATIVE_EXPENSE" -> ADMINISTRATIVE_EXPENSE_CATEGORIES[category?.uppercase()]
    else -> null
}

class AppService(
    private val database: Database,
    private val config: AppConfig,
    private val passwordSecurity: PasswordSecurity,
    private val pushNotifications: PushNotificationService,
    private val bcvRateService: BcvRateService,
    private val recaptchaService: RecaptchaService,
    private val emailService: SmtpEmailService
) {
    private val logger = LoggerFactory.getLogger(AppService::class.java)
    private val gson = Gson()
    private val secureRandom = SecureRandom()
    private val budgetPlanningService = BudgetPlanningService(database)
    private val MAX_LOGIN_ATTEMPTS = 5
    private val MAX_PIN_ATTEMPTS = 5
    private val uploadRoot = File(config.uploadDir).apply { mkdirs() }
    private val uploadAccessPolicy = UploadAccessPolicy(config.jwtSecret)
    private val notificationExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "credicash-push").apply { isDaemon = true }
    }
    private data class WalletTransferNotificationContext(
        val userId: Long,
        val reference: String,
        val availableBalanceUsd: Double
    )

    @Volatile private var walletSchemaReady = false
    @Volatile private var walletV28SchemaReady = false
    private val walletSchemaLock = Any()
    private val walletV28SchemaLock = Any()

    /**
     * Returns the canonical role stored in PostgreSQL only for an account that is
     * currently allowed to keep an authenticated session. Authorization never
     * trusts a potentially stale role embedded in an old JWT.
     */
    fun accountRole(userId: Long): String? = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT role FROM usuarios WHERE id=?").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (result.next()) Roles.canonical(result.getString("role")) else null
            }
        }
    }

    fun sessionRole(sessionId: UUID): String? = database.dataSource.connection.use { connection ->
        database.ensureAuthenticationSchema(connection)
        connection.prepareStatement(
            """
            SELECT u.role,u.account_status,u.verification_status
            FROM sesiones_usuario s
            JOIN usuarios u ON u.id=s.user_id
            WHERE s.id=? AND s.revoked_at IS NULL AND s.expires_at>NOW()
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use resultUse@ { result ->
                if (!result.next()) return@resultUse null
                if (result.getString("account_status") != "ACTIVE" || result.getString("verification_status") != "VERIFIED") {
                    return@resultUse null
                }
                Roles.canonical(result.getString("role"))
            }
        }
    }

    fun sessionRole(userId: Long): String? = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT role, account_status, verification_status FROM usuarios WHERE id=?"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use resultUse@ { result ->
                if (!result.next()) return@resultUse null
                val accountStatus = result.getString("account_status")
                val verificationStatus = result.getString("verification_status")
                if (accountStatus != "ACTIVE" || verificationStatus != "VERIFIED") return@resultUse null
                Roles.canonical(result.getString("role"))
            }
        }
    }

    fun sessionRole(userId: Long, sessionId: UUID): String? = database.dataSource.connection.use { connection ->
        database.ensureAuthenticationSchema(connection)
        val role = connection.prepareStatement(
            """
            SELECT u.role,u.account_status,u.verification_status
            FROM usuarios u
            JOIN sesiones_usuario s ON s.user_id=u.id
            WHERE u.id=? AND s.id=? AND s.revoked_at IS NULL AND s.expires_at>NOW()
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setObject(2, sessionId)
            statement.executeQuery().use resultUse@ { result ->
                if (!result.next()) return@resultUse null
                if (result.getString("account_status") != "ACTIVE" || result.getString("verification_status") != "VERIFIED") {
                    return@resultUse null
                }
                Roles.canonical(result.getString("role"))
            }
        }
        if (role != null) {
            connection.prepareStatement(
                """
                UPDATE sesiones_usuario
                SET last_used_at=NOW(),last_heartbeat_at=NOW()
                WHERE id=? AND user_id=? AND revoked_at IS NULL
                  AND (last_heartbeat_at IS NULL OR last_heartbeat_at < NOW() - INTERVAL '30 seconds')
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
        role
    }

    fun heartbeatSession(userId: Long, sessionId: UUID) {
        val updated = database.dataSource.connection.use { connection ->
            database.ensureAuthenticationSchema(connection)
            connection.prepareStatement(
                "UPDATE sesiones_usuario SET last_used_at=NOW(),last_heartbeat_at=NOW() WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>NOW()"
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
        if (updated != 1) throw ForbiddenException("La sesión ya no está activa.")
    }

    fun currentRole(userId: Long): String = sessionRole(userId)
        ?: throw ForbiddenException("La cuenta no tiene una sesión activa válida.")

    fun requireAdmin(userId: Long) {
        if (!Roles.isAdmin(sessionRole(userId))) {
            throw ForbiddenException("Esta operación requiere permisos de administrador.")
        }
    }

    fun requireAccountant(userId: Long) {
        if (!Roles.isAccountant(sessionRole(userId))) {
            throw ForbiddenException("Esta operación requiere permisos de contador.")
        }
    }

    fun requireAdminOrAccountant(userId: Long) {
        val role = sessionRole(userId)
        if (!Roles.isAdmin(role) && !Roles.isAccountant(role)) {
            throw ForbiddenException("Esta operación requiere permisos de Administrador o Contador.")
        }
    }

    fun requireRegistrationReviewer(userId: Long) = requireAdminOrAccountant(userId)

    /** El Contador conserva visibilidad global y comparte la revisión documental con Administradores. */
    fun requireAccountantRegistrationFallback(userId: Long) {
        requireAccountant(userId)
    }

    fun activeAdministratorCount(): Int = database.dataSource.connection.use { connection ->
        activeAdministratorCount(connection)
    }

    private fun activeAdministratorCount(connection: Connection): Int = connection.prepareStatement(
        """SELECT COUNT(*) FROM usuarios
           WHERE UPPER(TRIM(role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
             AND account_status='ACTIVE'""".trimIndent()
    ).use { statement ->
        statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
    }

    /**
     * El rol CONTADOR nunca se obtiene desde el registro público. Se designa
     * exclusivamente desde el backend mediante variables protegidas del backend.
     * La cartera se crea una sola vez con el presupuesto inicial configurado y
     * nunca se vuelve a rellenar durante despliegues posteriores.
     */
    fun hasAccountantAccount(): Boolean = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM usuarios WHERE UPPER(TRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS'))"
        ).use { statement ->
            statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
        }
    }

    fun ensureBootstrapAccountant(recoveryPassword: String? = null) {
        // El bootstrap crea el Contador solo en la primera instalación. Para instalaciones
        // existentes, las credenciales configuradas en Railway pueden actuar como mecanismo
        // de recuperación únicamente cuando el propio intento de login presenta exactamente
        // BOOTSTRAP_ACCOUNTANT_PASSWORD. Así un redeploy no pisa una contraseña cambiada por
        // el usuario, pero una instalación con hash histórico desincronizado puede repararse.
        val existingAccountantIds = database.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id FROM usuarios WHERE UPPER(TRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS') ORDER BY id LIMIT 2"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next() && size < 2) add(result.getLong(1))
                    }
                }
            }
        }
        val existingAccountantId = existingAccountantIds.singleOrNull()
        if (existingAccountantIds.isNotEmpty()) {
            if (existingAccountantId != null && recoveryPassword != null && config.bootstrapAccountantConfigured) {
                val configuredPassword = cleanBootstrapValue(config.bootstrapAccountantPassword)
                val recoveryMatchesEnvironment = shouldRecoverAccountantCredentials(
                    recoveryPassword = recoveryPassword,
                    configuredPassword = configuredPassword,
                    bootstrapConfigured = config.bootstrapAccountantConfigured
                )
                if (recoveryMatchesEnvironment) {
                    config.validateBootstrapAccountant()
                    val configuredPin = cleanBootstrapValue(config.bootstrapAccountantPin)
                    database.transaction { connection ->
                        ensureAccountantBootstrapInfrastructure(connection)
                        val currentHashes = connection.prepareStatement(
                            "SELECT password_hash,pin_hash FROM usuarios WHERE id=? FOR UPDATE"
                        ).use { statement ->
                            statement.setLong(1, existingAccountantId)
                            statement.executeQuery().use { result ->
                                if (!result.next()) throw AppException("No fue posible localizar la cuenta del contador.")
                                result.getString("password_hash") to result.getString("pin_hash")
                            }
                        }
                        val passwordOutOfSync = !passwordSecurity.verify(currentHashes.first, configuredPassword)
                        val pinOutOfSync = !passwordSecurity.verify(currentHashes.second, configuredPin)
                        if (passwordOutOfSync || pinOutOfSync) {
                            connection.prepareStatement(
                                """UPDATE usuarios
                                   SET password_hash=?,pin_hash=?,role='ACCOUNTANT',account_status='ACTIVE',
                                       verification_status='VERIFIED',email_verified=TRUE,failed_login_attempts=0,
                                       locked_until=NULL,updated_at=NOW()
                                   WHERE id=?"""
                            ).use { statement ->
                                statement.setString(1, passwordSecurity.hash(configuredPassword))
                                statement.setString(2, passwordSecurity.hash(configuredPin))
                                statement.setLong(3, existingAccountantId)
                                statement.executeUpdate()
                            }
                            withSavepointFallback(
                                connection = connection,
                                fallback = Unit,
                                context = "registrar recuperación de credenciales del contador"
                            ) {
                                audit(
                                    connection,
                                    existingAccountantId,
                                    "BOOTSTRAP_ACCOUNTANT_CREDENTIALS_RECOVERED",
                                    "ACCOUNTANT",
                                    existingAccountantId.toString(),
                                    "Contraseña/PIN del Contador sincronizados desde variables protegidas durante un login válido."
                                )
                            }
                            logger.warn("Credenciales del Contador recuperadas desde variables protegidas de Railway.")
                        }
                        ensureAccountantWallet(connection, existingAccountantId)
                    }
                } else {
                    database.transaction { connection ->
                        ensureAccountantBootstrapInfrastructure(connection)
                        ensureAccountantWallet(connection, existingAccountantId)
                    }
                }
            } else {
                database.transaction { connection ->
                    ensureAccountantBootstrapInfrastructure(connection)
                    existingAccountantIds.forEach { ensureAccountantWallet(connection, it) }
                }
                if (existingAccountantIds.size > 1 && recoveryPassword != null) {
                    logger.error("No se aplicó recuperación bootstrap: existen múltiples cuentas con rol Contador.")
                }
            }
            return
        }

        config.validateBootstrapAccountant()
        val normalizedEmail = cleanBootstrapValue(config.bootstrapAccountantEmail).lowercase()
        val normalizedUsername = bootstrapUsername(config.bootstrapAccountantUsername, normalizedEmail, "CONTADOR")
        val password = cleanBootstrapValue(config.bootstrapAccountantPassword)
        val pin = cleanBootstrapValue(config.bootstrapAccountantPin)
        val displayName = cleanBootstrapValue(config.bootstrapAccountantName).ifBlank { "Contador General" }
        val configuredPhone = cleanBootstrapValue(config.bootstrapAccountantPhone)
        val birthDate = runCatching {
            parseBirthDate(cleanBootstrapValue(config.bootstrapAccountantBirthDate))
        }.getOrElse {
            logger.warn(
                "BOOTSTRAP_ACCOUNTANT_BIRTH_DATE no es válida. Se usará 1990-01-01 para no bloquear el acceso del contador."
            )
            LocalDate.of(1990, 1, 1)
        }

        if (!EMAIL_REGEX.matches(normalizedEmail)) {
            throw AppException("BOOTSTRAP_ACCOUNTANT_EMAIL no contiene un correo válido.")
        }
        PasswordPolicy.validationError(password, normalizedUsername, normalizedEmail)?.let { error ->
            throw AppException("BOOTSTRAP_ACCOUNTANT_PASSWORD no cumple la política de seguridad: $error")
        }
        PinPolicy.validationError(pin)?.let { throw AppException("BOOTSTRAP_ACCOUNTANT_PIN no cumple la política de seguridad: $it") }

        // La autenticación del contador se confirma primero y se guarda en una
        // transacción independiente. Así, un teléfono repetido o un dato de perfil
        // heredado nunca puede impedir que el contador inicie sesión.
        val accountantId = database.transaction { connection ->
            ensureAccountantBootstrapInfrastructure(connection)

            val existing = connection.prepareStatement(
                "SELECT id FROM usuarios WHERE LOWER(email)=LOWER(?) OR LOWER(username)=LOWER(?) FOR UPDATE"
            ).use { statement ->
                statement.setString(1, normalizedEmail)
                statement.setString(2, normalizedUsername)
                statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
            }

            val userId = if (existing != null) {
                connection.prepareStatement(
                    """UPDATE usuarios
                       SET username=?,email=?,password_hash=?,pin_hash=?,role='ACCOUNTANT',account_status='ACTIVE',
                           verification_status='VERIFIED',email_verified=TRUE,phone_verified=TRUE,account_kind='ACCOUNTANT',
                           person_group_id=COALESCE(person_group_id,public_id),failed_login_attempts=0,locked_until=NULL,updated_at=NOW()
                       WHERE id=?"""
                ).use { statement ->
                    statement.setString(1, normalizedUsername)
                    statement.setString(2, normalizedEmail)
                    statement.setString(3, passwordSecurity.hash(password))
                    statement.setString(4, passwordSecurity.hash(pin))
                    statement.setLong(5, existing)
                    statement.executeUpdate()
                }
                existing
            } else {
                connection.prepareStatement(
                    """INSERT INTO usuarios(
                           username,email,password_hash,pin_hash,role,account_status,verification_status,
                           email_verified,phone_verified,failed_login_attempts,account_kind
                       ) VALUES (?,?,?,?,'ACCOUNTANT','ACTIVE','VERIFIED',TRUE,TRUE,0,'ACCOUNTANT')
                       RETURNING id"""
                ).use { statement ->
                    statement.setString(1, normalizedUsername)
                    statement.setString(2, normalizedEmail)
                    statement.setString(3, passwordSecurity.hash(password))
                    statement.setString(4, passwordSecurity.hash(pin))
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw AppException("No fue posible crear la cuenta del contador.")
                        result.getLong(1)
                    }
                }
            }

            withSavepointFallback(
                connection = connection,
                fallback = Unit,
                context = "registrar auditoría de credenciales del contador"
            ) {
                audit(
                    connection,
                    userId,
                    if (existing == null) "BOOTSTRAP_ACCOUNTANT_CREATED" else "BOOTSTRAP_ACCOUNTANT_CREDENTIALS_SYNCED",
                    "ACCOUNTANT",
                    userId.toString(),
                    "Credenciales y rol del contador sincronizados de forma segura."
                )
            }
            userId
        }

        // El perfil es complementario. Si el teléfono configurado ya pertenece a
        // otra cuenta, se conserva uno existente o se usa un identificador interno
        // único; la autenticación no se revierte.
        runCatching {
            database.transaction { connection ->
                ensureAccountantBootstrapInfrastructure(connection)
                val existingProfilePhone = connection.prepareStatement(
                    "SELECT phone FROM perfiles_usuario WHERE user_id=?"
                ).use { statement ->
                    statement.setLong(1, accountantId)
                    statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
                }
                val phoneOwnedByAnotherUser = connection.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM perfiles_usuario WHERE phone=? AND user_id<>?)"
                ).use { statement ->
                    statement.setString(1, configuredPhone)
                    statement.setLong(2, accountantId)
                    statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
                }
                val effectivePhone = when {
                    configuredPhone.isBlank() -> existingProfilePhone ?: "CONTADOR-$accountantId"
                    phoneOwnedByAnotherUser -> existingProfilePhone ?: "CONTADOR-$accountantId"
                    else -> configuredPhone
                }

                connection.prepareStatement(
                    """INSERT INTO perfiles_usuario(user_id,full_name,phone,birth_date)
                       VALUES (?,?,?,?)
                       ON CONFLICT(user_id) DO UPDATE SET
                           full_name=EXCLUDED.full_name,
                           phone=EXCLUDED.phone,
                           birth_date=EXCLUDED.birth_date,
                           updated_at=NOW()"""
                ).use { statement ->
                    statement.setLong(1, accountantId)
                    statement.setString(2, displayName)
                    statement.setString(3, effectivePhone)
                    statement.setObject(4, birthDate)
                    statement.executeUpdate()
                }
            }
        }.onFailure { error ->
            logger.error(
                "El contador fue creado y puede autenticarse, pero su perfil complementario no pudo sincronizarse.",
                error
            )
        }

        database.transaction { connection ->
            ensureAccountantBootstrapInfrastructure(connection)
            ensureAccountantWallet(connection, accountantId)
            withSavepointFallback(
                connection = connection,
                fallback = Unit,
                context = "registrar auditoría de cartera del contador"
            ) {
                audit(
                    connection,
                    accountantId,
                    "BOOTSTRAP_ACCOUNTANT_READY",
                    "ACCOUNTANT_WALLET",
                    accountantId.toString(),
                    "Contador activo con cartera presupuestaria preparada."
                )
            }
        }
    }

    private fun ensureAccountantBootstrapInfrastructure(connection: Connection) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, 2_500_025L)
            statement.execute()
        }

        val roleConstraintReady = connection.prepareStatement(
            """SELECT EXISTS(
                   SELECT 1
                   FROM pg_constraint
                   WHERE conrelid='usuarios'::regclass
                     AND contype='c'
                     AND pg_get_constraintdef(oid) ILIKE '%role%'
                     AND pg_get_constraintdef(oid) ILIKE '%ACCOUNTANT%'
                     AND pg_get_constraintdef(oid) ILIKE '%WAREHOUSE%'
               )"""
        ).use { statement ->
            statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
        }

        connection.createStatement().use { statement ->
            if (!roleConstraintReady) {
                statement.execute(
                    """DO $$
                       DECLARE role_constraint RECORD;
                       BEGIN
                           FOR role_constraint IN
                               SELECT conname
                               FROM pg_constraint
                               WHERE conrelid='usuarios'::regclass
                                 AND contype='c'
                                 AND pg_get_constraintdef(oid) ILIKE '%role%'
                           LOOP
                               EXECUTE format('ALTER TABLE usuarios DROP CONSTRAINT %I', role_constraint.conname);
                           END LOOP;
                       END $$"""
                )
                statement.execute(
                    """UPDATE usuarios
                       SET role=CASE
                           WHEN UPPER(TRIM(role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN') THEN 'ADMIN'
                           WHEN UPPER(TRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS') THEN 'ACCOUNTANT'
                           WHEN UPPER(TRIM(role)) IN ('WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA') THEN 'WAREHOUSE'
                           ELSE 'BENEFICIARY'
                       END"""
                )
                statement.execute(
                    "ALTER TABLE usuarios ADD CONSTRAINT users_role_check CHECK (role IN ('BENEFICIARY','ADMIN','ACCOUNTANT','WAREHOUSE'))"
                )
            }

            statement.execute(
                """CREATE TABLE IF NOT EXISTS contadores (
                       user_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
                       codigo_contador VARCHAR(40) NOT NULL UNIQUE DEFAULT ('CONT-' || UPPER(SUBSTRING(gen_random_uuid()::TEXT,1,8))),
                       activo BOOLEAN NOT NULL DEFAULT TRUE,
                       designado_por BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
                       designado_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                   )"""
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS carteras_presupuesto_contador (
                       contador_id BIGINT PRIMARY KEY REFERENCES contadores(user_id) ON DELETE CASCADE,
                       presupuesto_inicial_usd NUMERIC(18,2) NOT NULL DEFAULT 1000000.00 CHECK (presupuesto_inicial_usd >= 0),
                       saldo_disponible_usd NUMERIC(18,2) NOT NULL DEFAULT 1000000.00 CHECK (saldo_disponible_usd >= 0),
                       total_asignado_usd NUMERIC(18,2) NOT NULL DEFAULT 0.00 CHECK (total_asignado_usd >= 0),
                       fuente_fondos VARCHAR(40) NOT NULL DEFAULT 'INITIAL_OPERATING_BUDGET',
                       proveedor_bancario VARCHAR(80),
                       cuenta_bancaria_externa_id VARCHAR(180),
                       estado_integracion_bancaria VARCHAR(40) NOT NULL DEFAULT 'READY_FOR_BANK_API',
                       metadata_integracion JSONB NOT NULL DEFAULT '{}'::JSONB,
                       ultima_sincronizacion_bancaria_at TIMESTAMPTZ,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                   )"""
            )
            statement.execute(
                "ALTER TABLE carteras_presupuesto_contador ALTER COLUMN fuente_fondos SET DEFAULT 'INITIAL_OPERATING_BUDGET'"
            )
            statement.execute(
                """UPDATE carteras_presupuesto_contador
                   SET fuente_fondos='INITIAL_OPERATING_BUDGET', updated_at=NOW()
                   WHERE fuente_fondos IS NULL
                      OR TRIM(fuente_fondos)=''
                      OR fuente_fondos='SIMULATED_INITIAL_BUDGET'"""
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS asignaciones_presupuesto_admin (
                       id BIGSERIAL PRIMARY KEY,
                       contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
                       admin_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
                       monto_usd NUMERIC(18,2) NOT NULL CHECK (monto_usd > 0),
                       tasa_bcv NUMERIC(18,6) NOT NULL CHECK (tasa_bcv > 0),
                       monto_bs NUMERIC(20,2) NOT NULL CHECK (monto_bs >= 0),
                       saldo_contador_antes_usd NUMERIC(18,2) NOT NULL CHECK (saldo_contador_antes_usd >= 0),
                       saldo_contador_despues_usd NUMERIC(18,2) NOT NULL CHECK (saldo_contador_despues_usd >= 0),
                       referencia VARCHAR(120) NOT NULL UNIQUE,
                       descripcion VARCHAR(500),
                       fuente_fondos VARCHAR(40) NOT NULL DEFAULT 'ACCOUNTANT_WALLET',
                       transaccion_bancaria_externa_id VARCHAR(180),
                       estado VARCHAR(30) NOT NULL DEFAULT 'COMPLETED',
                       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                   )"""
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS movimientos_cartera_contador (
                       id BIGSERIAL PRIMARY KEY,
                       contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
                       admin_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
                       tipo VARCHAR(40) NOT NULL,
                       monto_usd NUMERIC(18,2) NOT NULL CHECK (monto_usd >= 0),
                       tasa_bcv NUMERIC(18,6),
                       monto_bs NUMERIC(20,2),
                       saldo_antes_usd NUMERIC(18,2) NOT NULL CHECK (saldo_antes_usd >= 0),
                       saldo_despues_usd NUMERIC(18,2) NOT NULL CHECK (saldo_despues_usd >= 0),
                       referencia VARCHAR(120),
                       descripcion VARCHAR(500),
                       proveedor_bancario VARCHAR(80),
                       transaccion_bancaria_externa_id VARCHAR(180),
                       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                   )"""
            )
        }
    }

    private fun normalizeUsername(value: String): String = value.trim()

    private fun validateUsername(value: String): String {
        val username = normalizeUsername(value)
        if (!USERNAME_REGEX.matches(username)) {
            throw AppException("El usuario debe tener entre 4 y 24 caracteres, comenzar con una letra y usar solo letras, números, punto o guion bajo.")
        }
        return username
    }

    private fun bootstrapUsername(configured: String, email: String, fallbackPrefix: String): String {
        val explicit = cleanBootstrapValue(configured)
        if (USERNAME_REGEX.matches(explicit)) return explicit
        val base = email.substringBefore('@').replace(Regex("[^A-Za-z0-9_.]"), "")
        val candidate = base.take(24).let { if (it.length >= 4 && it.first().isLetter()) it else "$fallbackPrefix${base.take(12)}" }.take(24)
        return if (USERNAME_REGEX.matches(candidate)) candidate else "${fallbackPrefix}2026".take(24)
    }

    private fun cleanBootstrapValue(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
                trimmed.substring(1, trimmed.length - 1).trim()
            trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'') ->
                trimmed.substring(1, trimmed.length - 1).trim()
            else -> trimmed
        }
    }


    fun register(request: RegisterRequest): RegisterResponse {
        verifyRecaptcha(request.recaptchaToken ?: request.captchaToken, "register")
        val username = validateUsername(request.username)
        val email = request.email.trim().lowercase()
        val firstName = request.firstName.trim()
        val middleName = request.middleName.trim()
        val lastName = request.lastName.trim()
        val secondLastName = request.secondLastName.trim()
        val fullName = listOf(firstName, middleName, lastName, secondLastName).joinToString(" ")
        val phone = normalizeVenezuelanPhone(request.phone)
            ?: throw AppException("Ingresa un celular venezolano válido, por ejemplo +58 412-1234567.")
        val birthDate = parseBirthDate(request.birthDate)
        val employmentType = request.employmentType.trim().uppercase()

        if (employmentType !in setOf("PUBLIC_EMPLOYEE", "PRIVATE_EMPLOYEE")) {
            throw AppException("Selecciona si eres empleado público o privado.")
        }
        if (firstName.length < 2) throw AppException("Ingresa tu primer nombre.")
        if (middleName.length < 2) throw AppException("Ingresa tu segundo nombre.")
        if (lastName.length < 2) throw AppException("Ingresa tu primer apellido.")
        if (secondLastName.length < 2) throw AppException("Ingresa tu segundo apellido.")
        listOf(firstName, middleName, lastName, secondLastName).forEach { value ->
            if (!isSafePersonName(value)) throw AppException("Nombres y apellidos solo pueden contener letras y espacios.")
        }
        listOfNotNull(request.community, request.address).filter { it.isNotBlank() }.forEach { value ->
            if (!isSafePlainText(value)) throw AppException("Los campos de texto no permiten caracteres especiales.")
        }
        if (!EMAIL_REGEX.matches(email)) throw AppException("Ingresa un correo electrónico válido.")
        if (ChronoUnit.YEARS.between(birthDate, LocalDate.now()) < 18) {
            throw AppException("Debes ser mayor de 18 años para registrarte.")
        }
        PasswordPolicy.validationError(request.password, username, email)?.let { throw AppException(it) }
        PinPolicy.validationError(request.pin)?.let { throw AppException(it) }
        if (!request.acceptedTerms) throw AppException("Debes aceptar los Términos y Condiciones y la Política de Privacidad para crear la cuenta.")
        val termsVersion = request.termsVersion?.trim()?.takeIf { it.isNotBlank() }?.take(80)
            ?: throw AppException("No se recibió la versión de los Términos y Condiciones aceptados.")
        val privacyVersion = request.privacyVersion?.trim()?.takeIf { it.isNotBlank() }?.take(80)
            ?: throw AppException("No se recibió la versión de la Política de Privacidad aceptada.")

        val creation = database.transaction { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, phone)
                statement.execute()
            }
            val duplicate = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(username)=LOWER(?)), EXISTS(SELECT 1 FROM usuarios WHERE LOWER(email)=LOWER(?)), EXISTS(SELECT 1 FROM perfiles_usuario WHERE phone=?)"
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, email)
                statement.setString(3, phone)
                statement.executeQuery().use { result ->
                    result.next()
                    Triple(result.getBoolean(1), result.getBoolean(2), result.getBoolean(3))
                }
            }
            if (duplicate.first) throw AppException("Ese nombre de usuario ya está ocupado.")
            if (duplicate.second) throw AppException("Ya existe una cuenta registrada con ese correo.")
            if (duplicate.third) throw AppException("Ese número de teléfono ya está registrado.")

            val userId = connection.prepareStatement(
                """
                INSERT INTO usuarios(
                    username,email,password_hash,pin_hash,role,account_status,verification_status,email_verified
                )
                VALUES (?,?,?,?,'BENEFICIARY','PENDING_VERIFICATION','NOT_SUBMITTED',TRUE)
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, email)
                statement.setString(3, passwordSecurity.hash(request.password))
                statement.setString(4, passwordSecurity.hash(request.pin))
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }

            connection.prepareStatement(
                """
                INSERT INTO perfiles_usuario(
                    user_id,full_name,first_name,middle_name,last_name,second_last_name,
                    phone,birth_date,employment_type,state,municipality,parish,community,address
                )
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, fullName)
                statement.setString(3, firstName)
                statement.setString(4, middleName)
                statement.setString(5, lastName)
                statement.setString(6, secondLastName)
                statement.setString(7, phone)
                statement.setObject(8, birthDate)
                statement.setString(9, employmentType)
                statement.setString(10, request.state?.trim()?.takeIf { it.isNotEmpty() })
                statement.setString(11, request.municipality?.trim()?.takeIf { it.isNotEmpty() })
                statement.setString(12, request.parish?.trim()?.takeIf { it.isNotEmpty() })
                statement.setString(13, request.community?.trim()?.takeIf { it.isNotEmpty() })
                statement.setString(14, request.address?.trim()?.takeIf { it.isNotEmpty() })
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO perfiles_financieros_usuario(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING").use {
                it.setLong(1, userId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO consentimientos_usuario(
                       user_id,terms_version,privacy_version,accepted,source,accepted_at
                   ) VALUES (?,?,?,TRUE,'ANDROID',NOW())"""
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, termsVersion)
                statement.setString(3, privacyVersion)
                statement.executeUpdate()
            }

            val registrationToken = createChallenge(connection, userId, "REGISTRATION_DOCUMENT", 24 * 60).toString()
            audit(connection, userId, "USER_REGISTERED", "USER", userId.toString(), "Nueva cuenta registrada; revisión documental/administrativa pendiente")
            RegistrationCreation(userId, registrationToken)
        }

        val response = RegisterResponse(
            userId = creation.userId,
            verificationStatus = "NOT_SUBMITTED",
            registrationToken = creation.registrationToken,
            accountVerified = true
        )

        notifyUsers(listOf(response.userId), "Bienvenido a Kredi+", "Tu usuario fue creado. Completa la verificación para activar todas las funciones.", "WELCOME")
        notifyAdmins(
            title = "Nuevo registro",
            body = "$fullName creó una cuenta. Al subir sus documentos aparecerá en la cola de revisión del Administrador.",
            type = "REGISTRATION",
            data = mapOf("userId" to response.userId.toString())
        )
        return response
    }

    private fun cleanupFailedRegistration(userId: Long) {
        runCatching {
            database.transaction { connection ->
                connection.prepareStatement(
                    "DELETE FROM usuarios WHERE id=? AND email_verified=FALSE AND account_status='PENDING_VERIFICATION'"
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.executeUpdate()
                }
            }
        }.onFailure { cleanupError ->
            logger.error("No fue posible limpiar el registro fallido {}.", userId, cleanupError)
        }
    }

    private fun cleanupUnsentSecurityCode(userId: Long, purpose: String) {
        runCatching {
            database.transaction { connection ->
                connection.prepareStatement(
                    "DELETE FROM codigos_verificacion WHERE user_id=? AND purpose=? AND consumed_at IS NULL"
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, purpose)
                    statement.executeUpdate()
                }
            }
        }.onFailure { cleanupError ->
            logger.error("No fue posible limpiar el código no enviado del usuario {}.", userId, cleanupError)
        }
    }


    fun lookupRecoveryAccount(request: RecoveryLookupRequest): RecoveryLookupResponse {
        verifyRecaptcha(request.recaptchaToken ?: request.captchaToken, "recovery_identify")
        val identifier = request.identifier.trim()
        if (identifier.isBlank()) throw AppException("Ingresa tu usuario, cédula o correo de Kredi+.")
        val user = database.transaction { connection -> findRecoveryUser(connection, identifier) }
            ?: throw AppException("No encontramos una cuenta con esos datos.")
        if (user.email.isBlank()) throw AppException("Esta cuenta no tiene un correo registrado. Contacta a soporte Kredi+.")
        return RecoveryLookupResponse(
            message = "Confirma que deseas recibir un código en el correo registrado.",
            maskedEmail = maskRecoveryEmail(user.email)
        )
    }

    fun requestPasswordReset(request: PasswordResetRequest): PasswordResetResponse =
        requestRecoveryCode(request, "PASSWORD_RESET", "password_reset_request") { destination, code ->
            emailService.sendPasswordResetCode(destination, code, config.passwordResetCodeTtlMinutes)
        }

    fun requestPinReset(request: PasswordResetRequest): PasswordResetResponse =
        requestRecoveryCode(request, "PIN_RESET", "pin_reset_request") { destination, code ->
            emailService.sendPinResetCode(destination, code, config.passwordResetCodeTtlMinutes)
        }

    private fun requestRecoveryCode(
        request: PasswordResetRequest,
        purpose: String,
        recaptchaAction: String,
        sender: (String, String) -> Unit
    ): PasswordResetResponse {
        verifyRecaptcha(request.recaptchaToken ?: request.captchaToken, recaptchaAction)
        if (!emailService.configured) {
            throw AppException("La recuperación por correo no está disponible temporalmente. Inténtalo nuevamente más tarde.")
        }

        val identifier = request.identifier.trim()
        if (identifier.isBlank()) throw AppException("Ingresa tu usuario, cédula o correo de Kredi+.")
        val genericMessage = "Si la cuenta coincide, enviamos un código de 6 dígitos al correo registrado."
        val user = database.transaction { connection -> findRecoveryUser(connection, identifier) }
            ?: return PasswordResetResponse(genericMessage, config.passwordResetCodeTtlMinutes)

        if (user.email.isBlank()) {
            logger.warn("Kredi+ recovery: el usuario {} no tiene correo registrado.", user.id)
            return PasswordResetResponse(genericMessage, config.passwordResetCodeTtlMinutes)
        }

        val code = generateRecoveryCode()
        val codeHash = passwordSecurity.hash(code)
        database.transaction { connection ->
            connection.prepareStatement(
                "DELETE FROM codigos_verificacion WHERE user_id=? AND purpose=? AND consumed_at IS NULL"
            ).use { statement ->
                statement.setLong(1, user.id)
                statement.setString(2, purpose)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO codigos_verificacion(user_id,channel,purpose,destination,code_hash,expires_at)
                VALUES (?,'EMAIL',?,?,?,NOW() + (? * INTERVAL '1 minute'))
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, user.id)
                statement.setString(2, purpose)
                statement.setString(3, user.email)
                statement.setString(4, codeHash)
                statement.setLong(5, config.passwordResetCodeTtlMinutes)
                statement.executeUpdate()
            }
            audit(connection, user.id, "${purpose}_EMAIL_REQUESTED", "USER", user.id.toString(), "Código de recuperación solicitado por correo")
        }

        try {
            sender(user.email, code)
        } catch (error: Throwable) {
            cleanupUnsentSecurityCode(user.id, purpose)
            logger.error("No fue posible enviar el correo de recuperación para el usuario {}.", user.id, error)
            throw AppException("No fue posible enviar el correo de recuperación. Inténtalo nuevamente en unos minutos.")
        }

        return PasswordResetResponse(
            message = genericMessage,
            expiresInMinutes = config.passwordResetCodeTtlMinutes,
            status = "EMAIL_SENT",
            nextStep = "ENTER_CODE"
        )
    }

    fun resetPassword(request: PasswordResetConfirmRequest): MessageResponse {
        verifyRecaptcha(request.recaptchaToken ?: request.captchaToken, "password_reset")
        if (!emailService.configured) throw AppException("La recuperación por correo no está disponible temporalmente.")
        val identifier = request.identifier.trim()
        if (identifier.isBlank()) throw AppException("Ingresa tu usuario, cédula o correo de Kredi+.")
        val code = (request.code ?: request.verificationCode).orEmpty().filter(Char::isDigit)
        if (code.length != config.passwordResetCodeLength) throw AppException("Ingresa el código de ${config.passwordResetCodeLength} dígitos enviado a tu correo.")
        val newPassword = (request.newPassword ?: request.password).orEmpty()
        val user = database.transaction { connection -> findRecoveryUser(connection, identifier) }
            ?: throw AppException("El código de recuperación es inválido o venció.")
        PasswordPolicy.validationError(newPassword, user.username, user.email)?.let { throw AppException(it) }

        val result = database.transaction { connection ->
            val codeResult = consumeRecoveryCode(connection, user.id, "PASSWORD_RESET", code)
            if (codeResult == RecoveryCodeResult.VALID) {
                connection.prepareStatement(
                    "UPDATE usuarios SET password_hash=?,failed_login_attempts=0,locked_until=NULL,updated_at=NOW() WHERE id=?"
                ).use { statement ->
                    statement.setString(1, passwordSecurity.hash(newPassword))
                    statement.setLong(2, user.id)
                    statement.executeUpdate()
                }
                revokeRecoverySessions(connection, user.id)
                audit(connection, user.id, "PASSWORD_RESET_EMAIL_COMPLETED", "USER", user.id.toString(), "Contraseña restablecida mediante correo corporativo")
            }
            codeResult
        }
        return recoveryResultMessage(result, "Contraseña actualizada. Inicia sesión nuevamente.")
    }

    fun resetPin(request: PinResetConfirmRequest): MessageResponse {
        verifyRecaptcha(request.recaptchaToken ?: request.captchaToken, "pin_reset")
        if (!emailService.configured) throw AppException("La recuperación por correo no está disponible temporalmente.")
        val identifier = request.identifier.trim()
        if (identifier.isBlank()) throw AppException("Ingresa tu usuario, cédula o correo de Kredi+.")
        val code = (request.code ?: request.verificationCode).orEmpty().filter(Char::isDigit)
        if (code.length != config.passwordResetCodeLength) throw AppException("Ingresa el código de ${config.passwordResetCodeLength} dígitos enviado a tu correo.")
        val newPin = (request.newPin ?: request.pin).orEmpty().filter(Char::isDigit)
        PinPolicy.validationError(newPin)?.let { throw AppException(it) }
        val user = database.transaction { connection -> findRecoveryUser(connection, identifier) }
            ?: throw AppException("El código de recuperación es inválido o venció.")

        val result = database.transaction { connection ->
            val codeResult = consumeRecoveryCode(connection, user.id, "PIN_RESET", code)
            if (codeResult == RecoveryCodeResult.VALID) {
                connection.prepareStatement(
                    "UPDATE usuarios SET pin_hash=?,failed_login_attempts=0,locked_until=NULL,updated_at=NOW() WHERE id=?"
                ).use { statement ->
                    statement.setString(1, passwordSecurity.hash(newPin))
                    statement.setLong(2, user.id)
                    statement.executeUpdate()
                }
                revokeRecoverySessions(connection, user.id)
                audit(connection, user.id, "PIN_RESET_EMAIL_COMPLETED", "USER", user.id.toString(), "PIN restablecido mediante correo corporativo")
            }
            codeResult
        }
        return recoveryResultMessage(result, "PIN actualizado. Inicia sesión nuevamente.")
    }

    private fun consumeRecoveryCode(connection: Connection, userId: Long, purpose: String, code: String): RecoveryCodeResult {
        val row = connection.prepareStatement(
            """
            SELECT id,code_hash,attempts
            FROM codigos_verificacion
            WHERE user_id=? AND channel='EMAIL' AND purpose=?
              AND consumed_at IS NULL AND expires_at>NOW()
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, purpose)
            statement.executeQuery().use { rs ->
                if (!rs.next()) null else RecoveryCodeRow(rs.getLong("id"), rs.getString("code_hash"), rs.getInt("attempts"))
            }
        } ?: return RecoveryCodeResult.MISSING

        if (row.attempts >= config.passwordResetMaxAttempts) {
            connection.prepareStatement("UPDATE codigos_verificacion SET consumed_at=NOW() WHERE id=?").use { st ->
                st.setLong(1, row.id); st.executeUpdate()
            }
            return RecoveryCodeResult.LOCKED
        }
        if (!passwordSecurity.verify(row.hash, code)) {
            val nextAttempts = row.attempts + 1
            connection.prepareStatement(
                "UPDATE codigos_verificacion SET attempts=?, consumed_at=CASE WHEN ?>=? THEN NOW() ELSE consumed_at END WHERE id=?"
            ).use { st ->
                st.setInt(1, nextAttempts); st.setInt(2, nextAttempts); st.setInt(3, config.passwordResetMaxAttempts); st.setLong(4, row.id); st.executeUpdate()
            }
            return if (nextAttempts >= config.passwordResetMaxAttempts) RecoveryCodeResult.LOCKED else RecoveryCodeResult.INVALID
        }
        connection.prepareStatement("UPDATE codigos_verificacion SET consumed_at=NOW() WHERE id=?").use { st ->
            st.setLong(1, row.id); st.executeUpdate()
        }
        return RecoveryCodeResult.VALID
    }

    private fun revokeRecoverySessions(connection: Connection, userId: Long) {
        connection.prepareStatement("UPDATE sesiones_usuario SET revoked_at=NOW() WHERE user_id=? AND revoked_at IS NULL").use { st ->
            st.setLong(1, userId); st.executeUpdate()
        }
        connection.prepareStatement("UPDATE desafios_autenticacion SET used_at=NOW() WHERE user_id=? AND used_at IS NULL").use { st ->
            st.setLong(1, userId); st.executeUpdate()
        }
    }

    private fun recoveryResultMessage(result: RecoveryCodeResult, success: String): MessageResponse = when (result) {
        RecoveryCodeResult.VALID -> MessageResponse(success)
        RecoveryCodeResult.LOCKED -> throw AppException("Superaste el número de intentos permitidos. Solicita un código nuevo.")
        RecoveryCodeResult.INVALID, RecoveryCodeResult.MISSING -> throw AppException("El código de recuperación es inválido o venció.")
    }

    private fun findRecoveryUser(connection: Connection, identifier: String): RecoveryUser? =
        connection.prepareStatement(
            """
            SELECT u.id,u.username,u.email
            FROM usuarios u
            WHERE LOWER(TRIM(u.username))=LOWER(TRIM(?))
               OR LOWER(TRIM(u.email))=LOWER(TRIM(?))
               OR EXISTS (
                    SELECT 1 FROM verificaciones_documentos vd
                    WHERE vd.user_id=u.id
                      AND regexp_replace(UPPER(vd.document_number),'[^A-Z0-9]','','g') =
                          regexp_replace(UPPER(?),'[^A-Z0-9]','','g')
               )
            ORDER BY CASE
                WHEN LOWER(TRIM(u.username))=LOWER(TRIM(?)) THEN 0
                WHEN LOWER(TRIM(u.email))=LOWER(TRIM(?)) THEN 1
                ELSE 2 END
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            repeat(5) { index -> statement.setString(index + 1, identifier) }
            statement.executeQuery().use { rs ->
                if (!rs.next()) null else RecoveryUser(
                    id = rs.getLong("id"),
                    username = rs.getString("username").orEmpty(),
                    email = rs.getString("email").orEmpty().trim().lowercase(Locale.ROOT)
                )
            }
        }

    private fun maskRecoveryEmail(email: String): String {
        val parts = email.split('@', limit = 2)
        if (parts.size != 2) return "***"
        val local = parts[0]
        val masked = when {
            local.length <= 1 -> "*"
            local.length == 2 -> "${local.first()}*"
            else -> "${local.first()}***${local.last()}"
        }
        return "$masked@${parts[1]}"
    }

    private fun generateRecoveryCode(): String = buildString(config.passwordResetCodeLength) {
        repeat(config.passwordResetCodeLength) { append(secureRandom.nextInt(10)) }
    }

    fun login(request: LoginRequest): LoginDecision {
        verifyRecaptcha(request.recaptchaToken ?: request.captchaToken, "login")
        val identifier = (request.username ?: request.email).orEmpty().trim()
        if (identifier.isBlank()) throw AppException("Ingresa tu nombre de usuario.")
        val configuredAccountantEmail = cleanBootstrapValue(config.bootstrapAccountantEmail).lowercase()
        val configuredAccountantUsername = bootstrapUsername(config.bootstrapAccountantUsername, configuredAccountantEmail, "CONTADOR")
        if (config.bootstrapAccountantConfigured && (identifier.equals(configuredAccountantUsername, true) || identifier.equals(configuredAccountantEmail, true))) {
            runCatching { ensureBootstrapAccountant(request.password) }
                .onFailure { error -> logger.error("No fue posible recuperar la cuenta del contador durante el inicio de sesión.", error) }
        }

        val decision = database.transaction { connection ->
            val directRow = connection.prepareStatement(
                "SELECT id,email,password_hash,verification_status,account_status,email_verified,failed_login_attempts,locked_until,suspension_reason,suspended_at FROM usuarios WHERE LOWER(TRIM(username))=LOWER(TRIM(?)) OR LOWER(TRIM(email))=LOWER(TRIM(?)) ORDER BY CASE WHEN LOWER(TRIM(username))=LOWER(TRIM(?)) THEN 0 ELSE 1 END LIMIT 1"
            ).use { statement ->
                statement.setString(1, identifier)
                statement.setString(2, identifier)
                statement.setString(3, identifier)
                statement.executeQuery().use { result ->
                    if (!result.next()) null else LoginRow(
                        id = result.getLong("id"),
                        email = result.getString("email").orEmpty(),
                        passwordHash = result.getString("password_hash"),
                        verificationStatus = result.getString("verification_status"),
                        accountStatus = result.getString("account_status"),
                        accountVerified = result.getBoolean("email_verified"),
                        failedLoginAttempts = result.getInt("failed_login_attempts"),
                        lockedUntil = result.getObject("locked_until", OffsetDateTime::class.java),
                        suspensionReason = result.getString("suspension_reason"),
                        suspendedAt = result.getObject("suspended_at", OffsetDateTime::class.java)
                    )
                }
            }

            // Compatibilidad con instalaciones anteriores: históricamente el Contador podía
            // quedar con un username técnico distinto aunque la interfaz usara "CONTADOR".
            // El alias solo se acepta si existe exactamente UN contador activo/verificable;
            // la contraseña real sigue siendo obligatoria y se valida más abajo.
            val canonicalAccountantAlias = identifier.equals("CONTADOR", ignoreCase = true) ||
                identifier.equals(configuredAccountantUsername, ignoreCase = true) ||
                (configuredAccountantEmail.isNotBlank() && identifier.equals(configuredAccountantEmail, ignoreCase = true))

            val accountantAliasRow = if (directRow == null && canonicalAccountantAlias) {
                connection.prepareStatement(
                    "SELECT id,email,password_hash,verification_status,account_status,email_verified,failed_login_attempts,locked_until,suspension_reason,suspended_at FROM usuarios WHERE UPPER(TRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS') AND account_status <> 'BLOCKED' ORDER BY id LIMIT 2"
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        val matches = mutableListOf<LoginRow>()
                        while (result.next() && matches.size < 2) {
                            matches += LoginRow(
                                id = result.getLong("id"),
                                email = result.getString("email").orEmpty(),
                                passwordHash = result.getString("password_hash"),
                                verificationStatus = result.getString("verification_status"),
                                accountStatus = result.getString("account_status"),
                                accountVerified = result.getBoolean("email_verified"),
                                failedLoginAttempts = result.getInt("failed_login_attempts"),
                                lockedUntil = result.getObject("locked_until", OffsetDateTime::class.java),
                                suspensionReason = result.getString("suspension_reason"),
                                suspendedAt = result.getObject("suspended_at", OffsetDateTime::class.java)
                            )
                        }
                        matches.singleOrNull()
                    }
                }
            } else null

            val row = directRow ?: accountantAliasRow ?: throw AppException("Usuario o contraseña incorrectos.")

            val now = OffsetDateTime.now(ZoneOffset.UTC)
            if (row.lockedUntil?.isAfter(now) == true) {
                val remainingMinutes = ChronoUnit.MINUTES.between(now, row.lockedUntil).coerceAtLeast(0) + 1
                throw ForbiddenException("Demasiados intentos fallidos. Inténtalo nuevamente en $remainingMinutes minuto(s).")
            }
            if (!passwordSecurity.verify(row.passwordHash, request.password)) {
                val nextAttempts = row.failedLoginAttempts + 1
                val shouldLock = nextAttempts >= MAX_LOGIN_ATTEMPTS
                connection.prepareStatement(
                    "UPDATE usuarios SET failed_login_attempts=?, locked_until=CASE WHEN ? THEN NOW() + INTERVAL '15 minutes' ELSE NULL END, updated_at=NOW() WHERE id=?"
                ).use { statement ->
                    statement.setInt(1, if (shouldLock) 0 else nextAttempts)
                    statement.setBoolean(2, shouldLock)
                    statement.setLong(3, row.id)
                    statement.executeUpdate()
                }
                return@transaction LoginDecision(
                    LoginResponse(
                        userId = -row.id,
                        verificationStatus = "INVALID_CREDENTIALS",
                        email = "",
                        accountVerified = false
                    )
                )
            }

            if (row.accountStatus == "BLOCKED") {
                throw ForbiddenException("La cuenta está bloqueada. Comunícate con soporte Kredi+.")
            }

            connection.prepareStatement(
                "UPDATE usuarios SET failed_login_attempts=0, locked_until=NULL, updated_at=NOW() WHERE id=?"
            ).use { statement ->
                statement.setLong(1, row.id)
                statement.executeUpdate()
            }

            val response = when {
                row.accountStatus == "SUSPENDED" -> LoginResponse(
                    userId = row.id,
                    verificationStatus = row.verificationStatus,
                    email = row.email,
                    accountStatus = "SUSPENDED",
                    suspensionReason = row.suspensionReason ?: "Falta de pago",
                    suspendedAt = row.suspendedAt?.toString(),
                    accountVerified = row.accountVerified
                )
                row.verificationStatus == "VERIFIED" && row.accountStatus == "ACTIVE" -> LoginResponse(
                    userId = row.id,
                    verificationStatus = row.verificationStatus,
                    email = row.email,
                    accountStatus = row.accountStatus,
                    pinChallengeToken = createChallenge(connection, row.id, "LOGIN_PIN", 15).toString(),
                    accountVerified = true
                )
                else -> LoginResponse(
                    userId = row.id,
                    verificationStatus = row.verificationStatus,
                    email = row.email,
                    accountStatus = row.accountStatus,
                    registrationToken = createChallenge(connection, row.id, "REGISTRATION_DOCUMENT", 24 * 60).toString(),
                    accountVerified = true
                )
            }
            LoginDecision(response)
        }
        if (decision.response.userId < 0) {
            throw AppException("Usuario o contraseña incorrectos.")
        }
        return decision
    }


    fun verifyPin(request: VerifyPinRequest, accessTokenFactory: (Long, String, UUID) -> String): VerifyPinResponse {
        var pinFailure: String? = null
        val response: VerifyPinResponse? = database.transaction { connection ->
        database.ensureAuthenticationSchema(connection)
        if (!request.pin.matches(Regex("\\d{6}"))) {
            throw AppException("El PIN debe contener exactamente 6 dígitos.")
        }

        // La autorización se valida primero, pero solo se consume cuando el PIN es correcto
        // y toda la sesión pudo construirse. Así un error secundario no deja al usuario atrapado.
        verifyChallenge(connection, request.challengeToken, request.userId, "LOGIN_PIN", consume = false)

        val row = connection.prepareStatement(
            "SELECT pin_hash,role,verification_status,account_status FROM usuarios WHERE id=? FOR UPDATE"
        ).use { statement ->
            statement.setLong(1, request.userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("La cuenta ya no existe.")
                PinRow(
                    pinHash = result.getString("pin_hash")?.takeIf { it.isNotBlank() }
                        ?: throw AppException("La cuenta no tiene un PIN válido configurado. Restablécelo e intenta nuevamente."),
                    role = result.getString("role") ?: "BENEFICIARY",
                    verificationStatus = result.getString("verification_status") ?: "NOT_SUBMITTED",
                    accountStatus = result.getString("account_status") ?: "PENDING_VERIFICATION"
                )
            }
        }

        if (row.verificationStatus != "VERIFIED" || row.accountStatus != "ACTIVE") {
            throw ForbiddenException("La cuenta todavía no está verificada.")
        }

        val pinIsValid = runCatching { passwordSecurity.verify(row.pinHash, request.pin) }
            .onFailure { error ->
                logger.error("No fue posible validar el hash de PIN del usuario ${request.userId}.", error)
            }
            .getOrElse {
                throw AppException("No fue posible validar el PIN. Restablécelo e intenta nuevamente.")
            }
        if (!pinIsValid) {
            val attempts = connection.prepareStatement(
                """
                UPDATE desafios_autenticacion
                SET attempts=attempts+1,
                    used_at=CASE WHEN attempts+1>=? THEN NOW() ELSE used_at END
                WHERE token=?::uuid AND user_id=? AND purpose='LOGIN_PIN' AND used_at IS NULL
                RETURNING attempts
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, MAX_PIN_ATTEMPTS)
                statement.setString(2, request.challengeToken.trim())
                statement.setLong(3, request.userId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getInt("attempts") else MAX_PIN_ATTEMPTS
                }
            }
            pinFailure = if (attempts >= MAX_PIN_ATTEMPTS) {
                "Demasiados intentos de PIN. Inicia el proceso de acceso nuevamente."
            } else {
                "PIN incorrecto. Quedan ${MAX_PIN_ATTEMPTS - attempts} intento(s)."
            }
            return@transaction null
        }

        val normalizedDeviceId = request.deviceIdHash.trim().lowercase()
        if (!normalizedDeviceId.matches(Regex("[a-f0-9]{64}"))) {
            throw AppException("No fue posible identificar de forma segura este dispositivo.")
        }

        // Serializa los intentos de acceso de una misma cuenta. Sin este bloqueo, dos
        // teléfonos podían superar la comprobación al mismo tiempo y crear dos sesiones.
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, 6_001_000_000L + request.userId)
            statement.executeQuery().use { result -> result.next() }
        }

        // Una cuenta puede mantener una sola sesión realmente activa. Las sesiones sin
        // actividad reciente se cierran automáticamente para no bloquear al usuario para siempre.
        connection.prepareStatement(
            """
            UPDATE sesiones_usuario
            SET revoked_at=COALESCE(revoked_at,NOW()),ended_reason=COALESCE(ended_reason,'INACTIVITY_TIMEOUT')
            WHERE user_id=? AND revoked_at IS NULL
              AND COALESCE(last_heartbeat_at,last_used_at,created_at) < NOW() - INTERVAL '3 minutes'
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, request.userId)
            statement.executeUpdate()
        }

        val activeOtherDevice = connection.prepareStatement(
            """
            SELECT id,device_name
            FROM sesiones_usuario
            WHERE user_id=? AND revoked_at IS NULL AND expires_at>NOW()
              AND COALESCE(last_heartbeat_at,last_used_at,created_at) >= NOW() - INTERVAL '3 minutes'
              AND COALESCE(device_id_hash,'')<>?
            ORDER BY COALESCE(last_heartbeat_at,last_used_at,created_at) DESC
            LIMIT 1 FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, request.userId)
            statement.setString(2, normalizedDeviceId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString("device_name").orEmpty() else null
            }
        }
        if (activeOtherDevice != null) {
            throw ForbiddenException("Usuario tiene una sesión activa.")
        }

        // Reingresar en el mismo dispositivo reemplaza únicamente la sesión anterior del mismo equipo.
        connection.prepareStatement(
            """
            UPDATE sesiones_usuario
            SET revoked_at=COALESCE(revoked_at,NOW()),ended_reason=COALESCE(ended_reason,'REPLACED_SAME_DEVICE')
            WHERE user_id=? AND revoked_at IS NULL AND device_id_hash=?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, request.userId)
            statement.setString(2, normalizedDeviceId)
            statement.executeUpdate()
        }

        val canonicalRole = Roles.canonical(row.role)
        if (canonicalRole != row.role) {
            connection.prepareStatement("UPDATE usuarios SET role=?, updated_at=NOW() WHERE id=?").use { statement ->
                statement.setString(1, canonicalRole)
                statement.setLong(2, request.userId)
                statement.executeUpdate()
            }
        }

        connection.prepareStatement(
            "UPDATE usuarios SET last_login_at=NOW(), failed_login_attempts=0, locked_until=NULL, updated_at=NOW() WHERE id=?"
        ).use { statement ->
            statement.setLong(1, request.userId)
            statement.executeUpdate()
        }

        val user = userDto(connection, request.userId)
        val persistentSession = createPersistentSession(connection, request.userId, normalizedDeviceId, request.deviceName, request.appVersion)
        val token = accessTokenFactory(request.userId, canonicalRole, persistentSession)

        val challengeId = runCatching { UUID.fromString(request.challengeToken.trim()) }
            .getOrElse { throw AppException("La autorización temporal no es válida.") }
        connection.prepareStatement(
            "UPDATE desafios_autenticacion SET used_at=NOW() WHERE token=? AND user_id=? AND purpose='LOGIN_PIN' AND used_at IS NULL"
        ).use { statement ->
            statement.setObject(1, challengeId)
            statement.setLong(2, request.userId)
            if (statement.executeUpdate() != 1) {
                throw AppException("La autorización temporal expiró. Inicia el proceso nuevamente.")
            }
        }

        withSavepointFallback(
            connection = connection,
            fallback = Unit,
            context = "registrar auditoría de inicio de sesión"
        ) {
            audit(connection, request.userId, "USER_LOGIN", "USER", request.userId.toString(), "Inicio de sesión exitoso")
        }

            VerifyPinResponse(token, persistentSession.toString(), user)
        }
        return response ?: throw AppException(pinFailure ?: "No fue posible validar el PIN.")
    }

    fun createSavedSessionPinChallenge(request: SavedSessionPinChallengeRequest): SavedSessionPinChallengeResponse = database.transaction { connection ->
        database.ensureAuthenticationSchema(connection)
        val sessionId = runCatching { UUID.fromString(request.refreshToken.trim()) }.getOrNull()
            ?: throw ForbiddenException("La sesión persistente no es válida.")
        val userId = connection.prepareStatement(
            """
            SELECT s.user_id
            FROM sesiones_usuario s
            JOIN usuarios u ON u.id=s.user_id
            WHERE s.id=? AND s.revoked_at IS NULL AND s.expires_at>NOW()
              AND u.account_status='ACTIVE' AND u.verification_status='VERIFIED'
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw ForbiddenException("La sesión persistente expiró o fue cerrada.")
                result.getLong("user_id")
            }
        }
        SavedSessionPinChallengeResponse(userId, createChallenge(connection, userId, "LOGIN_PIN", 15).toString())
    }

    fun refreshSession(
        request: RefreshSessionRequest,
        accessTokenFactory: (Long, String, UUID) -> String
    ): RefreshSessionResponse = database.transaction { connection ->
        database.ensureAuthenticationSchema(connection)
        val sessionId = runCatching { UUID.fromString(request.refreshToken.trim()) }.getOrNull()
            ?: throw ForbiddenException("La sesión persistente no es válida.")

        val accountStatusForSession = connection.prepareStatement(
            "SELECT u.account_status FROM sesiones_usuario s JOIN usuarios u ON u.id=s.user_id WHERE s.id=?"
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        if (accountStatusForSession.equals("SUSPENDED", true)) {
            throw ForbiddenException("Tu cuenta está suspendida.")
        }

        val row = connection.prepareStatement(
            """
            SELECT s.user_id,u.role,u.account_status,u.verification_status
            FROM sesiones_usuario s
            JOIN usuarios u ON u.id=s.user_id
            WHERE s.id=? AND s.revoked_at IS NULL AND s.expires_at>NOW()
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw ForbiddenException("La sesión persistente expiró o fue cerrada.")
                SessionRefreshRow(
                    userId = result.getLong("user_id"),
                    role = result.getString("role"),
                    accountStatus = result.getString("account_status"),
                    verificationStatus = result.getString("verification_status")
                )
            }
        }

        if (row.accountStatus != "ACTIVE" || row.verificationStatus != "VERIFIED") {
            throw ForbiddenException("La cuenta ya no está habilitada para mantener la sesión.")
        }
        val canonicalRole = Roles.canonical(row.role)
        connection.prepareStatement(
            "UPDATE sesiones_usuario SET last_used_at=NOW(),last_heartbeat_at=NOW(), expires_at=NOW() + (? * INTERVAL '1 day') WHERE id=?"
        ).use { statement ->
            statement.setLong(1, config.persistentSessionTtlDays)
            statement.setObject(2, sessionId)
            statement.executeUpdate()
        }
        val accessToken = accessTokenFactory(row.userId, canonicalRole, sessionId)
        RefreshSessionResponse(accessToken, sessionId.toString(), userDto(connection, row.userId))
    }


    fun revokePersistentSession(request: RefreshSessionRequest) {
        val sessionId = runCatching { UUID.fromString(request.refreshToken.trim()) }.getOrNull() ?: return
        database.transaction { connection ->
            database.ensureAuthenticationSchema(connection)
            connection.prepareStatement("UPDATE sesiones_usuario SET revoked_at=COALESCE(revoked_at,NOW()),ended_reason=COALESCE(ended_reason,'USER_LOGOUT') WHERE id=?").use { statement ->
                statement.setObject(1, sessionId)
                statement.executeUpdate()
            }
        }
    }

    fun userSessions(userId: Long): List<UserSessionDto> = database.dataSource.connection.use { connection ->
        database.ensureAuthenticationSchema(connection)
        connection.prepareStatement(
            """
            SELECT id,COALESCE(device_name,'Dispositivo Kredi+'),COALESCE(app_version,''),created_at,last_used_at,expires_at
            FROM sesiones_usuario
            WHERE user_id=? AND revoked_at IS NULL AND expires_at>NOW()
            ORDER BY COALESCE(last_used_at,created_at) DESC
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            UserSessionDto(
                                id = result.getObject(1, UUID::class.java).toString(),
                                deviceName = result.getString(2),
                                appVersion = result.getString(3),
                                createdAt = result.getObject(4, OffsetDateTime::class.java).toString(),
                                lastUsedAt = result.getObject(5, OffsetDateTime::class.java)?.toString(),
                                expiresAt = result.getObject(6, OffsetDateTime::class.java).toString()
                            )
                        )
                    }
                }
            }
        }
    }

    fun revokeUserSession(userId: Long, rawSessionId: String) {
        val sessionId = runCatching { UUID.fromString(rawSessionId.trim()) }.getOrNull()
            ?: throw AppException("La sesión indicada no es válida.")
        val updated = database.dataSource.connection.use { connection ->
            database.ensureAuthenticationSchema(connection)
            connection.prepareStatement(
                "UPDATE sesiones_usuario SET revoked_at=COALESCE(revoked_at,NOW()),ended_reason=COALESCE(ended_reason,'USER_REVOKED') WHERE id=? AND user_id=?"
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
        if (updated != 1) throw NotFoundException("La sesión ya no está disponible.")
    }

    private fun createPersistentSession(
        connection: java.sql.Connection,
        userId: Long,
        deviceIdHash: String,
        deviceName: String?,
        appVersion: String?
    ): UUID = connection.prepareStatement(
        """
        INSERT INTO sesiones_usuario(user_id,device_id_hash,device_name,app_version,expires_at,last_used_at,last_heartbeat_at)
        VALUES (?,?,?,?,NOW() + (? * INTERVAL '1 day'),NOW(),NOW()) RETURNING id
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, userId)
        statement.setString(2, deviceIdHash.trim().lowercase().takeIf { it.isNotBlank() })
        statement.setString(3, deviceName?.trim()?.take(255))
        statement.setString(4, appVersion?.trim()?.take(80))
        statement.setLong(5, config.persistentSessionTtlDays)
        statement.executeQuery().use { result ->
            if (!result.next()) error("No fue posible crear la sesión persistente.")
            result.getObject(1, UUID::class.java)
        }
    }

    fun submitDocumentVerification(
        userId: Long,
        registrationToken: String,
        documentType: String,
        documentNumber: String,
        frontPath: String,
        backPath: String?,
        selfiePath: String?
    ) {
        database.transaction { connection ->
        verifyChallenge(connection, registrationToken, userId, "REGISTRATION_DOCUMENT", consume = false)
        val normalizedDocumentType = documentType.trim().uppercase(Locale.ROOT)
        if (normalizedDocumentType !in setOf("NATIONAL_ID", "PASSPORT")) throw AppException("Selecciona Cédula de identidad o Pasaporte. El RIF se utiliza únicamente para Negocios asociados.")
        val normalizedDocumentNumber = documentNumber.trim().uppercase(Locale.ROOT)
        if (normalizedDocumentType == "NATIONAL_ID" && !normalizedDocumentNumber.matches(Regex("\\d{5,12}"))) throw AppException("El número de cédula debe contener solo números.")
        if (normalizedDocumentType == "PASSPORT" && !normalizedDocumentNumber.matches(Regex("[A-Z0-9-]{5,30}"))) throw AppException("El número de pasaporte no es válido.")
        if (frontPath.isBlank()) throw AppException("Debes adjuntar el documento de identidad.")
        if (selfiePath.isNullOrBlank()) throw AppException("Debes adjuntar una selfie actual.")
        val pendingVerificationId = connection.prepareStatement(
            "SELECT id FROM verificaciones_documentos WHERE user_id=? AND status='PENDING' ORDER BY submitted_at DESC LIMIT 1 FOR UPDATE"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }
        if (pendingVerificationId == null) {
            connection.prepareStatement(
                """
                INSERT INTO verificaciones_documentos(user_id,document_type,document_number,front_file_path,back_file_path,selfie_file_path,status)
                VALUES (?,?,?,?,?,?,'PENDING')
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, normalizedDocumentType)
                statement.setString(3, normalizedDocumentNumber)
                statement.setString(4, frontPath)
                statement.setString(5, backPath)
                statement.setString(6, selfiePath)
                statement.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                """
                UPDATE verificaciones_documentos
                SET document_type=?, document_number=?, front_file_path=?, back_file_path=?, selfie_file_path=?,
                    rejection_reason=NULL, reviewed_by=NULL, reviewed_at=NULL, updated_at=NOW()
                WHERE id=?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, normalizedDocumentType)
                statement.setString(2, normalizedDocumentNumber)
                statement.setString(3, frontPath)
                statement.setString(4, backPath)
                statement.setString(5, selfiePath)
                statement.setLong(6, pendingVerificationId)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement("UPDATE usuarios SET verification_status='PENDING', account_status='PENDING_VERIFICATION', updated_at=NOW() WHERE id=?").use {
            it.setLong(1, userId); it.executeUpdate()
        }
        audit(connection, userId, "DOCUMENT_SUBMITTED", "USER", userId.toString(), "Documento legal enviado para revisión")
    
        }
        val name = runCatching { me(userId).fullName }.getOrDefault("Un usuario")
        notifyAdmins(
            title = "Documentos recibidos",
            body = "$name envió sus documentos. Revisión pendiente del Administrador.",
            type = "DOCUMENT_SUBMITTED",
            data = buildMap {
                put("userId", userId.toString())
                put("frontPath", frontPath)
                backPath?.takeIf { it.isNotBlank() }?.let { put("backPath", it) }
                selfiePath?.takeIf { it.isNotBlank() }?.let { put("selfiePath", it) }
            }
        )
        notifyUsers(
            listOf(userId),
            "Solicitud recibida",
            "Recibimos tus documentos. Te avisaremos cuando finalice la revisión.",
            "REGISTRATION_UNDER_REVIEW"
        )
    }

    /** Valida la capacidad temporal antes de aceptar y escribir un multipart. */
    fun validateRegistrationDocumentToken(userId: Long, registrationToken: String) {
        database.transaction { connection ->
            verifyChallenge(connection, registrationToken, userId, "REGISTRATION_DOCUMENT", consume = false)
        }
    }

    fun me(userId: Long): UserDto = database.dataSource.connection.use { userDto(it, userId) }

    fun updateProfile(userId: Long, request: ProfileUpdateRequest): UserDto {
        val phone = validateProfilePhone(request.phone)
        val location = validateProfileLocation(
            request.state,
            request.municipality,
            request.parish,
            request.community,
            request.address
        )
        database.transaction { connection ->
            ensureProfilePhoneAvailable(connection, userId, phone)
            val updated = connection.prepareStatement(
                """UPDATE perfiles_usuario
                   SET phone=?,state=?,municipality=?,parish=?,community=?,address=?,updated_at=NOW()
                   WHERE user_id=?"""
            ).use { statement ->
                statement.setString(1, phone)
                statement.setString(2, location.state)
                statement.setString(3, location.municipality)
                statement.setString(4, location.parish)
                statement.setString(5, location.community)
                statement.setString(6, location.address)
                statement.setLong(7, userId)
                statement.executeUpdate()
            }
            if (updated == 0) throw NotFoundException("No se encontró el perfil del usuario.")
            audit(connection, userId, "PROFILE_UPDATED", "USER", userId.toString(), "Usuario actualizó teléfono y ubicación desde Kredi+ Mobile")
        }
        return me(userId)
    }

    fun updateProfileContact(userId: Long, request: ProfileContactUpdateRequest): UserDto {
        val phone = validateProfilePhone(request.phone)
        database.transaction { connection ->
            ensureProfilePhoneAvailable(connection, userId, phone)
            val updated = connection.prepareStatement(
                "UPDATE perfiles_usuario SET phone=?,updated_at=NOW() WHERE user_id=?"
            ).use { statement ->
                statement.setString(1, phone)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
            if (updated == 0) throw NotFoundException("No se encontró el perfil del usuario.")
            audit(connection, userId, "PROFILE_PHONE_UPDATED", "USER", userId.toString(), "Usuario actualizó su teléfono de contacto")
        }
        return me(userId)
    }

    fun updateProfileLocation(userId: Long, request: ProfileLocationUpdateRequest): UserDto {
        val location = validateProfileLocation(
            request.state,
            request.municipality,
            request.parish,
            request.community,
            request.address
        )
        database.transaction { connection ->
            val updated = connection.prepareStatement(
                """UPDATE perfiles_usuario
                   SET state=?,municipality=?,parish=?,community=?,address=?,updated_at=NOW()
                   WHERE user_id=?"""
            ).use { statement ->
                statement.setString(1, location.state)
                statement.setString(2, location.municipality)
                statement.setString(3, location.parish)
                statement.setString(4, location.community)
                statement.setString(5, location.address)
                statement.setLong(6, userId)
                statement.executeUpdate()
            }
            if (updated == 0) throw NotFoundException("No se encontró el perfil del usuario.")
            audit(connection, userId, "PROFILE_LOCATION_UPDATED", "USER", userId.toString(), "Usuario actualizó su ubicación de residencia")
        }
        return me(userId)
    }

    fun requestEmailChange(userId: Long, request: EmailChangeRequest): MessageResponse {
        if (!emailService.configured) {
            throw AppException("La verificación por correo no está disponible temporalmente. Inténtalo nuevamente más tarde.")
        }
        val newEmail = request.newEmail.trim().lowercase(Locale.ROOT)
        if (!EMAIL_REGEX.matches(newEmail)) throw AppException("Ingresa un correo electrónico válido.")

        val code = generateRecoveryCode()
        val codeHash = passwordSecurity.hash(code)
        database.transaction { connection ->
            val current = connection.prepareStatement("SELECT email FROM usuarios WHERE id=? FOR UPDATE").use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("La cuenta ya no existe.")
                    result.getString("email").orEmpty().trim().lowercase(Locale.ROOT)
                }
            }
            if (current.equals(newEmail, ignoreCase = true)) {
                throw AppException("Ese correo ya está asociado a tu cuenta.")
            }
            val duplicate = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(email)=LOWER(?) AND id<>?)"
            ).use { statement ->
                statement.setString(1, newEmail)
                statement.setLong(2, userId)
                statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
            }
            if (duplicate) throw AppException("Ese correo ya está registrado en otra cuenta.")

            connection.prepareStatement(
                "DELETE FROM codigos_verificacion WHERE user_id=? AND purpose='EMAIL_CHANGE' AND consumed_at IS NULL"
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO codigos_verificacion(user_id,channel,purpose,destination,code_hash,expires_at)
                   VALUES (?,'EMAIL','EMAIL_CHANGE',?,?,NOW() + (? * INTERVAL '1 minute'))"""
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, newEmail)
                statement.setString(3, codeHash)
                statement.setLong(4, config.passwordResetCodeTtlMinutes)
                statement.executeUpdate()
            }
            audit(connection, userId, "EMAIL_CHANGE_REQUESTED", "USER", userId.toString(), "Código de confirmación enviado al nuevo correo")
        }

        try {
            emailService.sendEmailChangeCode(newEmail, code, config.passwordResetCodeTtlMinutes)
        } catch (error: Throwable) {
            cleanupUnsentSecurityCode(userId, "EMAIL_CHANGE")
            logger.error("No fue posible enviar el correo de cambio de dirección para el usuario {}.", userId, error)
            throw AppException("No fue posible enviar el código al nuevo correo. Inténtalo nuevamente en unos minutos.")
        }
        return MessageResponse("Enviamos un código de ${config.passwordResetCodeLength} dígitos al nuevo correo. Escríbelo para confirmar el cambio.")
    }

    fun confirmEmailChange(userId: Long, request: EmailChangeConfirmRequest): MessageResponse {
        val newEmail = request.newEmail.trim().lowercase(Locale.ROOT)
        if (!EMAIL_REGEX.matches(newEmail)) throw AppException("Ingresa un correo electrónico válido.")
        val code = request.code.filter(Char::isDigit)
        if (code.length != config.passwordResetCodeLength) {
            throw AppException("Ingresa el código de ${config.passwordResetCodeLength} dígitos enviado al nuevo correo.")
        }

        val result = database.transaction { connection ->
            val duplicate = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(email)=LOWER(?) AND id<>?)"
            ).use { statement ->
                statement.setString(1, newEmail)
                statement.setLong(2, userId)
                statement.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
            }
            if (duplicate) throw AppException("Ese correo ya está registrado en otra cuenta.")

            val codeResult = consumeEmailChangeCode(connection, userId, newEmail, code)
            if (codeResult == RecoveryCodeResult.VALID) {
                val updated = connection.prepareStatement(
                    "UPDATE usuarios SET email=?,email_verified=TRUE,updated_at=NOW() WHERE id=?"
                ).use { statement ->
                    statement.setString(1, newEmail)
                    statement.setLong(2, userId)
                    statement.executeUpdate()
                }
                if (updated == 0) throw NotFoundException("La cuenta ya no existe.")
                audit(connection, userId, "EMAIL_CHANGE_CONFIRMED", "USER", userId.toString(), "Usuario confirmó y actualizó su correo electrónico")
            }
            codeResult
        }

        return when (result) {
            RecoveryCodeResult.VALID -> MessageResponse("Correo actualizado y verificado.")
            RecoveryCodeResult.LOCKED -> throw AppException("Superaste el número de intentos permitidos. Solicita un código nuevo.")
            RecoveryCodeResult.INVALID, RecoveryCodeResult.MISSING -> throw AppException("El código es inválido, no corresponde a ese correo o venció.")
        }
    }

    private data class ValidatedProfileLocation(
        val state: String,
        val municipality: String,
        val parish: String,
        val community: String,
        val address: String
    )

    private fun validateProfilePhone(raw: String): String = normalizeVenezuelanPhone(raw)
        ?: throw AppException("Ingresa un celular venezolano válido, por ejemplo +58 412-1234567.")

    private fun ensureProfilePhoneAvailable(connection: Connection, userId: Long, phone: String) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { result -> result.next() }
        }
        val duplicate = connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM perfiles_usuario WHERE phone=? AND user_id<>?)"
        ).use { statement ->
            statement.setString(1, phone)
            statement.setLong(2, userId)
            statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
        }
        if (duplicate) throw AppException("Ese número de teléfono ya está registrado en otra cuenta.")
    }

    private fun validateProfileLocation(
        rawState: String,
        rawMunicipality: String,
        rawParish: String,
        rawCommunity: String,
        rawAddress: String
    ): ValidatedProfileLocation {
        val state = rawState.trim()
        val municipality = rawMunicipality.trim()
        val parish = rawParish.trim()
        val community = rawCommunity.trim()
        val address = rawAddress.trim()

        if (state.isBlank()) throw AppException("Selecciona un estado.")
        if (municipality.isBlank()) throw AppException("Selecciona un municipio.")
        if (parish.isBlank()) throw AppException("Selecciona una parroquia.")
        if (community.isBlank()) throw AppException("Selecciona o escribe tu comunidad o sector.")
        if (address.isBlank()) throw AppException("Ingresa tu dirección.")

        listOf(state, municipality, parish, community).forEach { value ->
            if (value.length > 180 || !isSafeLocationText(value)) {
                throw AppException("La ubicación contiene caracteres no permitidos.")
            }
        }
        if (address.length > 500 || !isSafeAddressText(address)) {
            throw AppException("La dirección contiene caracteres no permitidos o es demasiado larga.")
        }
        return ValidatedProfileLocation(state, municipality, parish, community, address)
    }

    private fun consumeEmailChangeCode(
        connection: Connection,
        userId: Long,
        destination: String,
        code: String
    ): RecoveryCodeResult {
        val row = connection.prepareStatement(
            """SELECT id,code_hash,attempts
               FROM codigos_verificacion
               WHERE user_id=? AND channel='EMAIL' AND purpose='EMAIL_CHANGE'
                 AND LOWER(destination)=LOWER(?)
                 AND consumed_at IS NULL AND expires_at>NOW()
               ORDER BY created_at DESC
               LIMIT 1
               FOR UPDATE"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, destination)
            statement.executeQuery().use { rs ->
                if (!rs.next()) null else RecoveryCodeRow(rs.getLong("id"), rs.getString("code_hash"), rs.getInt("attempts"))
            }
        } ?: return RecoveryCodeResult.MISSING

        if (row.attempts >= config.passwordResetMaxAttempts) {
            connection.prepareStatement("UPDATE codigos_verificacion SET consumed_at=NOW() WHERE id=?").use { statement ->
                statement.setLong(1, row.id)
                statement.executeUpdate()
            }
            return RecoveryCodeResult.LOCKED
        }
        if (!passwordSecurity.verify(row.hash, code)) {
            val nextAttempts = row.attempts + 1
            connection.prepareStatement(
                "UPDATE codigos_verificacion SET attempts=?, consumed_at=CASE WHEN ?>=? THEN NOW() ELSE consumed_at END WHERE id=?"
            ).use { statement ->
                statement.setInt(1, nextAttempts)
                statement.setInt(2, nextAttempts)
                statement.setInt(3, config.passwordResetMaxAttempts)
                statement.setLong(4, row.id)
                statement.executeUpdate()
            }
            return if (nextAttempts >= config.passwordResetMaxAttempts) RecoveryCodeResult.LOCKED else RecoveryCodeResult.INVALID
        }
        connection.prepareStatement("UPDATE codigos_verificacion SET consumed_at=NOW() WHERE id=?").use { statement ->
            statement.setLong(1, row.id)
            statement.executeUpdate()
        }
        return RecoveryCodeResult.VALID
    }

    fun usuarios(): List<UserDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id FROM usuarios ORDER BY created_at DESC").use { statement ->
            statement.executeQuery().use { result -> buildList { while (result.next()) add(userDto(connection, result.getLong(1))) } }
        }
    }

    fun registrationRequests(): List<UserDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT id FROM usuarios
            WHERE UPPER(role) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS','WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA')
              AND account_status='PENDING_VERIFICATION'
              AND verification_status IN ('NOT_SUBMITTED','PENDING')
            ORDER BY created_at ASC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(userDto(connection, result.getLong(1))) }
            }
        }
    }

    fun registerBiometricCredential(userId: Long, request: BiometricCredentialRequest): BiometricCredentialResponse {
        val deviceIdHash = request.deviceIdHash.trim().lowercase()
        val declaredPublicKeyHash = request.publicKeyHash.trim().lowercase()
        val algorithm = request.keyAlgorithm.trim().uppercase()
        if (!deviceIdHash.matches(Regex("[a-f0-9]{64}"))) throw AppException("Identificador criptográfico de dispositivo inválido.")
        if (!declaredPublicKeyHash.matches(Regex("[a-f0-9]{64}"))) throw AppException("Hash de clave pública inválido.")
        if (algorithm !in setOf("EC", "RSA")) throw AppException("Algoritmo biométrico no admitido.")
        val publicKeyBytes = runCatching { Base64.getDecoder().decode(request.publicKeyBase64.trim()) }
            .getOrElse { throw AppException("Clave pública biométrica inválida.") }
        if (publicKeyBytes.size !in 64..4096) throw AppException("Tamaño de clave pública biométrica inválido.")
        runCatching {
            KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(publicKeyBytes))
        }.getOrElse { throw AppException("La credencial biométrica no contiene una clave pública válida.") }
        val computedHash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (computedHash != declaredPublicKeyHash) throw AppException("La integridad de la credencial biométrica no pudo verificarse.")

        return database.transaction { connection ->
            // Autorreparación C32: algunas instalaciones antiguas continuaban activas
            // aunque una migración histórica se hubiera detenido antes de crear esta tabla.
            ensureBiometricCredentialSchema(connection)
            val row = connection.prepareStatement(
                """
                INSERT INTO credenciales_biometricas_dispositivo(
                    user_id,device_id_hash,public_key_hash,public_key_base64,key_algorithm,
                    platform,device_name,app_version,enabled,updated_at
                ) VALUES (?,?,?,?,?,?,?,?,TRUE,NOW())
                ON CONFLICT(user_id,device_id_hash) DO UPDATE SET
                    public_key_hash=EXCLUDED.public_key_hash,
                    public_key_base64=EXCLUDED.public_key_base64,
                    key_algorithm=EXCLUDED.key_algorithm,
                    platform=EXCLUDED.platform,
                    device_name=EXCLUDED.device_name,
                    app_version=EXCLUDED.app_version,
                    enabled=TRUE,
                    updated_at=NOW()
                RETURNING id,registered_at
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, deviceIdHash)
                statement.setString(3, declaredPublicKeyHash)
                statement.setString(4, request.publicKeyBase64.trim())
                statement.setString(5, algorithm)
                statement.setString(6, request.platform.trim().uppercase().ifBlank { "ANDROID" }.take(30))
                statement.setString(7, request.deviceName?.trim()?.takeIf(String::isNotBlank)?.take(255))
                statement.setString(8, request.appVersion?.trim()?.takeIf(String::isNotBlank)?.take(80))
                statement.executeQuery().use { result ->
                    result.next()
                    BiometricCredentialResponse(
                        id = result.getLong("id"),
                        deviceIdHash = deviceIdHash,
                        publicKeyHash = declaredPublicKeyHash,
                        enabled = true,
                        registeredAt = result.getObject("registered_at", OffsetDateTime::class.java).toString()
                    )
                }
            }
            audit(
                connection,
                userId,
                "BIOMETRIC_DEVICE_REGISTERED",
                "BIOMETRIC_DEVICE",
                deviceIdHash.take(16),
                "Credencial criptográfica biométrica registrada para Android"
            )
            row
        }
    }

    fun disableBiometricCredential(userId: Long, request: BiometricCredentialDisableRequest) {
        val deviceIdHash = request.deviceIdHash.trim().lowercase()
        if (!deviceIdHash.matches(Regex("[a-f0-9]{64}"))) {
            throw AppException("Identificador criptográfico de dispositivo inválido.")
        }
        database.transaction { connection ->
            ensureBiometricCredentialSchema(connection)
            connection.prepareStatement(
                """UPDATE credenciales_biometricas_dispositivo
                   SET enabled=FALSE,updated_at=NOW()
                   WHERE user_id=? AND device_id_hash=?"""
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, deviceIdHash)
                statement.executeUpdate()
            }
            audit(
                connection, userId, "BIOMETRIC_DEVICE_DISABLED", "BIOMETRIC_DEVICE",
                deviceIdHash.take(16), "Protección biométrica desactivada por el usuario"
            )
        }
    }

    fun registerDeviceToken(userId: Long, request: DeviceTokenRequest) {
        database.transaction { connection -> upsertDeviceToken(connection, userId, request) }
    }

    fun unregisterDeviceToken(userId: Long, request: DeviceTokenRequest) {
        val token = request.token.trim()
        if (token.isBlank()) return
        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM tokens_dispositivo WHERE user_id=? AND token=?").use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, token)
                statement.executeUpdate()
            }
        }
    }

    fun registerPendingDeviceToken(userId: Long, registrationToken: String, request: DeviceTokenRequest) {
        database.transaction { connection ->
            verifyChallenge(connection, registrationToken, userId, "REGISTRATION_DOCUMENT", consume = false)
            upsertDeviceToken(connection, userId, request)
        }
    }

    fun markNotificationRead(userId: Long, notificationId: Long) {
        val updated = database.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE notificaciones SET read_at=COALESCE(read_at,NOW()) WHERE id=? AND user_id=?").use { statement ->
                statement.setLong(1, notificationId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
        if (updated != 1) throw NotFoundException("La notificación ya no está disponible.")
    }

    fun markAllNotificationsRead(userId: Long) {
        database.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE notificaciones SET read_at=COALESCE(read_at,NOW()) WHERE user_id=?").use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    fun clearNotifications(userId: Long) {
        database.transaction { connection ->
            connection.prepareStatement("DELETE FROM notificaciones WHERE user_id=?").use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    fun notificaciones(userId: Long): List<NotificationDto> = database.dataSource.connection.use { connection ->
        val rows = connection.prepareStatement(
            "SELECT id,title,body,type,payload::text,created_at FROM notificaciones WHERE user_id=? ORDER BY created_at DESC LIMIT 100"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val details = parseNotificationDetails(result.getString(5))
                    add(
                        NotificationDto(
                            id = result.getLong(1),
                            title = result.getString(2),
                            body = result.getString(3),
                            type = result.getString(4),
                            createdAt = result.getObject(6, OffsetDateTime::class.java).toString(),
                            details = details
                        )
                    )
                }
            } }
        }
        rows.map { notification ->
            notification.copy(attachments = notificationAttachments(connection, notification.type, notification.details))
        }
    }

    private fun parseNotificationDetails(rawPayload: String?): Map<String, String> = runCatching {
        if (rawPayload.isNullOrBlank()) return@runCatching emptyMap()
        val objectValue = JsonParser.parseString(rawPayload).asJsonObject
        objectValue.entrySet().mapNotNull { (key, value) ->
            if (value == null || value.isJsonNull || value.isJsonObject || value.isJsonArray) null
            else key to value.asString
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun notificationAttachments(
        connection: Connection,
        type: String,
        details: Map<String, String>
    ): List<NotificationAttachmentDto> {
        return when (type) {
            "DOCUMENT_SUBMITTED" -> {
                val directAttachments = buildList {
                    details["frontPath"]?.let(::publicUrl)?.let { add(NotificationAttachmentDto("Documento de identidad", it)) }
                    details["backPath"]?.let(::publicUrl)?.let { add(NotificationAttachmentDto("Documento adicional (histórico)", it)) }
                    details["selfiePath"]?.let(::publicUrl)?.let { add(NotificationAttachmentDto("Selfie", it)) }
                }
                if (directAttachments.isNotEmpty()) {
                    directAttachments
                } else {
                    val submittedUserId = details["userId"]?.toLongOrNull()
                    if (submittedUserId == null) {
                        emptyList()
                    } else {
                        connection.prepareStatement(
                            "SELECT front_file_path,back_file_path,selfie_file_path FROM verificaciones_documentos WHERE user_id=? ORDER BY submitted_at DESC LIMIT 1"
                        ).use { statement ->
                            statement.setLong(1, submittedUserId)
                            statement.executeQuery().use { result ->
                                if (!result.next()) emptyList() else buildList {
                                    publicUrl(result.getString(1))?.let { add(NotificationAttachmentDto("Documento de identidad", it)) }
                                    publicUrl(result.getString(2))?.let { add(NotificationAttachmentDto("Documento adicional (histórico)", it)) }
                                    publicUrl(result.getString(3))?.let { add(NotificationAttachmentDto("Selfie", it)) }
                                }
                            }
                        }
                    }
                }
            }
            "PAYMENT_VERIFICATION_REQUIRED" -> {
                val directProof = details["proofPath"]?.let(::publicUrl)
                if (directProof != null) {
                    listOf(NotificationAttachmentDto("Comprobante de pago", directProof))
                } else {
                    val orderId = details["orderId"]?.toLongOrNull()
                    if (orderId == null) {
                        emptyList()
                    } else {
                        connection.prepareStatement(
                            "SELECT proof_file_path FROM solicitudes_verificacion_pago WHERE order_id=? ORDER BY created_at DESC LIMIT 1"
                        ).use { statement ->
                            statement.setLong(1, orderId)
                            statement.executeQuery().use { result ->
                                if (!result.next()) emptyList() else publicUrl(result.getString(1))
                                    ?.let { listOf(NotificationAttachmentDto("Comprobante de pago", it)) }
                                    ?: emptyList()
                            }
                        }
                    }
                }
            }
            else -> emptyList()
        }
    }

    fun pendingVerifications(): List<VerificationDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT DISTINCT ON (dv.user_id)
                   dv.id,dv.user_id,dv.document_type,dv.document_number,dv.front_file_path,dv.back_file_path,dv.selfie_file_path,
                   dv.status,dv.rejection_reason,dv.submitted_at
            FROM verificaciones_documentos dv
            WHERE dv.status='PENDING'
            ORDER BY dv.user_id, dv.submitted_at DESC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(verificationDto(connection, result))
            } }
        }
    }

    fun reviewVerification(adminId: Long, verificationId: Long, request: ReviewVerificationRequest) {
        requireRegistrationReviewer(adminId)
        val userId = database.transaction { connection ->
        val userId = connection.prepareStatement("SELECT user_id FROM verificaciones_documentos WHERE id=? AND status='PENDING' FOR UPDATE").use { statement ->
            statement.setLong(1, verificationId)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else throw NotFoundException("La verificación ya no está pendiente.") }
        }
        val status = if (request.approved) "APPROVED" else "REJECTED"
        connection.prepareStatement(
            "UPDATE verificaciones_documentos SET status=?, rejection_reason=?, reviewed_by=?, reviewed_at=NOW(), updated_at=NOW() WHERE id=?"
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, request.rejectionReason?.trim()?.takeIf { it.isNotEmpty() })
            statement.setLong(3, adminId)
            statement.setLong(4, verificationId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE usuarios SET verification_status=?, account_status=?, updated_at=NOW() WHERE id=?"
        ).use { statement ->
            statement.setString(1, if (request.approved) "VERIFIED" else "REJECTED")
            statement.setString(2, if (request.approved) "ACTIVE" else "REJECTED")
            statement.setLong(3, userId)
            statement.executeUpdate()
        }
        if (request.approved) ensureCreditAccount(connection, userId)
        val reviewerRole = Roles.canonical(sessionRole(adminId))
        val actorPrefix = if (reviewerRole == Roles.ACCOUNTANT) "ACCOUNTANT" else "ADMIN"
        val auditAction = if (request.approved) "${actorPrefix}_APPROVED_USER" else "${actorPrefix}_REJECTED_USER"
        audit(connection, adminId, auditAction, "USER", userId.toString(), request.rejectionReason)
        userId
    
        }
        notifyVerificationResult(userId, request.approved, request.rejectionReason)
    }

    fun reviewUserVerification(adminId: Long, userId: Long, request: ReviewVerificationRequest) {
        requireRegistrationReviewer(adminId)
        database.transaction { connection ->
        val verificationId = connection.prepareStatement(
            "SELECT id FROM verificaciones_documentos WHERE user_id=? AND status='PENDING' ORDER BY submitted_at DESC LIMIT 1 FOR UPDATE"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getLong(1) else throw NotFoundException("No hay una verificación pendiente para este usuario.")
            }
        }
        val status = if (request.approved) "APPROVED" else "REJECTED"
        connection.prepareStatement(
            "UPDATE verificaciones_documentos SET status=?, rejection_reason=?, reviewed_by=?, reviewed_at=NOW(), updated_at=NOW() WHERE id=?"
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, request.rejectionReason?.trim()?.takeIf { it.isNotEmpty() })
            statement.setLong(3, adminId)
            statement.setLong(4, verificationId)
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE usuarios SET verification_status=?, account_status=?, updated_at=NOW() WHERE id=?").use { statement ->
            statement.setString(1, if (request.approved) "VERIFIED" else "REJECTED")
            statement.setString(2, if (request.approved) "ACTIVE" else "REJECTED")
            statement.setLong(3, userId)
            statement.executeUpdate()
        }
        if (request.approved) ensureCreditAccount(connection, userId)
        val reviewerRole = Roles.canonical(sessionRole(adminId))
        val actorPrefix = if (reviewerRole == Roles.ACCOUNTANT) "ACCOUNTANT" else "ADMIN"
        val auditAction = if (request.approved) "${actorPrefix}_APPROVED_USER" else "${actorPrefix}_REJECTED_USER"
        audit(connection, adminId, auditAction, "USER", userId.toString(), request.rejectionReason)
    
        }
        notifyVerificationResult(userId, request.approved, request.rejectionReason)
    }

    fun banks(): List<BankDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT code,name FROM directorio_bancos WHERE active=TRUE ORDER BY code").use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(BankDto(result.getString(1), result.getString(2)))
            } }
        }
    }

    fun productos(): List<ProductDto> {
        // La API BCV se consulta antes de abrir la transacción para no retener una conexión
        // PostgreSQL durante una operación de red.
        val rate = runCatching { bcvRateService.currentUsdRate().rate }
            .mapCatching { MoneyMath.positive(MoneyMath.rate(it), "Tasa BCV") }
            .getOrNull()
        return database.transaction { connection ->
            if (rate != null) {
                refreshProductPricesForCurrentBcv(connection, rate)
                refreshComboPricesForCurrentBcv(connection, rate)
            }
            connection.prepareStatement(
                """SELECT p.id,p.name,p.category,p.unit,p.technical_details,p.base_price,p.stock,p.base_price_usd,p.bcv_rate,p.pricing_mode,
                          (SELECT pj.image_path FROM productos_jornada pj
                           WHERE pj.product_id=p.id AND pj.image_path IS NOT NULL AND BTRIM(pj.image_path)<>''
                           ORDER BY pj.fair_id DESC LIMIT 1) AS image_path
                   FROM productos p WHERE p.active=TRUE ORDER BY p.name""".trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result -> buildList { while (result.next()) add(productDto(result)) } }
            }
        }
    }

    private fun refreshProductPricesForCurrentBcv(connection: Connection, rate: java.math.BigDecimal) {
        // Los productos heredados solo tenían precio en bolívares. Se deriva una vez el
        // precio maestro USD con la tasa vigente y desde allí se actualiza el equivalente BCV.
        connection.prepareStatement(
            """
            UPDATE productos
            SET base_price_usd=ROUND(base_price / ?, 6),bcv_rate=?,price_updated_at=NOW(),updated_at=NOW()
            WHERE active=TRUE AND base_price>0 AND base_price_usd<=0
            """.trimIndent()
        ).use { statement ->
            statement.setBigDecimal(1, rate)
            statement.setBigDecimal(2, rate)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            UPDATE productos
            SET base_price=ROUND(base_price_usd * ?, 2),bcv_rate=?,price_updated_at=NOW(),updated_at=NOW()
            WHERE active=TRUE AND base_price_usd>0 AND (bcv_rate IS DISTINCT FROM ?)
            """.trimIndent()
        ).use { statement ->
            statement.setBigDecimal(1, rate)
            statement.setBigDecimal(2, rate)
            statement.setBigDecimal(3, rate)
            statement.executeUpdate()
        }
    }


    /**
     * Mantiene el equivalente en bolívares del precio propio de cada combo.
     * Para combos heredados sin precio independiente, inicializa una sola vez el precio USD
     * con la suma de los precios individuales actuales; luego ese precio queda desacoplado.
     */
    private fun refreshComboPricesForCurrentBcv(connection: Connection, rate: java.math.BigDecimal) {
        connection.prepareStatement(
            """
            UPDATE combos c
            SET combo_price_usd=ROUND(x.individual_usd,2),
                combo_price_bs=ROUND(x.individual_usd * ?,2),
                bcv_rate=?,price_updated_at=NOW(),updated_at=NOW()
            FROM (
                SELECT pc.combo_id,COALESCE(SUM(pc.quantity * p.base_price_usd),0) AS individual_usd
                FROM productos_combo pc
                JOIN productos p ON p.id=pc.product_id AND p.active=TRUE
                GROUP BY pc.combo_id
            ) x
            WHERE c.id=x.combo_id AND c.active=TRUE AND c.combo_price_usd<=0 AND x.individual_usd>0
            """.trimIndent()
        ).use { statement ->
            statement.setBigDecimal(1, rate)
            statement.setBigDecimal(2, rate)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            UPDATE combos
            SET combo_price_bs=ROUND(combo_price_usd * ?,2),bcv_rate=?,price_updated_at=NOW(),updated_at=NOW()
            WHERE active=TRUE AND combo_price_usd>0 AND (bcv_rate IS DISTINCT FROM ?)
            """.trimIndent()
        ).use { statement ->
            statement.setBigDecimal(1, rate)
            statement.setBigDecimal(2, rate)
            statement.setBigDecimal(3, rate)
            statement.executeUpdate()
        }
    }

    private fun latestCatalogBcvRate(connection: Connection): java.math.BigDecimal? =
        connection.prepareStatement(
            "SELECT NULLIF(MAX(rate_value),0) FROM (SELECT MAX(bcv_rate) AS rate_value FROM productos UNION ALL SELECT MAX(bcv_rate) FROM combos) rates"
        ).use { statement ->
            statement.executeQuery().use { result ->
                if (!result.next()) null else result.getBigDecimal(1)?.takeIf { it.signum() > 0 }
            }
        }

    fun createProduct(adminId: Long, request: CreateProductRequest): ProductDto {
        val pricingMode = request.pricingMode.trim().uppercase().takeIf { it in setOf("UNIT", "KG") }
            ?: throw AppException("El tipo de precio debe ser por unidad o por kilogramo.")
        val normalizedName = request.name.trim()
        val normalizedMetadata = ProductMetadataPolicy.normalize(request.category, request.details)
        val normalizedCategory = normalizedMetadata.category
        val normalizedDetails = normalizedMetadata.details
        if (
            request.name.isBlank() || request.category.isBlank() || request.stock < 0 ||
            (pricingMode == "UNIT" && request.unit.isBlank())
        ) {
            throw AppException("Datos de producto inválidos.")
        }
        if (normalizedName.length > 180) throw AppException("El nombre del producto supera el máximo permitido de 180 caracteres.")
        if (normalizedCategory.length > 140) throw AppException("La categoría del producto supera el máximo permitido. Revisa la clasificación y la marca.")
        if (normalizedDetails.length > 4000) throw AppException("La ficha técnica del producto supera el máximo permitido de 4000 caracteres.")
        val normalizedClassification = InventoryProductValidator.classificationOf(normalizedCategory)
        if (normalizedClassification in InventoryProductValidator.technologyClassifications && normalizedDetails.isBlank()) {
            throw AppException("Indica los componentes o especificaciones del producto tecnológico.")
        }
        if (normalizedClassification in InventoryProductValidator.pharmacyClassifications && normalizedDetails.isBlank()) {
            throw AppException("Completa la ficha farmacéutica del producto.")
        }
        validateFoodProduct(normalizedName, normalizedCategory)
        val currentRate = runCatching { bcvRateService.currentUsdRate() }
            .getOrElse { throw AppException("No se pudo consultar la tasa BCV para calcular el precio en bolívares.") }
        val rate = runCatching { MoneyMath.positive(MoneyMath.rate(currentRate.rate), "Tasa BCV") }
            .getOrElse { throw AppException(it.message ?: "La tasa BCV no es válida.") }

        val priceUsd = when {
            request.priceUsd != null -> runCatching { MoneyMath.positive(MoneyMath.usd(request.priceUsd, "Precio USD"), "Precio USD") }
                .getOrElse { throw AppException(it.message ?: "El precio en dólares no es válido.") }
            request.price != null -> {
                val legacyBs = runCatching { MoneyMath.positive(MoneyMath.ves(request.price, "Precio Bs"), "Precio Bs") }
                    .getOrElse { throw AppException(it.message ?: "El precio no es válido.") }
                MoneyMath.vesToUsd(legacyBs, rate)
            }
            else -> throw AppException("Ingresa el precio del producto en dólares.")
        }
        val priceBs = MoneyMath.usdToVes(priceUsd, rate)
        val normalizedUnit = if (pricingMode == "KG") "kg" else request.unit.trim()
        if (normalizedUnit.length > 120) throw AppException("La presentación o unidad supera el máximo permitido de 120 caracteres.")

        val product = database.transaction { connection ->
            val productLockKey = "product:${normalizedName.lowercase()}:${normalizedCategory.lowercase()}"
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
                statement.setString(1, productLockKey)
                statement.execute()
            }
            connection.prepareStatement(
                """SELECT id FROM productos
                   WHERE active=TRUE
                     AND LOWER(BTRIM(name))=LOWER(BTRIM(?))
                     AND LOWER(BTRIM(category))=LOWER(BTRIM(?))
                   LIMIT 1 FOR UPDATE"""
            ).use { statement ->
                statement.setString(1, normalizedName)
                statement.setString(2, normalizedCategory)
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        throw AppException("Este producto ya existe en el inventario compartido. Actualiza su existencia en lugar de duplicarlo.")
                    }
                }
            }
            val id = connection.prepareStatement(
                """
                INSERT INTO productos(name,category,unit,technical_details,base_price,base_price_usd,bcv_rate,pricing_mode,stock,created_by,price_updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,NOW()) RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, normalizedName)
                statement.setString(2, normalizedCategory)
                statement.setString(3, normalizedUnit)
                statement.setString(4, normalizedDetails)
                statement.setBigDecimal(5, priceBs)
                statement.setBigDecimal(6, priceUsd)
                statement.setBigDecimal(7, rate)
                statement.setString(8, pricingMode)
                statement.setInt(9, request.stock)
                statement.setLong(10, adminId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
            if (request.stock != 0) {
                connection.prepareStatement("INSERT INTO movimientos_inventario(product_id,movement_type,quantity_delta,notes,performed_by) VALUES (?,'INITIAL',?,'Cantidad inicial',?)").use {
                    it.setLong(1, id); it.setInt(2, request.stock); it.setLong(3, adminId); it.executeUpdate()
                }
            }
            audit(
                connection,
                adminId,
                "INVENTORY_PRODUCT_CREATED",
                "PRODUCT",
                id.toString(),
                "$normalizedName · USD ${priceUsd.toPlainString()} · Bs ${priceBs.toPlainString()} · $pricingMode"
            )
            findProduct(connection, id)
        }
        notifyBeneficiaries(
            "Nuevo producto",
            "${product.name} ya forma parte del catálogo de Kredi+.",
            "NEW_PRODUCT",
            mapOf("productId" to product.id.toString())
        )
        return product
    }

    fun updateProduct(adminId: Long, productId: Long, request: CreateProductRequest): ProductDto {
        val pricingMode = request.pricingMode.trim().uppercase().takeIf { it in setOf("UNIT", "KG") }
            ?: throw AppException("El tipo de precio debe ser por unidad o por kilogramo.")
        val normalizedName = request.name.trim()
        val normalizedMetadata = ProductMetadataPolicy.normalize(request.category, request.details)
        val normalizedCategory = normalizedMetadata.category
        val normalizedDetails = normalizedMetadata.details
        if (normalizedName.isBlank() || normalizedCategory.isBlank() || request.stock < 0 || (pricingMode == "UNIT" && request.unit.isBlank())) {
            throw AppException("Datos de producto inválidos.")
        }
        if (normalizedName.length > 180) throw AppException("El nombre del producto supera el máximo permitido de 180 caracteres.")
        if (normalizedCategory.length > 140) throw AppException("La categoría del producto supera el máximo permitido.")
        if (normalizedDetails.length > 4000) throw AppException("La ficha técnica del producto supera el máximo permitido de 4000 caracteres.")
        validateFoodProduct(normalizedName, normalizedCategory)

        val rateSnapshot = runCatching { bcvRateService.currentUsdRate() }
            .getOrElse { throw AppException("No se pudo consultar la tasa BCV para actualizar el producto.") }
        val rate = runCatching { MoneyMath.positive(MoneyMath.rate(rateSnapshot.rate), "Tasa BCV") }
            .getOrElse { throw AppException(it.message ?: "La tasa BCV no es válida.") }
        val priceUsd = when {
            request.priceUsd != null -> runCatching { MoneyMath.positive(MoneyMath.usd(request.priceUsd, "Precio USD"), "Precio USD") }
                .getOrElse { throw AppException(it.message ?: "El precio en dólares no es válido.") }
            request.price != null -> {
                val legacyBs = runCatching { MoneyMath.positive(MoneyMath.ves(request.price, "Precio Bs"), "Precio Bs") }
                    .getOrElse { throw AppException(it.message ?: "El precio no es válido.") }
                MoneyMath.vesToUsd(legacyBs, rate)
            }
            else -> throw AppException("Ingresa el precio del producto en dólares.")
        }
        val priceBs = MoneyMath.usdToVes(priceUsd, rate)
        val normalizedUnit = if (pricingMode == "KG") "kg" else request.unit.trim()

        return database.transaction { connection ->
            val currentStock = connection.prepareStatement("SELECT stock FROM productos WHERE id=? AND active=TRUE FOR UPDATE").use { st ->
                st.setLong(1, productId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else throw NotFoundException("El producto no existe.") }
            }
            connection.prepareStatement(
                """SELECT id FROM productos
                   WHERE id<>? AND active=TRUE
                     AND LOWER(BTRIM(name))=LOWER(BTRIM(?))
                     AND LOWER(BTRIM(category))=LOWER(BTRIM(?))
                   LIMIT 1"""
            ).use { st ->
                st.setLong(1, productId); st.setString(2, normalizedName); st.setString(3, normalizedCategory)
                st.executeQuery().use { rs -> if (rs.next()) throw AppException("Ya existe otro producto con ese nombre y categoría.") }
            }
            connection.prepareStatement(
                """UPDATE productos
                   SET name=?,category=?,unit=?,technical_details=?,base_price=?,base_price_usd=?,bcv_rate=?,pricing_mode=?,stock=?,price_updated_at=NOW(),updated_at=NOW()
                   WHERE id=? AND active=TRUE"""
            ).use { st ->
                st.setString(1, normalizedName)
                st.setString(2, normalizedCategory)
                st.setString(3, normalizedUnit)
                st.setString(4, normalizedDetails)
                st.setBigDecimal(5, priceBs)
                st.setBigDecimal(6, priceUsd)
                st.setBigDecimal(7, rate)
                st.setString(8, pricingMode)
                st.setInt(9, request.stock)
                st.setLong(10, productId)
                if (st.executeUpdate() != 1) throw NotFoundException("El producto no existe.")
            }
            val delta = request.stock - currentStock
            if (delta != 0) {
                connection.prepareStatement("INSERT INTO movimientos_inventario(product_id,movement_type,quantity_delta,notes,performed_by) VALUES (?,'COUNT',?,?,?)").use { st ->
                    st.setLong(1, productId); st.setInt(2, delta); st.setString(3, "Edición de producto: $currentStock → ${request.stock}"); st.setLong(4, adminId); st.executeUpdate()
                }
            }
            audit(connection, adminId, "ADMIN_UPDATED_PRODUCT", "PRODUCT", productId.toString(), "$normalizedName · USD ${priceUsd.toPlainString()} · existencia ${request.stock}")
            findProduct(connection, productId)
        }
    }

    fun setProductPricing(adminId: Long, productId: Long, request: UpdateProductPricingRequest): ProductDto {
        val pricingMode = request.pricingMode.trim().uppercase().takeIf { it in setOf("UNIT", "KG") }
            ?: throw AppException("El tipo de precio debe ser por unidad o por kilogramo.")
        val rateSnapshot = runCatching { bcvRateService.currentUsdRate() }
            .getOrElse { throw AppException("No se pudo consultar la tasa BCV para actualizar el precio.") }
        val rate = runCatching { MoneyMath.positive(MoneyMath.rate(rateSnapshot.rate), "Tasa BCV") }
            .getOrElse { throw AppException(it.message ?: "La tasa BCV no es válida.") }
        val priceUsd = runCatching { MoneyMath.positive(MoneyMath.usd(request.priceUsd, "Precio USD"), "Precio USD") }
            .getOrElse { throw AppException(it.message ?: "El precio en dólares no es válido.") }
        val priceBs = MoneyMath.usdToVes(priceUsd, rate)

        return database.transaction { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE productos
                SET base_price=?,base_price_usd=?,bcv_rate=?,pricing_mode=?,
                    unit=CASE WHEN ?='KG' THEN 'kg' WHEN pricing_mode='KG' THEN 'unidad' ELSE unit END,price_updated_at=NOW(),updated_at=NOW()
                WHERE id=? AND active=TRUE
                """.trimIndent()
            ).use { statement ->
                statement.setBigDecimal(1, priceBs)
                statement.setBigDecimal(2, priceUsd)
                statement.setBigDecimal(3, rate)
                statement.setString(4, pricingMode)
                statement.setString(5, pricingMode)
                statement.setLong(6, productId)
                statement.executeUpdate()
            }
            if (updated != 1) throw NotFoundException("El producto no existe.")
            audit(
                connection,
                adminId,
                "ADMIN_UPDATED_PRODUCT_PRICE",
                "PRODUCT",
                productId.toString(),
                "USD ${priceUsd.toPlainString()} · Bs ${priceBs.toPlainString()} · $pricingMode"
            )
            findProduct(connection, productId)
        }
    }

    fun deleteProduct(adminId: Long, productId: Long) {
        database.transaction { connection ->
            val updated = connection.prepareStatement("UPDATE productos SET active=FALSE,updated_at=NOW() WHERE id=? AND active=TRUE").use { statement ->
                statement.setLong(1, productId)
                statement.executeUpdate()
            }
            if (updated == 0) throw NotFoundException("El producto no existe o ya fue eliminado.")
            audit(connection, adminId, "ADMIN_DELETED_PRODUCT", "PRODUCT", productId.toString(), "Producto desactivado")
        }
    }

    fun setProductStock(adminId: Long, productId: Long, stock: Int): ProductDto = database.transaction { connection ->
        if (stock < 0) throw AppException("La cantidad de producto no puede ser negativa.")
        val current = connection.prepareStatement("SELECT stock FROM productos WHERE id=? FOR UPDATE").use { statement ->
            statement.setLong(1, productId)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else throw NotFoundException("El producto no existe.") }
        }
        connection.prepareStatement("UPDATE productos SET stock=?,last_counted_at=NOW(),updated_at=NOW() WHERE id=?").use {
            it.setInt(1, stock)
            it.setLong(2, productId)
            it.executeUpdate()
        }
        val delta = stock - current
        if (delta != 0) connection.prepareStatement(
            "INSERT INTO movimientos_inventario(product_id,movement_type,quantity_delta,notes,performed_by) VALUES (?,'COUNT',?,?,?)"
        ).use {
            it.setLong(1, productId)
            it.setInt(2, delta)
            it.setString(3, "Conteo físico: $current → $stock")
            it.setLong(4, adminId)
            it.executeUpdate()
        }
        audit(
            connection,
            adminId,
            "INVENTORY_PHYSICAL_COUNT",
            "PRODUCT",
            productId.toString(),
            "Existencia anterior=$current · existencia nueva=$stock · diferencia=$delta"
        )
        findProduct(connection, productId)
    }

    fun associatedBusinesses(activeOnly: Boolean): List<AssociatedBusinessDto> = database.dataSource.connection.use { connection ->
        val sql = buildString {
            append("SELECT * FROM negocios_asociados")
            if (activeOnly) append(" WHERE active=TRUE")
            append(" ORDER BY active DESC, commercial_name ASC, id DESC")
        }
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result -> buildList { while (result.next()) add(associatedBusinessDto(result)) } }
        }
    }

    fun catalogSalesDestinations(): CatalogSalesDestinationsDto {
        database.ensureCatalogSalesDestinationsSchema()
        return database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT product_business_id,combo_business_id FROM configuracion_ventas_catalogo WHERE id=1"
        ).use { statement ->
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    CatalogSalesDestinationsDto()
                } else {
                    val productId = result.getLong(1).let { if (result.wasNull()) null else it }
                    val comboId = result.getLong(2).let { if (result.wasNull()) null else it }
                    CatalogSalesDestinationsDto(productId, comboId)
                }
            }
        }
    }
    }

    fun saveCatalogSalesDestinations(actorId: Long, request: SaveCatalogSalesDestinationsRequest): CatalogSalesDestinationsDto {
        database.ensureCatalogSalesDestinationsSchema()
        return database.transaction { connection ->
            fun validateBusiness(id: Long?, label: String) {
                if (id == null) return
                val exists = connection.prepareStatement("SELECT active FROM negocios_asociados WHERE id=?").use { statement ->
                    statement.setLong(1, id)
                    statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
                }
                if (!exists) throw AppException("El negocio seleccionado para $label no existe o está inactivo.")
            }
            validateBusiness(request.productBusinessId, "ventas individuales")
            validateBusiness(request.comboBusinessId, "combos")
            connection.prepareStatement(
                """INSERT INTO configuracion_ventas_catalogo(id,product_business_id,combo_business_id,updated_by,updated_at)
                   VALUES (1,?,?,?,NOW())
                   ON CONFLICT(id) DO UPDATE SET product_business_id=EXCLUDED.product_business_id,combo_business_id=EXCLUDED.combo_business_id,updated_by=EXCLUDED.updated_by,updated_at=NOW()""".trimIndent()
            ).use { statement ->
                if (request.productBusinessId == null) statement.setNull(1, java.sql.Types.BIGINT) else statement.setLong(1, request.productBusinessId)
                if (request.comboBusinessId == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, request.comboBusinessId)
                statement.setLong(3, actorId)
                statement.executeUpdate()
            }
            audit(
                connection,
                actorId,
                "CATALOG_SALES_DESTINATIONS_UPDATED",
                "CATALOG_CONFIGURATION",
                "1",
                "Ventas individuales=${request.productBusinessId ?: "sin negocio"} · combos=${request.comboBusinessId ?: "sin negocio"}"
            )
            CatalogSalesDestinationsDto(request.productBusinessId, request.comboBusinessId)
        }
    }

    fun saveAssociatedBusiness(accountantId: Long, businessId: Long?, request: SaveAssociatedBusinessRequest): AssociatedBusinessDto =
        database.transaction { connection ->
            validateAssociatedBusinessRequest(request)
            val rif = normalizeBusinessRif(request.rif)
            val duplicateRif = connection.prepareStatement(
                """SELECT id FROM negocios_asociados
                   WHERE UPPER(REPLACE(REPLACE(rif,'-',''),' ',''))=UPPER(REPLACE(REPLACE(?,'-',''),' ',''))
                     AND (? IS NULL OR id<>?)
                   LIMIT 1""".trimIndent()
            ).use { statement ->
                statement.setString(1, rif)
                if (businessId == null) {
                    statement.setNull(2, java.sql.Types.BIGINT)
                    statement.setNull(3, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(2, businessId)
                    statement.setLong(3, businessId)
                }
                statement.executeQuery().use { it.next() }
            }
            if (duplicateRif) throw AppException("Ya existe un negocio asociado con ese RIF.")
            val resolvedId = if (businessId == null) {
                connection.prepareStatement(
                    """INSERT INTO negocios_asociados(
                           commercial_name,legal_name,rif,phone,email,address,payment_mode,
                           mobile_bank,mobile_phone,mobile_identity_number,mobile_holder_name,
                           bank_name,bank_account_type,bank_account_number,bank_identity_number,bank_holder_name,created_by
                       ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id""".trimIndent()
                ).use { statement ->
                    bindAssociatedBusiness(statement, request, rif, accountantId)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
            } else {
                val changed = connection.prepareStatement(
                    """UPDATE negocios_asociados SET
                           commercial_name=?,legal_name=?,rif=?,phone=?,email=?,address=?,payment_mode=?,
                           mobile_bank=?,mobile_phone=?,mobile_identity_number=?,mobile_holder_name=?,
                           bank_name=?,bank_account_type=?,bank_account_number=?,bank_identity_number=?,bank_holder_name=?,updated_at=NOW()
                       WHERE id=?""".trimIndent()
                ).use { statement ->
                    bindAssociatedBusiness(statement, request, rif, accountantId = null)
                    statement.setLong(17, businessId)
                    statement.executeUpdate()
                }
                if (changed == 0) throw NotFoundException("El negocio asociado no existe.")
                businessId
            }
            audit(
                connection,
                accountantId,
                if (businessId == null) "ACCOUNTANT_CREATED_BUSINESS" else "ACCOUNTANT_UPDATED_BUSINESS",
                "ASSOCIATED_BUSINESS",
                resolvedId.toString(),
                "${request.commercialName.trim()} · $rif"
            )
            findAssociatedBusiness(connection, resolvedId)
        }

    fun setAssociatedBusinessActive(accountantId: Long, businessId: Long, active: Boolean): AssociatedBusinessDto =
        database.transaction { connection ->
            val changed = connection.prepareStatement(
                "UPDATE negocios_asociados SET active=?,updated_at=NOW() WHERE id=?"
            ).use { statement ->
                statement.setBoolean(1, active)
                statement.setLong(2, businessId)
                statement.executeUpdate()
            }
            if (changed == 0) throw NotFoundException("El negocio asociado no existe.")
            audit(
                connection,
                accountantId,
                if (active) "ACCOUNTANT_ACTIVATED_BUSINESS" else "ACCOUNTANT_DEACTIVATED_BUSINESS",
                "ASSOCIATED_BUSINESS",
                businessId.toString(),
                if (active) "Negocio activado" else "Negocio desactivado"
            )
            findAssociatedBusiness(connection, businessId)
        }

    fun updateAssociatedBusinessLogo(accountantId: Long, businessId: Long, relativePath: String): AssociatedBusinessDto =
        database.transaction { connection ->
            val changed = connection.prepareStatement(
                "UPDATE negocios_asociados SET logo_path=?,updated_at=NOW() WHERE id=?"
            ).use { statement ->
                statement.setString(1, relativePath)
                statement.setLong(2, businessId)
                statement.executeUpdate()
            }
            if (changed == 0) throw NotFoundException("El negocio asociado no existe.")
            audit(connection, accountantId, "ACCOUNTANT_UPDATED_BUSINESS_LOGO", "ASSOCIATED_BUSINESS", businessId.toString(), relativePath)
            findAssociatedBusiness(connection, businessId)
        }

    fun jornadas(includeUnpublished: Boolean, role: String): List<FairDto> = database.dataSource.connection.use { connection ->
        val allowUnpublished = includeUnpublished && role == "ADMIN"
        val sql = if (allowUnpublished) {
            "SELECT id FROM jornadas WHERE active=TRUE ORDER BY created_at DESC"
        } else {
            "SELECT id FROM jornadas WHERE active=TRUE AND published=TRUE AND finalized=FALSE ORDER BY published_at DESC NULLS LAST, created_at DESC"
        }
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result -> buildList { while (result.next()) add(fairDto(connection, result.getLong(1))) } }
        }
    }

    fun banners(includeInactive: Boolean, role: String): List<BannerDto> = database.dataSource.connection.use { connection ->
        val adminView = includeInactive && Roles.isAdmin(role)
        val sql = if (adminView) {
            """SELECT b.id FROM banners_inicio b
               ORDER BY b.sort_order ASC,b.id DESC""".trimIndent()
        } else {
            """SELECT b.id FROM banners_inicio b
               WHERE b.active=TRUE
                 AND b.image_path IS NOT NULL AND BTRIM(b.image_path)<>''
                 AND (b.starts_at IS NULL OR b.starts_at<=NOW())
                 AND (b.ends_at IS NULL OR b.ends_at>=NOW())
               ORDER BY b.sort_order ASC,b.id DESC""".trimIndent()
        }
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result -> buildList { while (result.next()) add(bannerDto(connection, result.getLong(1))) } }
        }
    }

    fun saveBanner(adminId: Long, bannerId: Long?, request: SaveBannerRequest): BannerDto = database.transaction { connection ->
        val title = request.title.trim()
        if (title.isBlank()) throw AppException("Escribe un nombre para identificar el banner.")
        if (title.length > 180) throw AppException("El nombre del banner no puede superar 180 caracteres.")
        // El orden ya no depende del formulario Android. Se resuelve de forma transaccional
        // para evitar órdenes repetidos cuando dos administradores crean banners casi a la vez.
        connection.prepareStatement("LOCK TABLE banners_inicio IN SHARE ROW EXCLUSIVE MODE").use { it.execute() }
        val resolvedSortOrder = if (bannerId == null) {
            connection.prepareStatement("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM banners_inicio").use { statement ->
                statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
            }
        } else {
            connection.prepareStatement("SELECT sort_order FROM banners_inicio WHERE id=?").use { statement ->
                statement.setLong(1, bannerId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getInt(1) else throw NotFoundException("El banner no existe.")
                }
            }
        }
        val startsAt = parseBannerDate(request.startsAt, "inicio")
        val endsAt = parseBannerDate(request.endsAt, "fin")
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw AppException("La fecha de fin del banner no puede ser anterior a la fecha de inicio.")
        }
        request.fairId?.let { fairId ->
            val exists = connection.prepareStatement("SELECT 1 FROM jornadas WHERE id=? AND active=TRUE").use { statement ->
                statement.setLong(1, fairId)
                statement.executeQuery().use { it.next() }
            }
            if (!exists) throw AppException("La jornada asociada ya no existe.")
        }

        val resolvedId = if (bannerId == null) {
            connection.prepareStatement(
                """INSERT INTO banners_inicio(title,fair_id,active,sort_order,starts_at,ends_at,created_by)
                   VALUES (?,?,?,?,?,?,?) RETURNING id""".trimIndent()
            ).use { statement ->
                statement.setString(1, title)
                if (request.fairId == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, request.fairId)
                statement.setBoolean(3, request.active)
                statement.setInt(4, resolvedSortOrder)
                if (startsAt == null) statement.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else statement.setObject(5, startsAt)
                if (endsAt == null) statement.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else statement.setObject(6, endsAt)
                statement.setLong(7, adminId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
        } else {
            val changed = connection.prepareStatement(
                """UPDATE banners_inicio
                   SET title=?,fair_id=?,active=?,sort_order=?,starts_at=?,ends_at=?,updated_at=NOW()
                   WHERE id=?""".trimIndent()
            ).use { statement ->
                statement.setString(1, title)
                if (request.fairId == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, request.fairId)
                statement.setBoolean(3, request.active)
                statement.setInt(4, resolvedSortOrder)
                if (startsAt == null) statement.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else statement.setObject(5, startsAt)
                if (endsAt == null) statement.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else statement.setObject(6, endsAt)
                statement.setLong(7, bannerId)
                statement.executeUpdate()
            }
            if (changed == 0) throw NotFoundException("El banner no existe.")
            bannerId
        }
        audit(connection, adminId, if (bannerId == null) "ADMIN_CREATED_BANNER" else "ADMIN_UPDATED_BANNER", "BANNER", resolvedId.toString(), title)
        bannerDto(connection, resolvedId)
    }

    fun updateBannerImage(adminId: Long, bannerId: Long, relativePath: String): BannerDto = database.transaction { connection ->
        val changed = connection.prepareStatement(
            "UPDATE banners_inicio SET image_path=?,updated_at=NOW() WHERE id=?"
        ).use { statement ->
            statement.setString(1, relativePath)
            statement.setLong(2, bannerId)
            statement.executeUpdate()
        }
        if (changed == 0) throw NotFoundException("El banner no existe.")
        audit(connection, adminId, "ADMIN_UPDATED_BANNER_IMAGE", "BANNER", bannerId.toString(), relativePath)
        bannerDto(connection, bannerId)
    }

    fun deleteBanner(adminId: Long, bannerId: Long) = database.transaction { connection ->
        val title = connection.prepareStatement("SELECT title FROM banners_inicio WHERE id=? FOR UPDATE").use { statement ->
            statement.setLong(1, bannerId)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1).orEmpty() else throw NotFoundException("El banner no existe.") }
        }
        connection.prepareStatement("DELETE FROM banners_inicio WHERE id=?").use { statement ->
            statement.setLong(1, bannerId)
            statement.executeUpdate()
        }
        audit(connection, adminId, "ADMIN_DELETED_BANNER", "BANNER", bannerId.toString(), title)
    }


    fun rateBanner(userId: Long, bannerId: Long, request: StarRatingRequest): RatingSavedDto = database.transaction { connection ->
        val stars = request.stars.coerceIn(1, 5)
        val exists = connection.prepareStatement("SELECT 1 FROM banners_inicio WHERE id=? AND active=TRUE").use { statement ->
            statement.setLong(1, bannerId)
            statement.executeQuery().use { it.next() }
        }
        if (!exists) throw NotFoundException("El banner ya no está disponible.")
        connection.prepareStatement(
            """INSERT INTO calificaciones_banner(banner_id,user_id,stars)
               VALUES (?,?,?)
               ON CONFLICT(banner_id,user_id) DO UPDATE SET stars=EXCLUDED.stars,updated_at=NOW()""".trimIndent()
        ).use { statement ->
            statement.setLong(1, bannerId); statement.setLong(2, userId); statement.setInt(3, stars); statement.executeUpdate()
        }
        RatingSavedDto(stars, "Gracias. Tu opinión ayuda a planificar las próximas jornadas.")
    }

    fun ratePurchase(userId: Long, orderId: Long, request: StarRatingRequest): RatingSavedDto = database.transaction { connection ->
        val stars = request.stars.coerceIn(1, 5)
        val owned = connection.prepareStatement("SELECT 1 FROM pedidos WHERE id=? AND user_id=?").use { statement ->
            statement.setLong(1, orderId); statement.setLong(2, userId); statement.executeQuery().use { it.next() }
        }
        if (!owned) throw NotFoundException("La compra no existe o no pertenece a tu cuenta.")
        val tags = request.tags.map { it.trim().take(80) }.filter { it.isNotBlank() }.distinct().take(6)
        val comment = request.comment.trim().take(600)
        connection.prepareStatement(
            """INSERT INTO calificaciones_compra(order_id,user_id,stars,tags,comment)
               VALUES (?,?,?,?,?)
               ON CONFLICT(order_id,user_id) DO UPDATE SET stars=EXCLUDED.stars,tags=EXCLUDED.tags,comment=EXCLUDED.comment,updated_at=NOW()""".trimIndent()
        ).use { statement ->
            statement.setLong(1, orderId); statement.setLong(2, userId); statement.setInt(3, stars)
            statement.setArray(4, connection.createArrayOf("text", tags.toTypedArray())); statement.setString(5, comment)
            statement.executeUpdate()
        }
        RatingSavedDto(stars, "Gracias por calificar tu experiencia de compra.")
    }

    fun ratingsInsights(): RatingsInsightsDto = database.dataSource.connection.use { connection ->
        fun distribution(sql: String, id: Long? = null): List<Int> {
            val counts = IntArray(5)
            connection.prepareStatement(sql).use { statement ->
                if (id != null) statement.setLong(1, id)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val stars = result.getInt(1)
                        if (stars in 1..5) counts[stars - 1] = result.getInt(2)
                    }
                }
            }
            return counts.toList()
        }
        val bannerRows = mutableListOf<BannerRatingInsightDto>()
        connection.prepareStatement(
            """SELECT b.id,b.title,j.name,COALESCE(AVG(r.stars),0),COUNT(r.id),
                      COALESCE(100.0*SUM(CASE WHEN r.stars>=4 THEN 1 ELSE 0 END)/NULLIF(COUNT(r.id),0),0)
               FROM banners_inicio b
               LEFT JOIN jornadas j ON j.id=b.fair_id
               LEFT JOIN calificaciones_banner r ON r.banner_id=b.id
               GROUP BY b.id,b.title,j.name
               HAVING COUNT(r.id)>0
               ORDER BY COUNT(r.id) DESC,AVG(r.stars) DESC""".trimIndent()
        ).use { statement -> statement.executeQuery().use { result ->
            while (result.next()) {
                val id=result.getLong(1)
                bannerRows += BannerRatingInsightDto(id,result.getString(2),result.getString(3),result.getDouble(4),result.getInt(5),result.getDouble(6),distribution("SELECT stars,COUNT(*) FROM calificaciones_banner WHERE banner_id=? GROUP BY stars",id))
            }
        } }
        val purchaseRows = mutableListOf<PurchaseRatingInsightDto>()
        connection.prepareStatement(
            """SELECT p.fair_id,COALESCE(j.name,'Sin jornada'),AVG(r.stars),COUNT(r.id)
               FROM calificaciones_compra r JOIN pedidos p ON p.id=r.order_id
               LEFT JOIN jornadas j ON j.id=p.fair_id
               GROUP BY p.fair_id,j.name ORDER BY COUNT(r.id) DESC,AVG(r.stars) DESC""".trimIndent()
        ).use { statement -> statement.executeQuery().use { result ->
            while (result.next()) {
                val fairId=result.getLong(1)
                purchaseRows += PurchaseRatingInsightDto(fairId,result.getString(2),result.getDouble(3),result.getInt(4),distribution("SELECT r.stars,COUNT(*) FROM calificaciones_compra r JOIN pedidos p ON p.id=r.order_id WHERE p.fair_id=? GROUP BY r.stars",fairId))
            }
        } }
        val recentFeedback = mutableListOf<PurchaseFeedbackDto>()
        connection.prepareStatement(
            """SELECT r.order_id,COALESCE(j.name,'Sin jornada'),r.stars,r.tags,r.comment,r.updated_at
               FROM calificaciones_compra r
               JOIN pedidos p ON p.id=r.order_id
               LEFT JOIN jornadas j ON j.id=p.fair_id
               WHERE BTRIM(r.comment)<>'' OR cardinality(r.tags)>0
               ORDER BY r.updated_at DESC LIMIT 12""".trimIndent()
        ).use { statement -> statement.executeQuery().use { result ->
            while (result.next()) {
                recentFeedback += PurchaseFeedbackDto(
                    result.getLong(1), result.getString(2), result.getInt(3),
                    result.getArray(4)?.array?.let { it as? Array<*> }?.mapNotNull { it?.toString() }.orEmpty(),
                    result.getString(5).orEmpty(), result.getObject(6, OffsetDateTime::class.java).toString()
                )
            }
        } }
        val totals = connection.prepareStatement(
            """SELECT (SELECT COALESCE(AVG(stars),0) FROM calificaciones_banner),
                      (SELECT COUNT(*) FROM calificaciones_banner),
                      (SELECT COALESCE(AVG(stars),0) FROM calificaciones_compra),
                      (SELECT COUNT(*) FROM calificaciones_compra)""".trimIndent()
        ).use { statement -> statement.executeQuery().use { result -> result.next(); listOf(result.getDouble(1),result.getDouble(2),result.getDouble(3),result.getDouble(4)) } }
        RatingsInsightsDto(totals[0],totals[1].toInt(),totals[2],totals[3].toInt(),bannerRows,purchaseRows,recentFeedback)
    }

    fun saveAppExperienceSurvey(userId: Long, request: AppExperienceSurveyRequest): MessageResponse = database.transaction { connection ->
        val registration = request.registrationStars.coerceIn(1, 5)
        val navigation = request.navigationStars.coerceIn(1, 5)
        connection.prepareStatement(
            """INSERT INTO encuestas_experiencia_app(user_id,registration_stars,navigation_stars,payments_clear)
               VALUES (?,?,?,?)
               ON CONFLICT(user_id) DO UPDATE SET registration_stars=EXCLUDED.registration_stars,navigation_stars=EXCLUDED.navigation_stars,payments_clear=EXCLUDED.payments_clear,updated_at=NOW()""".trimIndent()
        ).use { statement ->
            statement.setLong(1, userId); statement.setInt(2, registration); statement.setInt(3, navigation); statement.setBoolean(4, request.paymentsClear); statement.executeUpdate()
        }
        MessageResponse("Gracias. Tu encuesta fue registrada.")
    }

    fun appExperienceInsights(): AppExperienceSurveyInsightDto = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT COUNT(*),COALESCE(AVG(registration_stars),0),COALESCE(AVG(navigation_stars),0),
                      COALESCE(100.0*SUM(CASE WHEN payments_clear THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),0)
               FROM encuestas_experiencia_app""".trimIndent()
        ).use { statement -> statement.executeQuery().use { result ->
            result.next(); AppExperienceSurveyInsightDto(result.getInt(1), result.getDouble(2), result.getDouble(3), result.getDouble(4))
        } }
    }

    fun promotions(includeInactive: Boolean, role: String): List<PromotionDto> = database.dataSource.connection.use { connection ->
        val privileged = role in setOf("ADMIN", "ACCOUNTANT")
        val sql = if (includeInactive && privileged) {
            "SELECT id FROM promociones_descuentos ORDER BY active DESC,created_at DESC"
        } else {
            """SELECT p.id FROM promociones_descuentos p
               WHERE p.active=TRUE AND (p.starts_at IS NULL OR p.starts_at<=NOW()) AND (p.ends_at IS NULL OR p.ends_at>=NOW())
                 AND (p.max_uses IS NULL OR (SELECT COUNT(*) FROM pedidos o WHERE o.promotion_id=p.id) < p.max_uses)
               ORDER BY p.created_at DESC""".trimIndent()
        }
        connection.prepareStatement(sql).use { statement -> statement.executeQuery().use { result -> buildList { while (result.next()) add(promotionDto(connection, result.getLong(1))) } } }
    }

    fun savePromotion(adminId: Long, promotionId: Long?, request: SavePromotionRequest): PromotionDto = database.transaction { connection ->
        val name = request.name.trim().take(180)
        if (name.isBlank()) throw AppException("Escribe un nombre para la promoción.")
        val targetType = request.targetType.trim().uppercase(Locale.ROOT)
        if (targetType !in setOf("PRODUCT","COMBO","FAIR")) throw AppException("Selecciona un destino válido para el descuento.")
        val discountType = request.discountType.trim().uppercase(Locale.ROOT)
        if (discountType !in setOf("PERCENT","FIXED_USD")) throw AppException("Selecciona porcentaje o monto fijo en US$.")
        if (!request.discountValue.isFinite()) throw AppException("El valor del descuento no es válido.")
        val value = BigDecimal.valueOf(request.discountValue)
        if (value.signum() <= 0 || (discountType == "PERCENT" && value > BigDecimal("100"))) throw AppException("El valor del descuento no es válido.")
        val starts = parsePromotionDate(request.startsAt, "inicio")
        val ends = parsePromotionDate(request.endsAt, "fin")
        if (starts != null && ends != null && ends.isBefore(starts)) throw AppException("La fecha de fin no puede ser anterior al inicio.")
        if (request.maxUses != null && request.maxUses <= 0) throw AppException("El máximo de usos debe ser mayor que cero.")
        ensurePromotionTargetExists(connection, targetType, request.targetId)
        val id = if (promotionId == null) {
            connection.prepareStatement(
                """INSERT INTO promociones_descuentos(name,target_type,target_id,discount_type,discount_value,starts_at,ends_at,max_uses,active,created_by)
                   VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id""".trimIndent()
            ).use { st ->
                st.setString(1,name); st.setString(2,targetType); st.setLong(3,request.targetId); st.setString(4,discountType); st.setBigDecimal(5,value)
                if (starts==null) st.setNull(6,java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else st.setObject(6,starts)
                if (ends==null) st.setNull(7,java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else st.setObject(7,ends)
                if (request.maxUses==null) st.setNull(8,java.sql.Types.INTEGER) else st.setInt(8,request.maxUses)
                st.setBoolean(9,request.active); st.setLong(10,adminId); st.executeQuery().use { r -> r.next(); r.getLong(1) }
            }
        } else {
            val changed=connection.prepareStatement(
                """UPDATE promociones_descuentos SET name=?,target_type=?,target_id=?,discount_type=?,discount_value=?,starts_at=?,ends_at=?,max_uses=?,active=?,updated_at=NOW() WHERE id=?""".trimIndent()
            ).use { st ->
                st.setString(1,name); st.setString(2,targetType); st.setLong(3,request.targetId); st.setString(4,discountType); st.setBigDecimal(5,value)
                if (starts==null) st.setNull(6,java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else st.setObject(6,starts)
                if (ends==null) st.setNull(7,java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else st.setObject(7,ends)
                if (request.maxUses==null) st.setNull(8,java.sql.Types.INTEGER) else st.setInt(8,request.maxUses)
                st.setBoolean(9,request.active); st.setLong(10,promotionId); st.executeUpdate()
            }
            if (changed==0) throw NotFoundException("La promoción no existe.")
            promotionId
        }
        audit(connection, adminId, if (promotionId==null) "ADMIN_CREATED_PROMOTION" else "ADMIN_UPDATED_PROMOTION", "PROMOTION", id.toString(), name)
        promotionDto(connection,id)
    }

    fun setPromotionActive(adminId: Long, promotionId: Long, active: Boolean): PromotionDto = database.transaction { connection ->
        val changed=connection.prepareStatement("UPDATE promociones_descuentos SET active=?,updated_at=NOW() WHERE id=?").use { st -> st.setBoolean(1,active); st.setLong(2,promotionId); st.executeUpdate() }
        if (changed==0) throw NotFoundException("La promoción no existe.")
        audit(connection,adminId,if(active) "ADMIN_ACTIVATED_PROMOTION" else "ADMIN_DEACTIVATED_PROMOTION","PROMOTION",promotionId.toString(),null)
        promotionDto(connection,promotionId)
    }

    fun promotionsInsights(): PromotionsInsightsDto = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT (SELECT COUNT(*) FROM promociones_descuentos p WHERE p.active=TRUE AND (p.starts_at IS NULL OR p.starts_at<=NOW()) AND (p.ends_at IS NULL OR p.ends_at>=NOW())),
                      COUNT(*) FILTER (WHERE o.promotion_id IS NOT NULL),
                      COALESCE(SUM(COALESCE(o.original_subtotal,o.subtotal)) FILTER (WHERE o.promotion_id IS NOT NULL),0),
                      COALESCE(SUM(o.discount_amount),0),
                      COALESCE(SUM(o.total) FILTER (WHERE o.promotion_id IS NOT NULL),0)
               FROM pedidos o""".trimIndent()
        ).use { st -> st.executeQuery().use { r -> r.next(); PromotionsInsightsDto(r.getInt(1),r.getInt(2),r.getDouble(3),r.getDouble(4),r.getDouble(5)) } }
    }

    fun saveFair(adminId: Long, fairId: Long?, request: SaveFairRequest): FairDto {
        var wasPublished = false
        val saved = database.transaction { connection ->
            val existingFair = fairId?.let { id ->
                connection.prepareStatement("SELECT published,business_id FROM jornadas WHERE id=? AND active=TRUE FOR UPDATE").use { statement ->
                    statement.setLong(1, id)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw NotFoundException("La jornada no existe o ya fue eliminada.")
                        val published = result.getBoolean(1)
                        val businessId = result.getLong(2).let { if (result.wasNull()) null else it }
                        published to businessId
                    }
                }
            }
            wasPublished = existingFair?.first ?: false
            val allowExistingInactiveBusiness = request.businessId != null &&
                existingFair?.second == request.businessId &&
                (wasPublished || !request.published)
            val effectiveRequest = resolveFairBusiness(
                connection,
                request,
                allowInactive = allowExistingInactiveBusiness
            )
            validateFairRequest(effectiveRequest)

            val resolvedId = if (fairId == null) {
                connection.prepareStatement(
                    "INSERT INTO jornadas(name,place,schedule_text,description,published,payment_mode,business_id,created_by,published_at) VALUES (?,?,?,?,?,?,?,?,CASE WHEN ? THEN NOW() ELSE NULL END) RETURNING id"
                ).use { statement ->
                    bindFair(statement, effectiveRequest, adminId)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
            } else {
                val changed = connection.prepareStatement(
                    "UPDATE jornadas SET name=?,place=?,schedule_text=?,description=?,published=?,payment_mode=?,business_id=?,finalized=CASE WHEN ? THEN FALSE ELSE finalized END,updated_at=NOW(),published_at=CASE WHEN ? THEN COALESCE(published_at,NOW()) ELSE NULL END WHERE id=? AND active=TRUE"
                ).use { statement ->
                    statement.setString(1, effectiveRequest.name.trim())
                    statement.setString(2, effectiveRequest.place.trim())
                    statement.setString(3, effectiveRequest.schedule.trim())
                    statement.setString(4, effectiveRequest.description.trim())
                    statement.setBoolean(5, effectiveRequest.published)
                    statement.setString(6, effectiveRequest.paymentMode)
                    if (effectiveRequest.businessId == null) statement.setNull(7, java.sql.Types.BIGINT) else statement.setLong(7, effectiveRequest.businessId)
                    statement.setBoolean(8, effectiveRequest.published)
                    statement.setBoolean(9, effectiveRequest.published)
                    statement.setLong(10, fairId)
                    statement.executeUpdate()
                }
                if (changed == 0) throw NotFoundException("La jornada no existe o ya fue eliminada.")
                fairId
            }

            upsertPaymentDetails(connection, resolvedId, effectiveRequest)
            val inventoryPrices = effectiveRequest.productOffers.distinctBy { it.productId }.associate { offer ->
                val price = connection.prepareStatement("SELECT base_price FROM productos WHERE id=? AND active=TRUE").use { statement ->
                    statement.setLong(1, offer.productId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw AppException("Uno de los productos seleccionados ya no está disponible.")
                        result.getBigDecimal(1).setScale(MoneyMath.VES_SCALE, RoundingMode.HALF_EVEN)
                    }
                }
                offer.productId to price
            }
            val oldImages = connection.prepareStatement("SELECT product_id,image_path FROM productos_jornada WHERE fair_id=?").use { statement ->
                statement.setLong(1, resolvedId)
                statement.executeQuery().use { result ->
                    buildMap<Long, String?> { while (result.next()) put(result.getLong(1), result.getString(2)) }
                }
            }
            connection.prepareStatement("DELETE FROM productos_jornada WHERE fair_id=?").use {
                it.setLong(1, resolvedId)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO productos_jornada(fair_id,product_id,fair_price,image_path,quantity) VALUES (?,?,?,?,?)").use { statement ->
                effectiveRequest.productOffers.distinctBy { it.productId }.forEach { offer ->
                    statement.setLong(1, resolvedId)
                    statement.setLong(2, offer.productId)
                    statement.setBigDecimal(3, inventoryPrices.getValue(offer.productId))
                    statement.setString(4, oldImages[offer.productId])
                    statement.setInt(5, offer.quantity.coerceAtLeast(1))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            audit(
                connection,
                adminId,
                if (fairId == null) "ADMIN_CREATED_FAIR" else "ADMIN_UPDATED_FAIR",
                "FAIR",
                resolvedId.toString(),
                effectiveRequest.name
            )
            fairDto(connection, resolvedId)
        }
        if (fairId == null) {
            notifyAdmins(
                title = "Nueva campaña creada",
                body = "Se creó la jornada ${saved.name} en ${saved.place}.",
                type = "FAIR_CREATED",
                data = mapOf("fairId" to saved.id.toString(), "published" to saved.published.toString())
            )
        }
        if (saved.published && !wasPublished) {
            notifyBeneficiaries(
                "Nueva jornada",
                "${saved.name} ya está disponible en ${saved.place}.",
                "NEW_FAIR",
                mapOf("fairId" to saved.id.toString())
            )
        }
        return saved
    }

    fun setFairPublished(adminId: Long, fairId: Long, published: Boolean): FairDto {
        var wasPublished = false
        val updated = database.transaction { connection ->
            val currentState = connection.prepareStatement(
                "SELECT published FROM jornadas WHERE id=? AND active=TRUE FOR UPDATE"
            ).use { statement ->
                statement.setLong(1, fairId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("La jornada no existe o ya fue eliminada.")
                    result.getBoolean(1)
                }
            }
            wasPublished = currentState

            val fair = fairDto(connection, fairId)
            if (published) {
                validateFairRequest(
                    SaveFairRequest(
                        name = fair.name,
                        place = fair.place,
                        schedule = fair.schedule,
                        description = fair.description,
                        published = true,
                        paymentMode = fair.paymentMode,
                        businessId = fair.business?.id,
                        mobilePayment = fair.mobilePayment,
                        bankTransfer = fair.bankTransfer,
                        productOffers = fair.productOffers
                    )
                )
                val businessId = fair.business?.id
                    ?: throw AppException("Selecciona un negocio asociado antes de publicar la jornada.")
                val activeBusiness = connection.prepareStatement(
                    "SELECT active FROM negocios_asociados WHERE id=? FOR SHARE"
                ).use { statement ->
                    statement.setLong(1, businessId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw AppException("El negocio asociado de la jornada ya no existe.")
                        result.getBoolean(1)
                    }
                }
                if (!activeBusiness) throw AppException("Activa el negocio asociado antes de publicar la jornada.")
            }

            connection.prepareStatement(
                "UPDATE jornadas SET published=?,finalized=CASE WHEN ? THEN FALSE ELSE finalized END," +
                    "published_at=CASE WHEN ? THEN COALESCE(published_at,NOW()) ELSE NULL END," +
                    "updated_at=NOW() WHERE id=? AND active=TRUE"
            ).use { statement ->
                statement.setBoolean(1, published)
                statement.setBoolean(2, published)
                statement.setBoolean(3, published)
                statement.setLong(4, fairId)
                if (statement.executeUpdate() == 0) throw NotFoundException("La jornada no existe o ya fue eliminada.")
            }
            audit(
                connection,
                adminId,
                if (published) "ADMIN_PUBLISHED_FAIR" else "ADMIN_UNPUBLISHED_FAIR",
                "FAIR",
                fairId.toString(),
                fair.name
            )
            fairDto(connection, fairId)
        }
        if (published && !wasPublished) {
            notifyBeneficiaries(
                "Nueva jornada",
                "${updated.name} ya está disponible en ${updated.place}.",
                "NEW_FAIR",
                mapOf("fairId" to updated.id.toString())
            )
        }
        return updated
    }

    fun finalizeFair(adminId: Long, fairId: Long): FairDto = database.transaction { connection ->
        val changed = connection.prepareStatement(
            "UPDATE jornadas SET finalized=TRUE,published=FALSE,published_at=NULL,updated_at=NOW() WHERE id=? AND active=TRUE"
        ).use { statement ->
            statement.setLong(1, fairId)
            statement.executeUpdate()
        }
        if (changed == 0) throw NotFoundException("La jornada no existe o ya fue eliminada.")
        audit(connection, adminId, "ADMIN_FINALIZED_FAIR", "FAIR", fairId.toString(), "Jornada marcada como finalizada")
        fairDto(connection, fairId)
    }

    fun deleteFair(adminId: Long, fairId: Long) = database.transaction { connection ->
        // Las jornadas pueden estar referenciadas por pedidos históricos. No se eliminan físicamente:
        // se desactivan y despublican para preservar facturas, pagos y trazabilidad sin violar claves foráneas.
        val fairName = connection.prepareStatement("SELECT name FROM jornadas WHERE id=? AND active=TRUE FOR UPDATE").use { statement ->
            statement.setLong(1, fairId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString(1) else throw NotFoundException("La jornada no existe o ya fue eliminada.")
            }
        }
        val rows = connection.prepareStatement(
            "UPDATE jornadas SET active=FALSE,published=FALSE,published_at=NULL,updated_at=NOW() WHERE id=? AND active=TRUE"
        ).use { statement ->
            statement.setLong(1, fairId)
            statement.executeUpdate()
        }
        if (rows == 0) throw NotFoundException("La jornada no existe o ya fue eliminada.")
        connection.prepareStatement("UPDATE banners_inicio SET fair_id=NULL,updated_at=NOW() WHERE fair_id=?").use { statement ->
            statement.setLong(1, fairId)
            statement.executeUpdate()
        }
        audit(connection, adminId, "ADMIN_DELETED_FAIR", "FAIR", fairId.toString(), "Jornada desactivada: $fairName")
    }

    fun updateFairProductImage(adminId: Long, fairId: Long, productId: Long, relativePath: String): FairDto = database.transaction { connection ->
        val fairExists = connection.prepareStatement("SELECT 1 FROM jornadas WHERE id=? AND active=TRUE FOR SHARE").use { statement ->
            statement.setLong(1, fairId)
            statement.executeQuery().use { it.next() }
        }
        if (!fairExists) throw NotFoundException("La jornada no existe o ya fue eliminada.")
        val updated = connection.prepareStatement("UPDATE productos_jornada SET image_path=? WHERE fair_id=? AND product_id=?").use {
            it.setString(1, relativePath); it.setLong(2, fairId); it.setLong(3, productId); it.executeUpdate()
        }
        if (updated == 0) throw NotFoundException("El producto no pertenece a la jornada.")
        audit(connection, adminId, "ADMIN_UPDATED_FAIR_PRODUCT_IMAGE", "FAIR", fairId.toString(), "Producto $productId")
        fairDto(connection, fairId)
    }

    fun updateFairCover(adminId: Long, fairId: Long, relativePath: String): FairDto = database.transaction { connection ->
        val updated = connection.prepareStatement("UPDATE jornadas SET cover_path=?,updated_at=NOW() WHERE id=? AND active=TRUE").use { statement ->
            statement.setString(1, relativePath)
            statement.setLong(2, fairId)
            statement.executeUpdate()
        }
        if (updated == 0) throw NotFoundException("La jornada no existe o ya fue eliminada.")
        audit(connection, adminId, "ADMIN_UPDATED_FAIR_COVER", "FAIR", fairId.toString(), relativePath)
        fairDto(connection, fairId)
    }

    fun updateCombo(adminId: Long, comboId: Long, request: CreateComboRequest): ComboDto {
        val externalRate = runCatching { bcvRateService.currentUsdRate().rate }
            .mapCatching { MoneyMath.positive(MoneyMath.rate(it), "Tasa BCV") }
            .getOrNull()
        return database.transaction { connection ->
            val normalizedLines = request.lines
                .filter { it.quantity > 0 }
                .groupBy { it.productId to it.extra }
                .map { (key, rows) -> ComboLineDto(key.first, rows.sumOf { it.quantity }, key.second) }
            val cleanName = request.name.trim()
            val cleanDescription = request.description.trim()
            if (cleanName.isBlank() || normalizedLines.isEmpty()) throw AppException("El combo necesita nombre y productos.")

            val current = connection.prepareStatement(
                "SELECT cover_path,combo_price_usd FROM combos WHERE id=? AND active=TRUE FOR UPDATE"
            ).use { statement ->
                statement.setLong(1, comboId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("El combo no existe o ya fue eliminado.")
                    result.getString(1) to (result.getBigDecimal(2) ?: java.math.BigDecimal.ZERO)
                }
            }

            val requestedProductIds = normalizedLines.map { it.productId }.distinct()
            val validProductCount = connection.prepareStatement(
                "SELECT COUNT(*) FROM productos WHERE active=TRUE AND id = ANY(?)"
            ).use { statement ->
                statement.setArray(1, connection.createArrayOf("BIGINT", requestedProductIds.toTypedArray()))
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
            if (validProductCount != requestedProductIds.size) throw AppException("El combo contiene productos inexistentes o inactivos.")

            val individualUsd = comboIndividualTotalUsd(connection, normalizedLines)
            val rate = externalRate ?: latestCatalogBcvRate(connection)
                ?: throw AppException("No hay una tasa BCV válida para calcular el precio del combo en bolívares.")
            val storedOrDerived = if (current.second.signum() > 0) current.second else individualUsd
            val comboPriceUsd = request.priceUsd?.let {
                runCatching { MoneyMath.positive(MoneyMath.usd(it, "Precio del combo USD"), "Precio del combo USD") }
                    .getOrElse { error -> throw AppException(error.message ?: "El precio del combo no es válido.") }
            } ?: storedOrDerived
            if (comboPriceUsd.signum() <= 0) throw AppException("Ingresa el precio del combo en dólares.")
            val comboPriceBs = MoneyMath.usdToVes(comboPriceUsd, rate)

            connection.prepareStatement(
                """UPDATE combos
                   SET name=?,description=?,combo_price_usd=?,combo_price_bs=?,bcv_rate=?,price_updated_at=NOW(),updated_at=NOW()
                   WHERE id=? AND active=TRUE"""
            ).use { statement ->
                statement.setString(1, cleanName)
                statement.setString(2, cleanDescription)
                statement.setBigDecimal(3, comboPriceUsd)
                statement.setBigDecimal(4, comboPriceBs)
                statement.setBigDecimal(5, rate)
                statement.setLong(6, comboId)
                if (statement.executeUpdate() == 0) throw NotFoundException("El combo no existe o ya fue eliminado.")
            }

            // Se conserva el mismo combo_id para no romper compras, facturas ni referencias históricas.
            connection.prepareStatement("DELETE FROM productos_combo WHERE combo_id=?").use { statement ->
                statement.setLong(1, comboId)
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO productos_combo(combo_id,product_id,quantity,extra) VALUES (?,?,?,?)").use { statement ->
                normalizedLines.forEach { line ->
                    statement.setLong(1, comboId)
                    statement.setLong(2, line.productId)
                    statement.setInt(3, line.quantity)
                    statement.setBoolean(4, line.extra)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            audit(
                connection, adminId, "ADMIN_UPDATED_COMBO", "COMBO", comboId.toString(),
                "Combo actualizado: $cleanName · precio combo USD ${comboPriceUsd.toPlainString()} · valor individual USD ${individualUsd.toPlainString()}"
            )
            comboDto(connection, comboId)
        }
    }

    fun deleteCombo(adminId: Long, comboId: Long) = database.transaction { connection ->
        val comboName = connection.prepareStatement(
            "SELECT name FROM combos WHERE id=? AND active=TRUE FOR UPDATE"
        ).use { statement ->
            statement.setLong(1, comboId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString(1) else throw NotFoundException("El combo no existe o ya fue eliminado.")
            }
        }
        val rows = connection.prepareStatement(
            "UPDATE combos SET active=FALSE WHERE id=? AND active=TRUE"
        ).use { statement ->
            statement.setLong(1, comboId)
            statement.executeUpdate()
        }
        if (rows == 0) throw NotFoundException("El combo no existe o ya fue eliminado.")
        // Eliminación lógica: preserva productos_combo y combos_pedido para no romper compras/facturas históricas.
        audit(connection, adminId, "ADMIN_DELETED_COMBO", "COMBO", comboId.toString(), "Combo desactivado: $comboName")
    }

    fun updateComboCover(adminId: Long, comboId: Long, relativePath: String): ComboDto = database.transaction { connection ->
        val updated = connection.prepareStatement("UPDATE combos SET cover_path=?,updated_at=NOW() WHERE id=?").use { statement ->
            statement.setString(1, relativePath)
            statement.setLong(2, comboId)
            statement.executeUpdate()
        }
        if (updated == 0) throw NotFoundException("El combo no existe.")
        audit(connection, adminId, "ADMIN_UPDATED_COMBO_COVER", "COMBO", comboId.toString(), relativePath)
        comboDto(connection, comboId)
    }

    fun comunidades(): List<CommunityDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id,name,state,municipality,parish,families FROM comunidades WHERE active=TRUE ORDER BY name").use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(
                    CommunityDto(
                        id = result.getLong(1),
                        name = result.getString(2),
                        state = result.getString(3),
                        municipality = result.getString(4),
                        parish = result.getString(5),
                        families = result.getInt(6)
                    )
                )
            } }
        }
    }

    fun communityCatalog(state: String?, municipality: String?, parish: String?): List<CommunityCatalogDto> =
        database.dataSource.connection.use { connection ->
            val clauses = mutableListOf<String>()
            val values = mutableListOf<String>()
            state?.trim()?.takeIf { it.isNotEmpty() }?.let { clauses += "LOWER(state)=LOWER(?)"; values += it }
            municipality?.trim()?.takeIf { it.isNotEmpty() }?.let { clauses += "LOWER(municipality)=LOWER(?)"; values += it }
            parish?.trim()?.takeIf { it.isNotEmpty() }?.let { clauses += "LOWER(parish)=LOWER(?)"; values += it }
            val where = if (clauses.isEmpty()) "" else " AND ${clauses.joinToString(" AND ")}"
            val sql = """
                SELECT DISTINCT name,state,municipality,parish FROM (
                    SELECT TRIM(name) AS name, TRIM(state) AS state, TRIM(municipality) AS municipality, TRIM(parish) AS parish
                    FROM comunidades
                    WHERE active=TRUE AND state IS NOT NULL $where
                    UNION
                    SELECT TRIM(community) AS name, TRIM(state) AS state, TRIM(municipality) AS municipality, TRIM(parish) AS parish
                    FROM perfiles_usuario
                    WHERE community IS NOT NULL AND TRIM(community)<>'' AND state IS NOT NULL AND municipality IS NOT NULL AND parish IS NOT NULL $where
                ) catalog
                ORDER BY state,municipality,parish,name
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                // Los filtros aparecen en ambos SELECT del UNION.
                (values + values).forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(CommunityCatalogDto(result.getString(1), result.getString(2), result.getString(3), result.getString(4)))
                } }
            }
        }

    fun createCommunity(adminId: Long, request: CreateCommunityRequest): CommunityDto = database.transaction { connection ->
        val state = request.state?.trim().orEmpty()
        val name = request.name.trim()
        val municipality = request.municipality.trim()
        val parish = request.parish.trim()
        if (state.isBlank() || name.isBlank() || municipality.isBlank() || parish.isBlank() || request.families <= 0) {
            throw AppException("Selecciona estado, municipio, parroquia y comunidad; luego indica el número de familias.")
        }
        val existingId = connection.prepareStatement(
            """
            SELECT id FROM comunidades
            WHERE LOWER(name)=LOWER(?) AND LOWER(COALESCE(state,''))=LOWER(?)
              AND LOWER(municipality)=LOWER(?) AND LOWER(parish)=LOWER(?)
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, name); statement.setString(2, state); statement.setString(3, municipality); statement.setString(4, parish)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }
        val id = if (existingId != null) {
            connection.prepareStatement("UPDATE comunidades SET families=?,active=TRUE,updated_at=NOW() WHERE id=?").use {
                it.setInt(1, request.families); it.setLong(2, existingId); it.executeUpdate()
            }
            existingId
        } else {
            connection.prepareStatement("INSERT INTO comunidades(name,state,municipality,parish,families,created_by) VALUES (?,?,?,?,?,?) RETURNING id").use { statement ->
                statement.setString(1, name); statement.setString(2, state); statement.setString(3, municipality); statement.setString(4, parish); statement.setInt(5, request.families); statement.setLong(6, adminId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
        }
        audit(connection, adminId, "ADMIN_SAVED_COMMUNITY", "COMMUNITY", id.toString(), name)
        CommunityDto(id, name, state, municipality, parish, request.families)
    }

    fun combos(): List<ComboDto> {
        val rate = runCatching { bcvRateService.currentUsdRate().rate }
            .mapCatching { MoneyMath.positive(MoneyMath.rate(it), "Tasa BCV") }
            .getOrNull()
        return database.transaction { connection ->
            if (rate != null) {
                refreshProductPricesForCurrentBcv(connection, rate)
                refreshComboPricesForCurrentBcv(connection, rate)
            }
            connection.prepareStatement("SELECT id FROM combos WHERE active=TRUE ORDER BY created_at DESC").use { statement ->
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(comboDto(connection, result.getLong(1)))
                } }
            }
        }
    }

    fun createCombo(adminId: Long, request: CreateComboRequest): ComboDto {
        val externalRate = runCatching { bcvRateService.currentUsdRate().rate }
            .mapCatching { MoneyMath.positive(MoneyMath.rate(it), "Tasa BCV") }
            .getOrNull()
        val combo = database.transaction { connection ->
            val normalizedLines = request.lines
                .filter { it.quantity > 0 }
                .groupBy { it.productId to it.extra }
                .map { (key, rows) -> ComboLineDto(key.first, rows.sumOf { it.quantity }, key.second) }
            if (request.name.isBlank() || normalizedLines.isEmpty()) throw AppException("El combo necesita nombre y productos.")
            val activeProductCount = connection.prepareStatement("SELECT COUNT(*) FROM productos WHERE active=TRUE").use { statement ->
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
            if (activeProductCount == 0) throw AppException("Debes registrar al menos un producto antes de crear un combo.")
            val requestedProductIds = normalizedLines.map { it.productId }.distinct()
            val validProductCount = connection.prepareStatement(
                "SELECT COUNT(*) FROM productos WHERE active=TRUE AND id = ANY(?)"
            ).use { statement ->
                statement.setArray(1, connection.createArrayOf("BIGINT", requestedProductIds.toTypedArray()))
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
            if (validProductCount != requestedProductIds.size) throw AppException("El combo contiene productos inexistentes o inactivos.")

            val individualUsd = comboIndividualTotalUsd(connection, normalizedLines)
            val rate = externalRate ?: latestCatalogBcvRate(connection)
                ?: throw AppException("No hay una tasa BCV válida para calcular el precio del combo en bolívares.")
            val comboPriceUsd = request.priceUsd?.let {
                runCatching { MoneyMath.positive(MoneyMath.usd(it, "Precio del combo USD"), "Precio del combo USD") }
                    .getOrElse { error -> throw AppException(error.message ?: "El precio del combo no es válido.") }
            } ?: individualUsd
            if (comboPriceUsd.signum() <= 0) throw AppException("Ingresa el precio del combo en dólares.")
            val comboPriceBs = MoneyMath.usdToVes(comboPriceUsd, rate)

            val id = connection.prepareStatement(
                """INSERT INTO combos(name,description,combo_price_usd,combo_price_bs,bcv_rate,price_updated_at,active,created_by)
                   VALUES (?,?,?,?,?,NOW(),TRUE,?) RETURNING id"""
            ).use { statement ->
                statement.setString(1, request.name.trim())
                statement.setString(2, request.description.trim())
                statement.setBigDecimal(3, comboPriceUsd)
                statement.setBigDecimal(4, comboPriceBs)
                statement.setBigDecimal(5, rate)
                statement.setLong(6, adminId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
            connection.prepareStatement("INSERT INTO productos_combo(combo_id,product_id,quantity,extra) VALUES (?,?,?,?)").use { statement ->
                normalizedLines.forEach { line ->
                    statement.setLong(1, id); statement.setLong(2, line.productId); statement.setInt(3, line.quantity); statement.setBoolean(4, line.extra); statement.addBatch()
                }
                statement.executeBatch()
            }
            audit(
                connection, adminId, "ADMIN_CREATED_COMBO", "COMBO", id.toString(),
                "${request.name.trim()} · precio combo USD ${comboPriceUsd.toPlainString()} · valor individual USD ${individualUsd.toPlainString()}"
            )
            comboDto(connection, id)
        }
        notifyBeneficiaries(
            "Nuevo combo",
            "${combo.name} ya está disponible en Kredi+.",
            "NEW_COMBO",
            mapOf("comboId" to combo.id.toString())
        )
        return combo
    }

    fun communityRequests(): List<CommunityRequestDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id,community_id,status,created_at FROM solicitudes_comunidad ORDER BY created_at DESC").use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val id = result.getLong(1)
                    add(CommunityRequestDto(id, result.getLong(2), requestQuantities(connection, id), result.getString(3), result.getObject(4, OffsetDateTime::class.java).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))))
                }
            } }
        }
    }

    fun createCommunityRequest(userId: Long, payload: CreateCommunityRequestPayload): CommunityRequestDto {
        val response = database.transaction { connection ->
        if (payload.quantities.values.none { it > 0 }) throw AppException("Selecciona al menos un combo.")
        val id = connection.prepareStatement("INSERT INTO solicitudes_comunidad(community_id,status,requested_by) VALUES (?,'Solicitada',?) RETURNING id,created_at").use { statement ->
            statement.setLong(1, payload.communityId); statement.setLong(2, userId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
        connection.prepareStatement("INSERT INTO items_solicitud_comunidad(request_id,combo_id,quantity) VALUES (?,?,?)").use { statement ->
            payload.quantities.filterValues { it > 0 }.forEach { (comboId, quantity) ->
                statement.setLong(1, id); statement.setLong(2, comboId); statement.setInt(3, quantity); statement.addBatch()
            }
            statement.executeBatch()
        }
        audit(connection, userId, "COMMUNITY_REQUEST_CREATED", "COMMUNITY_REQUEST", id.toString(), null)
        CommunityRequestDto(
            id = id,
            communityId = payload.communityId,
            comboQuantities = payload.quantities.filterValues { it > 0 },
            status = "Solicitada",
            createdLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        )
    
        }
        val name = runCatching { me(userId).fullName }.getOrDefault("Un usuario")
        notifyAdmins("Nueva solicitud", "$name envió una solicitud comunitaria.", "COMMUNITY_REQUEST", mapOf("requestId" to response.id.toString()))
        return response
    }

    fun updateCommunityRequestStatus(adminId: Long, requestId: Long, status: String) {
        val userId = database.transaction { connection ->
        val requestedBy = connection.prepareStatement("SELECT requested_by FROM solicitudes_comunidad WHERE id=? FOR UPDATE").use { statement ->
            statement.setLong(1, requestId)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else throw NotFoundException("La solicitud no existe.") }
        }
        val updated = connection.prepareStatement("UPDATE solicitudes_comunidad SET status=?,updated_at=NOW() WHERE id=?").use { it.setString(1, status.trim()); it.setLong(2, requestId); it.executeUpdate() }
        audit(connection, adminId, "ADMIN_UPDATED_COMMUNITY_REQUEST", "COMMUNITY_REQUEST", requestId.toString(), status)
    
        requestedBy
        }
        notifyUsers(
            listOf(userId),
            "Solicitud actualizada",
            "Tu solicitud ahora está: ${status.trim()}.",
            "COMMUNITY_REQUEST_STATUS",
            mapOf("requestId" to requestId.toString())
        )
    }

    fun creditSummary(userId: Long): CreditSummaryDto =
        runCatching { creditSummaryStrict(userId) }
            .onFailure { error ->
                logger.error(
                    "Crédito Kredi+: falló el resumen principal para userId={}. Se devolverá un resumen seguro de respaldo.",
                    userId,
                    error
                )
            }
            .getOrElse { creditSummaryFallback(userId) }

    private fun creditSummaryFallback(userId: Long): CreditSummaryDto {
        val rules = defaultCredimpulsoLevelRules()

        // Intentar reparar/crear la cuenta de crédito, sin impedir que el frontend reciba datos.
        runCatching {
            database.transaction { connection ->
                ensureCreditAccount(connection, userId)
            }
        }.onFailure { error ->
            logger.warn(
                "Crédito Kredi+: no fue posible reparar la cuenta de crédito de userId={} durante el fallback.",
                userId,
                error
            )
        }

        val account = runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT level,credit_limit_usd,status FROM cuentas_credito WHERE user_id=?"
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) null
                        else Triple(
                            result.getInt(1).coerceIn(1, 6),
                            result.getBigDecimal(2)?.toDouble() ?: 60.0,
                            result.getString(3) ?: "ACTIVE"
                        )
                    }
                }
            }
        }.getOrNull()

        val completedPayments = runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT COUNT(*)
                       FROM cuotas_credito ci
                       JOIN prestamos_credito cl ON cl.id=ci.loan_id
                       WHERE cl.user_id=? AND ci.status='PAID'"""
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getInt(1)
                    }
                }
            }
        }.getOrDefault(0)

        val calculatedRule = rules
            .filter { completedPayments >= it.completedPaymentsRequired }
            .maxByOrNull { it.level }
            ?: rules.first()

        val fallbackOverdueDays = runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT COALESCE(MAX(GREATEST(CURRENT_DATE-ci.due_date,0)),0)
                       FROM cuotas_credito ci
                       JOIN prestamos_credito cl ON cl.id=ci.loan_id
                       WHERE cl.user_id=? AND ci.status<>'PAID' AND ci.due_date<CURRENT_DATE AND cl.status<>'CANCELLED'"""
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { result -> result.next(); result.getInt(1).coerceAtLeast(0) }
                }
            }
        }.getOrDefault(0)
        val fallbackPenaltySteps = fallbackOverdueDays / 15
        val effectiveLevel = (calculatedRule.level - fallbackPenaltySteps).coerceIn(1, 6)
        val current = rules.firstOrNull { it.level == effectiveLevel } ?: calculatedRule
        val effectiveCompletedPayments = completedPayments
        val next = rules.filter { it.level > effectiveLevel }.minByOrNull { it.level }

        val usedUsd = runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT COALESCE(SUM(CASE WHEN ci.status<>'PAID' THEN ci.amount_usd ELSE 0 END),0)
                       FROM prestamos_credito cl
                       LEFT JOIN cuotas_credito ci ON ci.loan_id=cl.id
                       WHERE cl.user_id=? AND cl.status<>'CANCELLED'"""
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getBigDecimal(1)?.toDouble() ?: 0.0
                    }
                }
            }
        }.getOrDefault(0.0)

        val automaticLimit = current.baseAmountUsd * current.creditMultiplier
        val effectiveLimit = maxOf(account?.second ?: automaticLimit, automaticLimit)
        val available = (effectiveLimit - usedUsd).coerceAtLeast(0.0)

        val installments = runCatching {
            database.dataSource.connection.use { connection ->
                creditInstallments(connection, userId)
            }
        }.getOrDefault(emptyList())

        val history = runCatching {
            database.dataSource.connection.use { connection ->
                creditHistorySnapshot(connection, userId)
            }
        }.getOrDefault(CreditHistorySnapshot(100, 0, 0, "ACTIVE"))

        val unpaid = installments.filterNot {
            it.status.equals("PAID", true) || it.status.equals("PAGADA", true)
        }
        val walletAddress = runCatching {
            database.dataSource.connection.use { connection -> creditWalletAddress(connection, userId) }
        }.getOrDefault("")

        return CreditSummaryDto(
            level = effectiveLevel,
            walletAddress = walletAddress,
            creditLimitUsd = effectiveLimit,
            usedUsd = usedUsd,
            availableUsd = available,
            status = account?.third ?: "ACTIVE",
            activeLoans = unpaid.map { it.loanId }.distinct().size,
            nextInstallment = unpaid.minByOrNull { it.dueDate },
            installments = installments,
            levelName = current.name,
            completedPayments = effectiveCompletedPayments,
            nextLevelAtPayments = next?.completedPaymentsRequired,
            creditMultiplier = current.creditMultiplier,
            downPaymentPercent = current.downPaymentPercent,
            baseAmountUsd = current.baseAmountUsd,
            maxInstallments = current.maxInstallments,
            creditScorePercentage = history.scorePercentage,
            latePaymentCount = history.latePaymentCount,
            creditHistoryStatus = history.status,
            creditSuspended = history.status == "SUSPENDED" || account?.third == "SUSPENDED",
            levelRules = rules
        )
    }

    private fun creditSummaryStrict(userId: Long): CreditSummaryDto = database.transaction { connection ->
        ensureCreditAccount(connection, userId)

        withSavepointFallback(
            connection = connection,
            fallback = false,
            context = "actualizar estados de cuotas para userId=$userId"
        ) {
            refreshCreditStatuses(connection, userId)
            true
        }

        val progress = refreshCredimpulsoLevel(connection, userId)
        val account = creditAccountSnapshot(connection, userId)
        val walletAddress = creditWalletAddress(connection, userId)
        val history = creditHistorySnapshot(connection, userId)

        val installments = withSavepointFallback(
            connection = connection,
            fallback = emptyList<CreditInstallmentDto>(),
            context = "leer cuotas de Crédito Kredi+ para userId=$userId"
        ) {
            creditInstallments(connection, userId)
        }

        val unpaid = installments.filter { it.status != "PAID" }

        // El nivel calculado es la fuente de verdad. Si una restricción heredada impide
        // persistirlo temporalmente en cuentas_credito, el usuario sigue viendo su nivel real.
        val calculatedLimit = progress.current.baseAmountUsd * progress.current.creditMultiplier
        val effectiveLimit = maxOf(account.creditLimitUsd, calculatedLimit)
        val effectiveAvailable = (effectiveLimit - account.usedUsd).coerceAtLeast(0.0)

        CreditSummaryDto(
            level = progress.current.level,
            walletAddress = walletAddress,
            creditLimitUsd = effectiveLimit,
            usedUsd = account.usedUsd,
            availableUsd = effectiveAvailable,
            status = account.status,
            activeLoans = unpaid.map { it.loanId }.distinct().size,
            nextInstallment = unpaid.minByOrNull { it.dueDate },
            installments = installments,
            levelName = progress.current.name,
            completedPayments = progress.completedPayments,
            nextLevelAtPayments = progress.next?.completedPaymentsRequired,
            creditMultiplier = progress.current.creditMultiplier,
            downPaymentPercent = progress.current.downPaymentPercent,
            baseAmountUsd = progress.current.baseAmountUsd,
            maxInstallments = progress.current.maxInstallments,
            creditScorePercentage = history.scorePercentage,
            latePaymentCount = history.latePaymentCount,
            creditHistoryStatus = history.status,
            creditSuspended = history.status == "SUSPENDED" || account.status == "SUSPENDED",
            levelRules = progress.rules
        )
    }


    private data class AccountantWalletCore(
        val initialBudgetUsd: Double,
        val balanceUsd: Double,
        val totalAllocatedUsd: Double,
        val fundingSource: String,
        val bankIntegrationStatus: String,
        val bankProvider: String?,
        val lastBankSyncAt: String?,
        val walletAddress: String
    )

    private data class AccountantAllocationTarget(
        val id: Long,
        val username: String,
        val name: String,
        val email: String,
        val walletAddress: String
    )

    fun accountantWallet(accountantId: Long): AccountantWalletDto {
        requireAccountant(accountantId)
        val bcv = safeCurrentBcvRate()
        return try {
            database.transaction { connection ->
                ensureWalletV28Schema(connection)
                ensureAccountantWallet(connection, accountantId)
                accountantWalletSnapshot(connection, accountantId, bcv)
            }
        } catch (error: Throwable) {
            logger.error(
                "No fue posible sincronizar completamente la cartera del contador {}. " +
                    "Se conservará el saldo real disponible y la sesión seguirá activa.",
                accountantId,
                error
            )
            accountantWalletFallback(accountantId, bcv)
        }
    }

    /**
     * La pantalla de cartera nunca debe inventar nuevamente US$ 1.000.000 después
     * de que ya existan asignaciones. Si una tabla secundaria falla, esta lectura
     * usa solo las columnas contables esenciales para conservar el saldo real.
     */
    private fun accountantWalletFallback(accountantId: Long, bcv: BcvRate): AccountantWalletDto {
        val configuredInitial = money(config.accountantInitialBudgetUsd)
        val generatedAddress = "ISC-" + sha256("ACCOUNTANT:$accountantId").take(32).uppercase()

        val core = runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT presupuesto_inicial_usd,saldo_disponible_usd,total_asignado_usd,
                              fuente_fondos,estado_integracion_bancaria,proveedor_bancario,
                              ultima_sincronizacion_bancaria_at
                       FROM carteras_presupuesto_contador
                       WHERE contador_id=?"""
                ).use { statement ->
                    statement.setLong(1, accountantId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            null
                        } else {
                            AccountantWalletCore(
                                initialBudgetUsd = result.getBigDecimal(1)?.toDouble() ?: configuredInitial,
                                balanceUsd = result.getBigDecimal(2)?.toDouble() ?: 0.0,
                                totalAllocatedUsd = result.getBigDecimal(3)?.toDouble() ?: 0.0,
                                fundingSource = result.getString(4).orEmpty().ifBlank { "INITIAL_OPERATING_BUDGET" },
                                bankIntegrationStatus = result.getString(5).orEmpty().ifBlank { "READY_FOR_BANK_API" },
                                bankProvider = result.getString(6),
                                lastBankSyncAt = result.getTimestamp(7)?.toInstant()?.toString(),
                                walletAddress = generatedAddress
                            )
                        }
                    }
                }
            }
        }.getOrNull()

        val actual = core ?: AccountantWalletCore(
            initialBudgetUsd = configuredInitial,
            balanceUsd = configuredInitial,
            totalAllocatedUsd = 0.0,
            fundingSource = "INITIAL_OPERATING_BUDGET",
            bankIntegrationStatus = "READY_FOR_BANK_API",
            bankProvider = null,
            lastBankSyncAt = null,
            walletAddress = generatedAddress
        )

        val storedAddress = runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COALESCE(NULLIF(wallet_address,''),?) FROM carteras_presupuesto_contador WHERE contador_id=?"
                ).use { statement ->
                    statement.setString(1, generatedAddress)
                    statement.setLong(2, accountantId)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getString(1).orEmpty() else generatedAddress
                    }
                }
            }
        }.getOrDefault(generatedAddress)

        val fallbackAdminCount = runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM usuarios WHERE UPPER(TRIM(role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN') AND account_status='ACTIVE'"
                ).use { statement -> statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 } }
            }
        }.getOrDefault(0)

        return AccountantWalletDto(
            walletAddress = storedAddress.ifBlank { generatedAddress },
            initialBudgetUsd = money(actual.initialBudgetUsd),
            balanceUsd = money(actual.balanceUsd),
            balanceBs = MoneyMath.usdToVesOrZero(actual.balanceUsd, bcv.rate).toDouble(),
            totalAllocatedUsd = money(actual.totalAllocatedUsd),
            totalAllocatedBs = MoneyMath.usdToVesOrZero(actual.totalAllocatedUsd, bcv.rate).toDouble(),
            bcvRate = bcv.rate,
            bcvDate = bcv.date,
            bcvSource = bcv.source,
            fundingSource = actual.fundingSource,
            bankIntegrationStatus = actual.bankIntegrationStatus,
            bankProvider = actual.bankProvider,
            lastBankSyncAt = actual.lastBankSyncAt,
            adminCount = fallbackAdminCount,
            admins = emptyList(),
            allocations = emptyList(),
            movements = emptyList(),
            budget = AdvancedBudgetDto(
                bankBudgetUsd = money(actual.initialBudgetUsd),
                bankBudgetBs = MoneyMath.usdToVesOrZero(actual.initialBudgetUsd, bcv.rate).toDouble(),
                centralAvailableUsd = money(actual.balanceUsd),
                centralAvailableBs = MoneyMath.usdToVesOrZero(actual.balanceUsd, bcv.rate).toDouble(),
                consolidatedAvailableUsd = money(actual.balanceUsd),
                consolidatedAvailableBs = MoneyMath.usdToVesOrZero(actual.balanceUsd, bcv.rate).toDouble(),
                calculatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                integrityStatus = "PARTIAL_DATA"
            )
        )
    }

    fun allocateAccountantBudget(
        accountantId: Long,
        request: AccountantAllocationRequest
    ): AccountantWalletDto {
        requireAccountant(accountantId)
        val amount = money(request.amountUsd)
        require(amount > 0.0) { "El monto de la asignación debe ser mayor que cero." }

        val bcv = safeCurrentBcvRate()
        if (bcv.rate <= 0.0) {
            throw AppException("La tasa BCV no está disponible. No se realizó ninguna asignación.")
        }

        val amountBs = MoneyMath.usdToVes(amount, bcv.rate).toDouble()
        val reference = "ASG-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8).uppercase()}"
        val description = request.description.trim().ifBlank {
            "Asignación presupuestaria a administrador"
        }.take(500)

        database.transaction { connection ->
            ensureWalletV28Schema(connection)
            ensureAccountantWallet(connection, accountantId)
            syncAccountantAdministratorDirectory(connection)
            val requestedIdentifier = (request.adminUsername ?: request.adminEmail ?: request.email).orEmpty().trim()
            val requestedAdminId = request.adminId
            val idempotencyKey = request.idempotencyKey?.trim()?.takeIf { it.isNotBlank() }?.take(120)
                ?: "ACCOUNTANT-$accountantId-${UUID.randomUUID()}"

            if (requestedIdentifier.isBlank() && requestedAdminId == null) {
                throw AppException("Selecciona un administrador o ingresa su usuario.")
            }
            connection.prepareStatement("SELECT 1 FROM asignaciones_presupuesto_admin WHERE idempotency_key=?").use { st ->
                st.setString(1,idempotencyKey)
                st.executeQuery().use { if (it.next()) return@transaction }
            }

            val target = findAccountantAllocationTarget(
                connection = connection,
                identifier = requestedIdentifier,
                adminId = requestedAdminId
            )
            ensureAdminCredimpulsoWallet(connection, target.id)

            val accountantBefore = connection.prepareStatement(
                """SELECT COALESCE(saldo_disponible_usd,0)
                   FROM carteras_presupuesto_contador
                   WHERE contador_id=?
                   FOR UPDATE"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        throw AppException("No fue posible abrir la cartera presupuestaria del contador.")
                    }
                    result.getBigDecimal(1)?.toDouble() ?: 0.0
                }
            }

            val reservedBudget = currentReservedBudget(connection, accountantId).toDouble()
            val availableForAllocation = MoneyMath.subtractUsd(accountantBefore, reservedBudget).coerceAtLeast(BigDecimal.ZERO).toDouble()
            if (MoneyMath.greaterThanUsd(amount, availableForAllocation)) {
                throw AppException("El presupuesto libre no tiene saldo suficiente. Libera una reserva o reduce la asignación.")
            }
            val accountantAfter = MoneyMath.subtractUsd(accountantBefore, amount).toDouble()

            val adminBefore = connection.prepareStatement(
                """SELECT COALESCE(saldo_disponible_usd,0)
                   FROM carteras_credimpulso_admin
                   WHERE admin_id=?
                   FOR UPDATE"""
            ).use { statement ->
                statement.setLong(1, target.id)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw AppException("No fue posible abrir la cartera del administrador.")
                    result.getBigDecimal(1)?.toDouble() ?: 0.0
                }
            }
            val adminAfter = MoneyMath.addUsd(adminBefore, amount).toDouble()

            val accountantUpdated = connection.prepareStatement(
                """UPDATE carteras_presupuesto_contador
                   SET saldo_disponible_usd=?,
                       total_asignado_usd=COALESCE(total_asignado_usd,0)+?,
                       updated_at=NOW()
                   WHERE contador_id=?"""
            ).use { statement ->
                statement.setBigDecimal(1, MoneyMath.usd(accountantAfter, "Saldo del contador"))
                statement.setBigDecimal(2, MoneyMath.usd(amount, "Monto USD"))
                statement.setLong(3, accountantId)
                statement.executeUpdate()
            }
            if (accountantUpdated != 1) {
                throw AppException("La cartera del contador cambió durante la operación. Actualiza e intenta nuevamente.")
            }

            val adminUpdated = connection.prepareStatement(
                """UPDATE carteras_credimpulso_admin
                   SET saldo_disponible_usd=?,updated_at=NOW()
                   WHERE admin_id=?"""
            ).use { statement ->
                statement.setBigDecimal(1, MoneyMath.usd(adminAfter, "Saldo administrativo"))
                statement.setLong(2, target.id)
                statement.executeUpdate()
            }
            if (adminUpdated != 1) {
                throw AppException("No fue posible acreditar la cartera del administrador.")
            }

            connection.prepareStatement(
                """INSERT INTO asignaciones_presupuesto_admin(
                       contador_id,admin_id,monto_usd,tasa_bcv,monto_bs,
                       saldo_contador_antes_usd,saldo_contador_despues_usd,
                       referencia,descripcion,estado,idempotency_key
                   ) VALUES (?,?,?,?,?,?,?,?,?,'COMPLETED',?)"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setLong(2, target.id)
                statement.setBigDecimal(3, MoneyMath.usd(amount, "Monto USD"))
                statement.setBigDecimal(4, MoneyMath.rate(bcv.rate))
                statement.setBigDecimal(5, MoneyMath.ves(amountBs))
                statement.setBigDecimal(6, MoneyMath.usd(accountantBefore, "Saldo anterior del contador"))
                statement.setBigDecimal(7, MoneyMath.usd(accountantAfter, "Saldo del contador"))
                statement.setString(8, reference)
                statement.setString(9, description)
                statement.setString(10, idempotencyKey)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """INSERT INTO movimientos_cartera_contador(
                       contador_id,admin_id,tipo,monto_usd,tasa_bcv,monto_bs,
                       saldo_antes_usd,saldo_despues_usd,referencia,descripcion
                   ) VALUES (?,?,'ASIGNACION_ADMIN',?,?,?,?,?,?,?)"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setLong(2, target.id)
                statement.setBigDecimal(3, MoneyMath.usd(amount, "Monto USD"))
                statement.setBigDecimal(4, MoneyMath.rate(bcv.rate))
                statement.setBigDecimal(5, MoneyMath.ves(amountBs))
                statement.setBigDecimal(6, MoneyMath.usd(accountantBefore, "Saldo anterior del contador"))
                statement.setBigDecimal(7, MoneyMath.usd(accountantAfter, "Saldo del contador"))
                statement.setString(8, reference)
                statement.setString(9, description)
                statement.executeUpdate()
            }

            insertAccountantAllocationIntoAdminWallet(
                connection = connection,
                adminId = target.id,
                amount = amount,
                balanceBefore = adminBefore,
                balanceAfter = adminAfter,
                reference = reference,
                description = "Asignación del contador: ${description.take(420)}"
            )

            connection.prepareStatement(
                """INSERT INTO transacciones_carteras_continuas(
                       idempotency_key,operation_type,source_wallet_address,destination_wallet_address,
                       amount_usd,bcv_rate,amount_bs,status,reference,actor_user_id,related_user_id,completed_at
                   ) VALUES (?,'ACCOUNTANT_ALLOCATION',?,?,?,?,?,'COMPLETED',?,?,?,NOW())
                   ON CONFLICT(idempotency_key) DO NOTHING"""
            ).use { st ->
                st.setString(1,idempotencyKey)
                st.setString(2,accountantWalletAddress(connection,accountantId))
                st.setString(3,target.walletAddress)
                st.setBigDecimal(4,MoneyMath.usd(amount, "Monto USD"))
                st.setBigDecimal(5,MoneyMath.rate(bcv.rate))
                st.setBigDecimal(6,MoneyMath.ves(amountBs))
                st.setString(7,reference)
                st.setLong(8,accountantId)
                st.setLong(9,target.id)
                st.executeUpdate()
            }

            withSavepointFallback(
                connection = connection,
                fallback = Unit,
                context = "registrar auditoría de asignación del contador"
            ) {
                audit(
                    connection,
                    accountantId,
                    "ACCOUNTANT_BUDGET_ALLOCATED",
                    "ADMIN_CREDIT_WALLET",
                    target.id.toString(),
                    "US$ ${String.format(java.util.Locale.US, "%.2f", amount)} · " +
                        "Bs ${String.format(java.util.Locale.US, "%.2f", amountBs)} · " +
                        "$reference · ${target.email}"
                )
            }
        }

        runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT a.admin_id,COALESCE(w.saldo_disponible_usd,0)
                       FROM asignaciones_presupuesto_admin a
                       JOIN carteras_credimpulso_admin w ON w.admin_id=a.admin_id
                       WHERE a.referencia=? AND a.estado='COMPLETED'
                       LIMIT 1"""
                ).use { statement ->
                    statement.setString(1, reference)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getLong(1) to result.getDouble(2) else null
                    }
                }
            }
        }.onSuccess { budgetRecipient ->
            budgetRecipient?.let { (adminId, availableBalanceUsd) ->
                val notification = WalletNotificationFactory.accountantBudgetAssigned(
                    amountUsd = amount,
                    availableBalanceUsd = availableBalanceUsd,
                    reference = reference
                )
                notifyUsers(
                    listOf(adminId),
                    notification.title,
                    notification.body,
                    notification.type,
                    notification.data
                )
            }
        }.onFailure { error ->
            logger.warn(
                "La asignación se completó, pero no fue posible notificar la acreditación de la cartera administrativa.",
                error
            )
        }
        return accountantWallet(accountantId)
    }

    private fun syncAccountantAdministratorDirectory(connection: Connection) {
        connection.createStatement().use { statement ->
            // Primero retira del conteo cualquier registro que dejó de ser administrador,
            // fue suspendido o fue eliminado. Así la tabla contable nunca conserva cifras viejas.
            statement.executeUpdate(
                """UPDATE directorio_administradores_contador d
                   SET active=FALSE,
                       account_status=COALESCE(u.account_status,'REMOVED'),
                       synced_at=NOW()
                   FROM usuarios u
                   WHERE u.id=d.admin_id
                     AND (
                         UPPER(TRIM(u.role)) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
                         OR u.account_status<>'ACTIVE'
                     )"""
            )
            statement.executeUpdate(
                """UPDATE directorio_administradores_contador d
                   SET active=FALSE,account_status='REMOVED',synced_at=NOW()
                   WHERE NOT EXISTS (SELECT 1 FROM usuarios u WHERE u.id=d.admin_id)"""
            )
            statement.executeUpdate(
                """INSERT INTO directorio_administradores_contador(
                       admin_id,username,email,full_name,wallet_address,wallet_balance_usd,account_status,active,synced_at
                   )
                   SELECT u.id,u.username,u.email,
                          COALESCE(NULLIF(TRIM(p.full_name),''),u.username),
                          COALESCE(NULLIF(w.wallet_address,''),'ISA-' || UPPER(SUBSTRING(MD5('ADMIN:' || u.id::TEXT),1,32))),
                          COALESCE(w.saldo_disponible_usd,0),u.account_status,(u.account_status='ACTIVE'),NOW()
                   FROM usuarios u
                   LEFT JOIN perfiles_usuario p ON p.user_id=u.id
                   LEFT JOIN carteras_credimpulso_admin w ON w.admin_id=u.id
                   WHERE UPPER(TRIM(u.role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
                   ON CONFLICT(admin_id) DO UPDATE SET
                       username=EXCLUDED.username,email=EXCLUDED.email,full_name=EXCLUDED.full_name,
                       wallet_address=EXCLUDED.wallet_address,wallet_balance_usd=EXCLUDED.wallet_balance_usd,
                       account_status=EXCLUDED.account_status,active=EXCLUDED.active,synced_at=NOW()"""
            )
            statement.executeUpdate(
                """INSERT INTO conteo_administradores_contador(
                       singleton_id,total_administradores,administradores_activos,updated_at
                   )
                   SELECT 1,COUNT(*)::INTEGER,
                          COUNT(*) FILTER (WHERE active=TRUE AND account_status='ACTIVE')::INTEGER,
                          NOW()
                   FROM directorio_administradores_contador
                   ON CONFLICT(singleton_id) DO UPDATE SET
                       total_administradores=EXCLUDED.total_administradores,
                       administradores_activos=EXCLUDED.administradores_activos,
                       updated_at=NOW()"""
            )
        }
    }

    private fun accountantWalletAddress(connection: Connection, accountantId: Long): String =
        connection.prepareStatement("SELECT wallet_address FROM carteras_presupuesto_contador WHERE contador_id=?").use { st ->
            st.setLong(1,accountantId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1).orEmpty() else "" }
        }

    private fun findAccountantAllocationTarget(
        connection: Connection,
        identifier: String,
        adminId: Long?
    ): AccountantAllocationTarget {
        syncAccountantAdministratorDirectory(connection)
        val sql = if (adminId != null) {
            """SELECT admin_id,username,COALESCE(NULLIF(full_name,''),username),COALESCE(email,''),COALESCE(wallet_address,''),account_status,active
               FROM directorio_administradores_contador WHERE admin_id=? FOR UPDATE"""
        } else {
            """SELECT admin_id,username,COALESCE(NULLIF(full_name,''),username),COALESCE(email,''),COALESCE(wallet_address,''),account_status,active
               FROM directorio_administradores_contador
               WHERE LOWER(username)=LOWER(?) OR LOWER(COALESCE(email,''))=LOWER(?) FOR UPDATE"""
        }
        return connection.prepareStatement(sql).use { st ->
            if (adminId != null) st.setLong(1,adminId) else { st.setString(1,identifier); st.setString(2,identifier) }
            st.executeQuery().use { rs ->
                if (!rs.next()) throw AppException("El administrador indicado no está registrado en el directorio contable.")
                if (!rs.getBoolean(7) || rs.getString(6) != "ACTIVE") throw AppException("La cuenta del administrador no está activa.")
                AccountantAllocationTarget(
                    id=rs.getLong(1),username=rs.getString(2).orEmpty(),name=rs.getString(3).orEmpty(),
                    email=rs.getString(4).orEmpty(),walletAddress=rs.getString(5).orEmpty()
                )
            }
        }
    }

    private fun ensureWalletV28Schema(connection: Connection) {
        if (walletV28SchemaReady) return
        synchronized(walletV28SchemaLock) {
            if (walletV28SchemaReady) return
            connection.createStatement().use { statement ->
                statement.executeUpdate("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS username VARCHAR(32)")
                statement.executeUpdate(
                    """WITH username_rank AS (
                           SELECT id,username,
                                  ROW_NUMBER() OVER (PARTITION BY LOWER(COALESCE(username,'')) ORDER BY id) AS duplicate_position
                           FROM usuarios
                       )
                       UPDATE usuarios u
                       SET username='USER_' || u.id::TEXT
                       FROM username_rank r
                       WHERE u.id=r.id
                         AND (
                             u.username IS NULL OR BTRIM(u.username)=''
                             OR u.username !~ '^[A-Za-z][A-Za-z0-9_.]{3,23}$'
                             OR r.duplicate_position > 1
                         )"""
                )
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_usuarios_username_lower ON usuarios(LOWER(username))")
                statement.executeUpdate("ALTER TABLE asignaciones_presupuesto_admin ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(120)")
                statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_asignacion_presupuesto_idempotency ON asignaciones_presupuesto_admin(idempotency_key) WHERE idempotency_key IS NOT NULL"
                )
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS movimientos_presupuestarios (
                           id BIGSERIAL PRIMARY KEY,
                           contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
                           tipo VARCHAR(40) NOT NULL CHECK (tipo IN (
                               'BANK_INCOME','INVENTORY_COST','OPERATING_EXPENSE','ADMINISTRATIVE_EXPENSE',
                               'COMMERCIAL_EXPENSE','FINANCIAL_EXPENSE','EXTRAORDINARY_EXPENSE','RESERVE','RELEASE',
                               'ADJUSTMENT_CREDIT','ADJUSTMENT_DEBIT'
                           )),
                           monto_usd NUMERIC(18,2) NOT NULL CHECK (monto_usd > 0),
                           tasa_bcv NUMERIC(18,6) NOT NULL CHECK (tasa_bcv > 0),
                           monto_bs NUMERIC(20,2) NOT NULL CHECK (monto_bs >= 0),
                           saldo_antes_usd NUMERIC(18,2) NOT NULL CHECK (saldo_antes_usd >= 0),
                           saldo_despues_usd NUMERIC(18,2) NOT NULL CHECK (saldo_despues_usd >= 0),
                           referencia VARCHAR(120) NOT NULL UNIQUE,
                           descripcion VARCHAR(500),
                           categoria_gasto VARCHAR(60),
                           idempotency_key VARCHAR(120) UNIQUE,
                           estado VARCHAR(30) NOT NULL DEFAULT 'COMPLETED',
                           created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                           reversed_at TIMESTAMPTZ
                       )"""
                )
                statement.executeUpdate("ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS categoria_gasto VARCHAR(60)")
                statement.executeUpdate("ALTER TABLE movimientos_presupuestarios DROP CONSTRAINT IF EXISTS movimientos_presupuestarios_tipo_check")
                statement.executeUpdate("ALTER TABLE movimientos_presupuestarios ADD CONSTRAINT movimientos_presupuestarios_tipo_check CHECK (tipo IN ('BANK_INCOME','INVENTORY_COST','OPERATING_EXPENSE','ADMINISTRATIVE_EXPENSE','COMMERCIAL_EXPENSE','FINANCIAL_EXPENSE','EXTRAORDINARY_EXPENSE','RESERVE','RELEASE','ADJUSTMENT_CREDIT','ADJUSTMENT_DEBIT'))")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_budget_movements_expense_category ON movimientos_presupuestarios(contador_id,tipo,categoria_gasto,created_at DESC)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_budget_movements_accountant_date ON movimientos_presupuestarios(contador_id,created_at DESC)")
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS directorio_administradores_contador (
                           admin_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
                           username VARCHAR(32) NOT NULL,
                           email VARCHAR(255),
                           full_name VARCHAR(220),
                           wallet_address VARCHAR(80),
                           wallet_balance_usd NUMERIC(18,2) NOT NULL DEFAULT 0,
                           account_status VARCHAR(40) NOT NULL,
                           active BOOLEAN NOT NULL DEFAULT TRUE,
                           synced_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                       )"""
                )
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_directorio_admin_username_lower ON directorio_administradores_contador(LOWER(username))")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_directorio_admin_active ON directorio_administradores_contador(active,account_status,admin_id)")
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS conteo_administradores_contador (
                           singleton_id SMALLINT PRIMARY KEY CHECK(singleton_id=1),
                           total_administradores INTEGER NOT NULL DEFAULT 0 CHECK(total_administradores>=0),
                           administradores_activos INTEGER NOT NULL DEFAULT 0 CHECK(administradores_activos>=0),
                           updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                       )"""
                )
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS transacciones_carteras_continuas (
                           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           sequence_id BIGSERIAL UNIQUE,
                           idempotency_key VARCHAR(120) UNIQUE,
                           operation_type VARCHAR(60) NOT NULL,
                           source_wallet_address VARCHAR(80),
                           destination_wallet_address VARCHAR(80),
                           amount_usd NUMERIC(18,2) NOT NULL CHECK(amount_usd>0),
                           bcv_rate NUMERIC(18,6),
                           amount_bs NUMERIC(20,2),
                           status VARCHAR(30) NOT NULL DEFAULT 'COMPLETED',
                           reference VARCHAR(120) NOT NULL UNIQUE,
                           actor_user_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
                           related_user_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
                           metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
                           created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                           completed_at TIMESTAMPTZ
                       )"""
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_wallet_ledger_source_created ON transacciones_carteras_continuas(source_wallet_address,created_at DESC)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_wallet_ledger_destination_created ON transacciones_carteras_continuas(destination_wallet_address,created_at DESC)")
                statement.executeUpdate("ALTER TABLE tokens_dispositivo ADD COLUMN IF NOT EXISTS token_kind VARCHAR(50) NOT NULL DEFAULT 'FCM_REGISTRATION_TOKEN'")
                statement.executeUpdate("ALTER TABLE tokens_dispositivo ADD COLUMN IF NOT EXISTS last_success_at TIMESTAMPTZ")
                statement.executeUpdate("ALTER TABLE tokens_dispositivo ADD COLUMN IF NOT EXISTS last_error TEXT")
                statement.executeUpdate("ALTER TABLE tokens_dispositivo ADD COLUMN IF NOT EXISTS failure_count INTEGER NOT NULL DEFAULT 0")
                statement.executeUpdate("UPDATE tokens_dispositivo SET token_kind='FCM_REGISTRATION_TOKEN' WHERE token_kind='FIREBASE_INSTALLATION_ID'")
            }
            walletV28SchemaReady = true
        }
    }

    private fun ensureAccountantWallet(connection: Connection, accountantId: Long) {
        ensureWalletV27Schema(connection)

        connection.prepareStatement(
            """INSERT INTO contadores(user_id)
               SELECT id
               FROM usuarios
               WHERE id=?
                 AND UPPER(TRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS')
               ON CONFLICT(user_id)
               DO UPDATE SET activo=TRUE,updated_at=NOW()"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeUpdate()
        }

        val exists = connection.prepareStatement(
            "SELECT 1 FROM contadores WHERE user_id=? AND activo=TRUE"
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result -> result.next() }
        }
        if (!exists) {
            throw ForbiddenException("La cartera presupuestaria está disponible únicamente para el contador designado.")
        }

        val initial = money(config.accountantInitialBudgetUsd)
        connection.prepareStatement(
            """INSERT INTO carteras_presupuesto_contador(
                   contador_id,presupuesto_inicial_usd,saldo_disponible_usd,total_asignado_usd,
                   fuente_fondos,estado_integracion_bancaria,wallet_address
               ) VALUES (?,?,?,0,'INITIAL_OPERATING_BUDGET',?,
                         'ISC-' || UPPER(SUBSTRING(MD5('ACCOUNTANT:' || ?::TEXT),1,32)))
               ON CONFLICT(contador_id) DO NOTHING"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.setBigDecimal(2, MoneyMath.usd(initial, "Saldo inicial"))
            statement.setBigDecimal(3, MoneyMath.usd(initial, "Saldo inicial"))
            statement.setString(4, if (config.bankIntegrationEnabled) "READY_FOR_BANK_API" else "DISABLED")
            statement.setLong(5, accountantId)
            statement.executeUpdate()
        }

        connection.prepareStatement(
            """UPDATE carteras_presupuesto_contador
               SET presupuesto_inicial_usd=COALESCE(presupuesto_inicial_usd,?),
                   saldo_disponible_usd=COALESCE(saldo_disponible_usd,presupuesto_inicial_usd,?),
                   total_asignado_usd=COALESCE(total_asignado_usd,0),
                   fuente_fondos=COALESCE(NULLIF(fuente_fondos,''),'INITIAL_OPERATING_BUDGET'),
                   estado_integracion_bancaria=CASE
                       WHEN ?=FALSE THEN 'DISABLED'
                       WHEN estado_integracion_bancaria IN ('READY_FOR_BANK_API','CONNECTED','SYNCING','ERROR','DISABLED')
                       THEN estado_integracion_bancaria
                       ELSE 'READY_FOR_BANK_API'
                   END,
                   wallet_address=COALESCE(
                       NULLIF(wallet_address,''),
                       'ISC-' || UPPER(SUBSTRING(MD5('ACCOUNTANT:' || contador_id::TEXT),1,32))
                   ),
                   updated_at=NOW()
               WHERE contador_id=?"""
        ).use { statement ->
            statement.setBigDecimal(1, MoneyMath.usd(initial, "Saldo inicial"))
            statement.setBigDecimal(2, MoneyMath.usd(initial, "Saldo inicial"))
            statement.setBoolean(3, config.bankIntegrationEnabled)
            statement.setLong(4, accountantId)
            if (statement.executeUpdate() != 1) {
                throw AppException("No fue posible inicializar la cartera presupuestaria.")
            }
        }

        withSavepointFallback(
            connection = connection,
            fallback = Unit,
            context = "registrar saldo inicial de la cartera del contador"
        ) {
            connection.prepareStatement(
                """INSERT INTO movimientos_cartera_contador(
                       contador_id,tipo,monto_usd,saldo_antes_usd,saldo_despues_usd,
                       referencia,descripcion
                   )
                   SELECT c.contador_id,'SALDO_INICIAL',c.presupuesto_inicial_usd,0,
                          c.saldo_disponible_usd,'INITIAL-' || c.contador_id,
                          'Presupuesto inicial de la cartera central'
                   FROM carteras_presupuesto_contador c
                   WHERE c.contador_id=?
                     AND NOT EXISTS (
                         SELECT 1
                         FROM movimientos_cartera_contador m
                         WHERE m.contador_id=c.contador_id
                           AND m.tipo='SALDO_INICIAL'
                     )"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeUpdate()
            }
        }
    }

    private fun accountantWalletSnapshot(
        connection: Connection,
        accountantId: Long,
        bcv: BcvRate
    ): AccountantWalletDto {
        val core = connection.prepareStatement(
            """SELECT COALESCE(presupuesto_inicial_usd,0),
                      COALESCE(saldo_disponible_usd,0),
                      COALESCE(total_asignado_usd,0),
                      COALESCE(NULLIF(fuente_fondos,''),'INITIAL_OPERATING_BUDGET'),
                      COALESCE(NULLIF(estado_integracion_bancaria,''),'READY_FOR_BANK_API'),
                      proveedor_bancario,
                      ultima_sincronizacion_bancaria_at,
                      COALESCE(NULLIF(wallet_address,''),
                          'ISC-' || UPPER(SUBSTRING(MD5('ACCOUNTANT:' || contador_id::TEXT),1,32)))
               FROM carteras_presupuesto_contador
               WHERE contador_id=?"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    throw AppException("No fue posible abrir la cartera presupuestaria del contador.")
                }
                AccountantWalletCore(
                    initialBudgetUsd = result.getBigDecimal(1)?.toDouble() ?: 0.0,
                    balanceUsd = result.getBigDecimal(2)?.toDouble() ?: 0.0,
                    totalAllocatedUsd = result.getBigDecimal(3)?.toDouble() ?: 0.0,
                    fundingSource = result.getString(4).orEmpty(),
                    bankIntegrationStatus = result.getString(5).orEmpty(),
                    bankProvider = result.getString(6),
                    lastBankSyncAt = result.getTimestamp(7)?.toInstant()?.toString(),
                    walletAddress = result.getString(8).orEmpty()
                )
            }
        }

        val totalAllocatedBs = withSavepointFallback(
            connection = connection,
            fallback = 0.0,
            context = "calcular total en bolívares asignado por el contador"
        ) {
            connection.prepareStatement(
                """SELECT COALESCE(SUM(monto_bs),0)
                   FROM asignaciones_presupuesto_admin
                   WHERE contador_id=? AND estado='COMPLETED'"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getBigDecimal(1)?.toDouble() ?: 0.0 else 0.0
                }
            }
        }

        withSavepointFallback(
            connection = connection,
            fallback = Unit,
            context = "reparar carteras de administradores visibles para el contador"
        ) {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """INSERT INTO carteras_credimpulso_admin(
                           admin_id,saldo_disponible_usd,total_transferido_usd,wallet_address
                       )
                       SELECT u.id,0,0,
                              'ISA-' || UPPER(SUBSTRING(MD5('ADMIN:' || u.id::TEXT),1,32))
                       FROM usuarios u
                       WHERE UPPER(TRIM(u.role)) IN (
                           'ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN'
                       )
                         AND u.account_status='ACTIVE'
                       ON CONFLICT(admin_id) DO UPDATE
                       SET wallet_address=COALESCE(
                           NULLIF(carteras_credimpulso_admin.wallet_address,''),
                           EXCLUDED.wallet_address
                       )"""
                )
            }
        }

        withSavepointFallback(connection, Unit, "sincronizar directorio administrativo") { syncAccountantAdministratorDirectory(connection) }

        val admins = withSavepointFallback(
            connection = connection,
            fallback = emptyList<AccountantAdminDto>(),
            context = "cargar administradores y sus carteras"
        ) {
            connection.prepareStatement(
                """SELECT admin_id,username,COALESCE(NULLIF(full_name,''),username),COALESCE(email,''),
                          COALESCE(wallet_address,''),COALESCE(wallet_balance_usd,0)
                   FROM directorio_administradores_contador
                   WHERE active=TRUE AND account_status='ACTIVE'
                   ORDER BY full_name,username"""
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                AccountantAdminDto(
                                    id = result.getLong(1),
                                    username = result.getString(2).orEmpty(),
                                    name = result.getString(3).orEmpty().ifBlank { result.getString(2).orEmpty() },
                                    email = result.getString(4).orEmpty(),
                                    walletAddress = result.getString(5).orEmpty(),
                                    walletBalanceUsd = result.getBigDecimal(6)?.toDouble() ?: 0.0
                                )
                            )
                        }
                    }
                }
            }
        }

        val allocations = withSavepointFallback(
            connection = connection,
            fallback = emptyList<AccountantAllocationDto>(),
            context = "cargar historial de asignaciones del contador"
        ) {
            connection.prepareStatement(
                """SELECT a.id,a.admin_id,
                          COALESCE(
                              NULLIF(TRIM(CONCAT_WS(' ',p.first_name,p.middle_name,p.last_name,p.second_last_name)),''),
                              NULLIF(p.full_name,''),
                              u.email
                          ),
                          a.monto_usd,a.monto_bs,a.tasa_bcv,a.referencia,a.descripcion,a.created_at
                   FROM asignaciones_presupuesto_admin a
                   JOIN usuarios u ON u.id=a.admin_id
                   LEFT JOIN perfiles_usuario p ON p.user_id=u.id
                   WHERE a.contador_id=?
                   ORDER BY a.created_at DESC
                   LIMIT 100"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                AccountantAllocationDto(
                                    id = result.getLong(1),
                                    adminId = result.getLong(2),
                                    adminName = result.getString(3).orEmpty(),
                                    amountUsd = result.getBigDecimal(4)?.toDouble() ?: 0.0,
                                    amountBs = result.getBigDecimal(5)?.toDouble() ?: 0.0,
                                    bcvRate = result.getBigDecimal(6)?.toDouble() ?: 0.0,
                                    reference = result.getString(7).orEmpty(),
                                    description = result.getString(8),
                                    createdAt = result.getTimestamp(9)?.toInstant()?.toString().orEmpty()
                                )
                            )
                        }
                    }
                }
            }
        }

        val movements = withSavepointFallback(
            connection = connection,
            fallback = emptyList<AccountantWalletMovementDto>(),
            context = "cargar movimientos de la cartera del contador"
        ) {
            connection.prepareStatement(
                """SELECT m.id,m.tipo,m.monto_usd,m.monto_bs,m.tasa_bcv,
                          m.saldo_antes_usd,m.saldo_despues_usd,m.admin_id,
                          COALESCE(
                              NULLIF(TRIM(CONCAT_WS(' ',p.first_name,p.middle_name,p.last_name,p.second_last_name)),''),
                              NULLIF(p.full_name,''),
                              u.email
                          ),
                          m.referencia,m.descripcion,m.created_at
                   FROM movimientos_cartera_contador m
                   LEFT JOIN usuarios u ON u.id=m.admin_id
                   LEFT JOIN perfiles_usuario p ON p.user_id=u.id
                   WHERE m.contador_id=?
                   ORDER BY m.created_at DESC
                   LIMIT 100"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            val movementAmount = result.getBigDecimal(3)?.toDouble() ?: 0.0
                            val historicalRate = result.getBigDecimal(5)?.toDouble()
                                ?.takeIf { it > 0.0 } ?: bcv.rate
                            val historicalBs = result.getBigDecimal(4)?.toDouble()
                                ?: MoneyMath.usdToVesOrZero(movementAmount, historicalRate).toDouble()
                            add(
                                AccountantWalletMovementDto(
                                    id = result.getLong(1),
                                    type = result.getString(2).orEmpty(),
                                    amountUsd = movementAmount,
                                    amountBs = historicalBs,
                                    bcvRate = historicalRate,
                                    balanceBeforeUsd = result.getBigDecimal(6)?.toDouble() ?: 0.0,
                                    balanceAfterUsd = result.getBigDecimal(7)?.toDouble() ?: 0.0,
                                    adminId = result.getLong(8).takeUnless { result.wasNull() },
                                    adminName = result.getString(9),
                                    reference = result.getString(10),
                                    description = result.getString(11),
                                    createdAt = result.getTimestamp(12)?.toInstant()?.toString().orEmpty()
                                )
                            )
                        }
                    }
                }
            }
        }

        val administratorCount = withSavepointFallback(
            connection = connection,
            fallback = admins.size,
            context = "cargar conteo de administradores"
        ) {
            connection.prepareStatement(
                "SELECT administradores_activos FROM conteo_administradores_contador WHERE singleton_id=1"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next()) result.getInt(1) else admins.size
                }
            }
        }

        return AccountantWalletDto(
            walletAddress = core.walletAddress,
            initialBudgetUsd = money(core.initialBudgetUsd),
            balanceUsd = money(core.balanceUsd),
            balanceBs = MoneyMath.usdToVesOrZero(core.balanceUsd, bcv.rate).toDouble(),
            totalAllocatedUsd = money(core.totalAllocatedUsd),
            totalAllocatedBs = money(totalAllocatedBs),
            bcvRate = bcv.rate,
            bcvDate = bcv.date,
            bcvSource = bcv.source,
            fundingSource = core.fundingSource,
            bankIntegrationStatus = core.bankIntegrationStatus,
            bankProvider = core.bankProvider,
            lastBankSyncAt = core.lastBankSyncAt,
            adminCount = administratorCount,
            admins = admins,
            allocations = allocations,
            movements = movements,
            budget = advancedBudgetSnapshot(connection, accountantId, core, bcv)
        )
    }


    fun versionPolicy(): VersionPolicyDto = VersionPolicyDto()

    /**
     * Ventana predictiva exclusiva del Contador. Calcula el riesgo del sistema,
     * proyecciones de liquidez y probabilidad de pago de usuarios y administradores.
     */
    fun predictiveDashboard(accountantId: Long): PredictiveDashboardDto {
        requireAccountant(accountantId)
        val wallet = accountantWallet(accountantId)
        val rate = wallet.bcvRate.takeIf { it > 0.0 } ?: safeCurrentBcvRate().rate.coerceAtLeast(1.0)
        return database.transaction { connection ->
            val users = predictiveUsers(connection, rate)
            val administrators = predictiveAdministrators(connection, rate)
            val recent = connection.prepareStatement(
                """SELECT
                       COALESCE(SUM(CASE WHEN tipo='OPERATING_EXPENSE' THEN monto_usd ELSE 0 END),0),
                       COALESCE(SUM(CASE WHEN tipo IN ('BANK_INCOME','ADJUSTMENT_CREDIT') THEN monto_usd ELSE 0 END),0)
                   FROM movimientos_presupuestarios
                   WHERE contador_id=? AND estado='COMPLETED' AND created_at>=NOW()-INTERVAL '90 days'"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeQuery().use { result ->
                    if (result.next()) Pair(result.getBigDecimal(1) ?: BigDecimal.ZERO, result.getBigDecimal(2) ?: BigDecimal.ZERO)
                    else Pair(BigDecimal.ZERO, BigDecimal.ZERO)
                }
            }
            val observedInstallments = connection.prepareStatement("SELECT COUNT(*) FROM cuotas_credito").use { statement ->
                statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
            }
            val activeBorrowers = connection.prepareStatement(
                "SELECT COUNT(DISTINCT user_id) FROM prestamos_credito WHERE status IN ('ACTIVE','OVERDUE')"
            ).use { statement -> statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 } }
            val budget = wallet.budget
            val budgetPrediction = PredictiveEngine.budget(
                PredictiveBudgetInput(
                    consolidatedAvailableUsd = MoneyMath.usd(budget.consolidatedAvailableUsd),
                    reservedUsd = MoneyMath.usd(budget.reservedUsd),
                    outstandingLoansUsd = MoneyMath.usd(budget.loansOutstandingUsd),
                    overdueLoansUsd = MoneyMath.usd(budget.overdueLoansUsd),
                    recoveredLoansUsd = MoneyMath.usd(budget.loansRecoveredUsd),
                    disbursedLoansUsd = MoneyMath.usd(budget.loansDisbursedUsd),
                    recentOperatingExpenses90DaysUsd = recent.first,
                    recentBankIncome90DaysUsd = recent.second,
                    activeBorrowers = activeBorrowers,
                    observedInstallments = observedInstallments
                )
            )
            val forecastDtos = budgetPrediction.forecasts.map {
                BudgetForecastPointDto(
                    horizonDays = it.horizonDays,
                    expectedCollectionsUsd = it.expectedCollectionsUsd.toDouble(),
                    expectedOperatingExpensesUsd = it.expectedOperatingExpensesUsd.toDouble(),
                    expectedBankIncomeUsd = it.expectedBankIncomeUsd.toDouble(),
                    expectedOverdueUsd = it.expectedOverdueUsd.toDouble(),
                    projectedAvailableUsd = it.projectedAvailableUsd.toDouble(),
                    confidencePercent = it.confidencePercent.toDouble()
                )
            }
            val sampleSize = users.sumOf { it.sampleSize } + administrators.sumOf { it.sampleSize } + observedInstallments
            val dataQuality = when {
                sampleSize >= 150 -> "HIGH"
                sampleSize >= 40 -> "MEDIUM"
                else -> "LOW"
            }
            connection.prepareStatement(
                """INSERT INTO corridas_predictivas_presupuesto(
                       contador_id,collection_probability_percent,default_risk_percent,liquidity_risk_level,
                       confidence_percent,forecasts_json,alerts_json,model_version)
                   SELECT ?,?,?,?,?,?,?, 'PREDICTIVE-6.0.0'
                   WHERE NOT EXISTS (
                       SELECT 1 FROM corridas_predictivas_presupuesto
                       WHERE contador_id=? AND generated_at>NOW()-INTERVAL '1 hour'
                   )"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setBigDecimal(2, budgetPrediction.collectionProbabilityPercent)
                statement.setBigDecimal(3, budgetPrediction.defaultRiskPercent)
                statement.setString(4, budgetPrediction.liquidityRiskLevel)
                statement.setBigDecimal(5, budgetPrediction.confidencePercent)
                statement.setString(6, gson.toJson(forecastDtos))
                statement.setString(7, gson.toJson(budgetPrediction.alerts))
                statement.setLong(8, accountantId)
                statement.executeUpdate()
            }
            PredictiveDashboardDto(
                generatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                dataQuality = dataQuality,
                collectionProbabilityPercent = budgetPrediction.collectionProbabilityPercent.toDouble(),
                defaultRiskPercent = budgetPrediction.defaultRiskPercent.toDouble(),
                liquidityRiskLevel = budgetPrediction.liquidityRiskLevel,
                confidencePercent = budgetPrediction.confidencePercent.toDouble(),
                forecasts = forecastDtos,
                users = users,
                administrators = administrators,
                alerts = budgetPrediction.alerts
            )
        }
    }

    private fun predictiveUsers(connection: Connection, bcvRate: Double): List<SubjectPredictionDto> {
        val sql = """
            SELECT u.id,u.username,COALESCE(NULLIF(up.full_name,''),u.username),
                   COALESCE(h.porcentaje,100),COALESCE(h.pagos_a_tiempo,0),COALESCE(h.pagos_atrasados,0),
                   (SELECT COUNT(*) FROM pagos p JOIN pedidos o ON o.id=p.order_id WHERE o.user_id=u.id AND p.status='VERIFIED'),
                   (SELECT COUNT(*) FROM pagos p JOIN pedidos o ON o.id=p.order_id WHERE o.user_id=u.id AND p.status='REJECTED'),
                   (SELECT COUNT(*) FROM pedidos o WHERE o.user_id=u.id),
                   (SELECT COUNT(*) FROM pedidos o WHERE o.user_id=u.id AND LOWER(o.status) NOT LIKE '%rechaz%' AND LOWER(o.status) NOT LIKE '%cancel%'),
                   (SELECT COUNT(*) FROM pedidos o WHERE o.user_id=u.id AND (LOWER(o.status) LIKE '%rechaz%' OR LOWER(o.status) LIKE '%cancel%')),
                   COALESCE((SELECT SUM(o.total) FROM pedidos o WHERE o.user_id=u.id),0),
                   COALESCE(cc.credit_limit_usd,0),
                   COALESCE((SELECT SUM(ci.amount_usd) FROM cuotas_credito ci JOIN prestamos_credito l ON l.id=ci.loan_id WHERE l.user_id=u.id AND ci.status<>'PAID'),0),
                   COALESCE((SELECT SUM(ci.amount_usd) FROM cuotas_credito ci JOIN prestamos_credito l ON l.id=ci.loan_id WHERE l.user_id=u.id AND (ci.status='OVERDUE' OR (ci.status='PENDING' AND ci.due_date<CURRENT_DATE))),0),
                   GREATEST(0,EXTRACT(DAY FROM NOW()-u.created_at)::INT)
            FROM usuarios u
            LEFT JOIN perfiles_usuario up ON up.user_id=u.id
            LEFT JOIN historial_crediticio_usuarios h ON h.user_id=u.id
            LEFT JOIN cuentas_credito cc ON cc.user_id=u.id
            WHERE UPPER(u.role) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS','WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA')
              AND u.account_status='ACTIVE'
              AND u.created_at<=NOW()-INTERVAL '30 days'
              AND COALESCE(cc.level,1)>1
            ORDER BY u.id DESC LIMIT 1000
        """.trimIndent()
        return connection.prepareStatement(sql).use { statement -> statement.executeQuery().use { result -> buildList {
            while (result.next()) {
                val totalPurchases = result.getInt(9)
                val onTime = result.getInt(5)
                val late = result.getInt(6)
                val verified = result.getInt(7)
                val rejected = result.getInt(8)
                val input = PredictiveSubjectInput(
                    subjectId = result.getLong(1), username = result.getString(2).orEmpty(), displayName = result.getString(3).orEmpty(), role = "BENEFICIARY",
                    creditScorePercent = result.getInt(4), onTimePayments = onTime, latePayments = late,
                    verifiedDirectPayments = verified, rejectedDirectPayments = rejected,
                    totalPurchases = totalPurchases, completedPurchases = result.getInt(10), cancelledPurchases = result.getInt(11),
                    totalPurchasedUsd = MoneyMath.vesToUsd(result.getBigDecimal(12) ?: BigDecimal.ZERO, MoneyMath.rate(bcvRate)),
                    outstandingUsd = result.getBigDecimal(14) ?: BigDecimal.ZERO, overdueUsd = result.getBigDecimal(15) ?: BigDecimal.ZERO,
                    currentCreditLimitUsd = result.getBigDecimal(13) ?: BigDecimal.ZERO, activityDays = result.getInt(16)
                )
                val predicted = PredictiveEngine.subject(input)
                val dto = predicted.toDto(input, onTime + late + verified + rejected + totalPurchases)
                persistSubjectPrediction(connection, dto)
                add(dto)
            }
        } } }
    }

    private fun predictiveAdministrators(connection: Connection, bcvRate: Double): List<SubjectPredictionDto> {
        val sql = """
            SELECT u.id,u.username,COALESCE(NULLIF(up.full_name,''),u.username),
                   (SELECT COUNT(*) FROM pagos p WHERE p.verified_by=u.id AND p.status='VERIFIED'),
                   (SELECT COUNT(*) FROM pagos p WHERE p.verified_by=u.id AND p.status='REJECTED'),
                   (SELECT COUNT(*) FROM pedidos o JOIN jornadas j ON j.id=o.fair_id WHERE j.created_by=u.id),
                   (SELECT COUNT(*) FROM pedidos o JOIN jornadas j ON j.id=o.fair_id WHERE j.created_by=u.id AND LOWER(o.status) NOT LIKE '%rechaz%' AND LOWER(o.status) NOT LIKE '%cancel%'),
                   (SELECT COUNT(*) FROM pedidos o JOIN jornadas j ON j.id=o.fair_id WHERE j.created_by=u.id AND (LOWER(o.status) LIKE '%rechaz%' OR LOWER(o.status) LIKE '%cancel%')),
                   COALESCE((SELECT SUM(o.total) FROM pedidos o JOIN jornadas j ON j.id=o.fair_id WHERE j.created_by=u.id),0),
                   COALESCE((SELECT SUM(a.monto_usd) FROM asignaciones_presupuesto_admin a WHERE a.admin_id=u.id AND a.estado='COMPLETED'),0),
                   COALESCE(w.saldo_disponible_usd,0),
                   COALESCE((SELECT SUM(ci.amount_usd) FROM cuotas_credito ci JOIN prestamos_credito l ON l.id=ci.loan_id JOIN pedidos o ON o.id=l.order_id JOIN jornadas j ON j.id=o.fair_id WHERE j.created_by=u.id AND ci.status<>'PAID'),0),
                   COALESCE((SELECT SUM(ci.amount_usd) FROM cuotas_credito ci JOIN prestamos_credito l ON l.id=ci.loan_id JOIN pedidos o ON o.id=l.order_id JOIN jornadas j ON j.id=o.fair_id WHERE j.created_by=u.id AND (ci.status='OVERDUE' OR (ci.status='PENDING' AND ci.due_date<CURRENT_DATE))),0),
                   GREATEST(0,EXTRACT(DAY FROM NOW()-u.created_at)::INT)
            FROM usuarios u
            LEFT JOIN perfiles_usuario up ON up.user_id=u.id
            LEFT JOIN carteras_credimpulso_admin w ON w.admin_id=u.id
            WHERE UPPER(u.role) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
              AND u.account_status='ACTIVE'
              AND u.created_at<=NOW()-INTERVAL '30 days'
            ORDER BY u.id DESC LIMIT 500
        """.trimIndent()
        return connection.prepareStatement(sql).use { statement -> statement.executeQuery().use { result -> buildList {
            while (result.next()) {
                val verified = result.getInt(4)
                val rejected = result.getInt(5)
                val processedPayments = verified + rejected
                val totalPurchases = result.getInt(6)
                val completedPurchases = result.getInt(7)
                val cancelledPurchases = result.getInt(8)
                // Rechazar un comprobante inválido es una acción correcta del administrador,
                // por lo que cuenta como revisión procesada y no como impago propio.
                val operationalScore = if (totalPurchases <= 0) 75
                    else ((completedPurchases * 100.0) / totalPurchases).toInt().coerceIn(0, 100)
                val input = PredictiveSubjectInput(
                    subjectId = result.getLong(1), username = result.getString(2).orEmpty(), displayName = result.getString(3).orEmpty(), role = "ADMIN",
                    creditScorePercent = operationalScore, verifiedDirectPayments = processedPayments, rejectedDirectPayments = 0,
                    totalPurchases = totalPurchases, completedPurchases = completedPurchases, cancelledPurchases = cancelledPurchases,
                    totalPurchasedUsd = MoneyMath.vesToUsd(result.getBigDecimal(9) ?: BigDecimal.ZERO, MoneyMath.rate(bcvRate)),
                    allocatedBudgetUsd = result.getBigDecimal(10) ?: BigDecimal.ZERO,
                    availableBudgetUsd = result.getBigDecimal(11) ?: BigDecimal.ZERO,
                    outstandingUsd = result.getBigDecimal(12) ?: BigDecimal.ZERO,
                    overdueUsd = result.getBigDecimal(13) ?: BigDecimal.ZERO,
                    activityDays = result.getInt(14)
                )
                val predicted = PredictiveEngine.subject(input)
                val dto = predicted.toDto(input, processedPayments + totalPurchases)
                persistSubjectPrediction(connection, dto)
                add(dto)
            }
        } } }
    }

    private fun PredictiveSubjectResult.toDto(input: PredictiveSubjectInput, sampleSize: Int) = SubjectPredictionDto(
        subjectId = input.subjectId, username = input.username, displayName = input.displayName, role = input.role,
        paymentSuccessPercent = paymentSuccessPercent.toDouble(), purchaseSuccessPercent = purchaseSuccessPercent.toDouble(),
        latePaymentProbabilityPercent = latePaymentProbabilityPercent.toDouble(), confidencePercent = confidencePercent.toDouble(),
        riskLevel = riskLevel, recommendedCreditLimitUsd = recommendedCreditLimitUsd.toDouble(),
        predictedNextPurchaseUsd = predictedNextPurchaseUsd.toDouble(), sampleSize = sampleSize,
        factors = factors.map { PredictionFactorDto(it.code,it.label,it.value.toDouble(),it.weight.toDouble(),it.impact,it.explanation) }
    )

    private fun persistSubjectPrediction(connection: Connection, dto: SubjectPredictionDto) {
        connection.prepareStatement(
            """INSERT INTO evaluaciones_predictivas(
                   subject_id,subject_role,payment_success_percent,purchase_success_percent,late_probability_percent,
                   confidence_percent,risk_level,recommended_limit_usd,predicted_next_purchase_usd,sample_size,factors_json,model_version)
               SELECT ?,?,?,?,?,?,?,?,?,?,?, 'PREDICTIVE-6.0.0'
               WHERE NOT EXISTS (
                   SELECT 1 FROM evaluaciones_predictivas WHERE subject_id=? AND generated_at>NOW()-INTERVAL '6 hours'
               )"""
        ).use { statement ->
            statement.setLong(1,dto.subjectId); statement.setString(2,dto.role)
            statement.setBigDecimal(3,BigDecimal.valueOf(dto.paymentSuccessPercent)); statement.setBigDecimal(4,BigDecimal.valueOf(dto.purchaseSuccessPercent))
            statement.setBigDecimal(5,BigDecimal.valueOf(dto.latePaymentProbabilityPercent)); statement.setBigDecimal(6,BigDecimal.valueOf(dto.confidencePercent))
            statement.setString(7,dto.riskLevel); statement.setBigDecimal(8,MoneyMath.usd(dto.recommendedCreditLimitUsd))
            statement.setBigDecimal(9,MoneyMath.usd(dto.predictedNextPurchaseUsd)); statement.setInt(10,dto.sampleSize)
            statement.setString(11,gson.toJson(dto.factors)); statement.setLong(12,dto.subjectId); statement.executeUpdate()
        }
    }

    fun adminInvoiceIntegrityRecords(): List<AdminInvoiceIntegrityDto> = database.transaction { connection ->
        connection.prepareStatement(
            """SELECT f.order_id
               FROM facturas f
               WHERE f.integrity_verified_at IS NULL
                  OR f.integrity_status='PENDING'
                  OR f.algorithm_version<>?
                  OR EXISTS (
                      SELECT 1 FROM pagos p
                      WHERE p.order_id=f.order_id
                        AND COALESCE(p.verified_at,p.created_at)>f.integrity_verified_at
                  )
                  OR EXISTS (
                      SELECT 1 FROM prestamos_credito l
                      WHERE l.order_id=f.order_id AND l.updated_at>f.integrity_verified_at
                  )
               ORDER BY f.generated_at DESC
               LIMIT 250"""
        ).use { statement ->
            statement.setString(1, InvoiceIntegrityEngine.ALGORITHM_VERSION)
            statement.executeQuery().use { result ->
                while (result.next()) verifyAndSealInvoice(connection, result.getLong(1))
            }
        }
        connection.prepareStatement(
            """SELECT o.id,f.invoice_number,COALESCE(NULLIF(up.full_name,''),u.username),o.total,
                      f.integrity_status,f.integrity_score,f.integrity_difference_bs,f.algorithm_version,
                      f.validation_warnings,f.integrity_verified_at,o.created_at
               FROM facturas f JOIN pedidos o ON o.id=f.order_id JOIN usuarios u ON u.id=o.user_id
               LEFT JOIN perfiles_usuario up ON up.user_id=u.id
               ORDER BY o.created_at DESC LIMIT 1000"""
        ).use { statement -> statement.executeQuery().use { result -> buildList {
            while (result.next()) add(AdminInvoiceIntegrityDto(
                purchaseId=result.getLong(1),invoiceNumber=result.getString(2),customerName=result.getString(3),
                totalBs=result.getBigDecimal(4)?.toDouble()?:0.0,status=result.getString(5),integrityScore=result.getInt(6),
                differenceBs=result.getBigDecimal(7)?.toDouble()?:0.0,algorithmVersion=result.getString(8),
                warnings=result.getString(9).orEmpty().lines().filter { it.isNotBlank() },
                verifiedAt=result.getObject(10,OffsetDateTime::class.java)?.toString(),createdAt=result.getObject(11,OffsetDateTime::class.java).toString()
            ))
        } } }
    }

    private fun verifyAndSealInvoice(connection: Connection, orderId: Long): InvoiceIntegrityDto {
        data class InvoiceIntegrityRow(val invoiceNumber:String,val financingType:String,val orderTotal:BigDecimal,val discountAmount:BigDecimal)
        val row = connection.prepareStatement(
            "SELECT f.invoice_number,o.financing_type,o.total,COALESCE(o.discount_amount,0) FROM facturas f JOIN pedidos o ON o.id=f.order_id WHERE o.id=?"
        ).use { statement -> statement.setLong(1,orderId); statement.executeQuery().use { result ->
            if (!result.next()) throw NotFoundException("No se encontró la factura.")
            InvoiceIntegrityRow(result.getString(1),result.getString(2),result.getBigDecimal(3)?:BigDecimal.ZERO,result.getBigDecimal(4)?:BigDecimal.ZERO)
        } }
        val lineValues = connection.prepareStatement(
            """SELECT COALESCE(SUM(line_total),0),COUNT(*) FROM (
                   SELECT unit_price*quantity AS line_total FROM items_pedido WHERE order_id=?
                   UNION ALL SELECT unit_price*quantity FROM combos_pedido WHERE order_id=?
               ) lines"""
        ).use { statement -> statement.setLong(1,orderId);statement.setLong(2,orderId);statement.executeQuery().use { result ->
            result.next(); Pair(result.getBigDecimal(1)?:BigDecimal.ZERO,result.getInt(2))
        } }
        val payment = connection.prepareStatement(
            "SELECT COALESCE(amount_paid,0),status FROM pagos WHERE order_id=? ORDER BY created_at DESC LIMIT 1"
        ).use { statement -> statement.setLong(1,orderId);statement.executeQuery().use { result ->
            if(result.next()) Pair(result.getBigDecimal(1)?:BigDecimal.ZERO,result.getString(2).orEmpty())
            else Pair(BigDecimal.ZERO,"MISSING")
        } }
        val creditPrincipal = connection.prepareStatement("SELECT COALESCE(principal_bs,0) FROM prestamos_credito WHERE order_id=? LIMIT 1").use { statement ->
            statement.setLong(1,orderId);statement.executeQuery().use { result -> if(result.next()) result.getBigDecimal(1)?:BigDecimal.ZERO else BigDecimal.ZERO }
        }
        val duplicates = connection.prepareStatement("SELECT COUNT(*) FROM facturas WHERE invoice_number=?").use { statement ->
            statement.setString(1,row.invoiceNumber);statement.executeQuery().use { result -> result.next();result.getInt(1) }
        }
        val evaluated = InvoiceIntegrityEngine.evaluate(InvoiceIntegrityInput(
            orderId=orderId,invoiceNumber=row.invoiceNumber,financingType=row.financingType,orderTotalBs=row.orderTotal,
            lineTotalBs=lineValues.first.subtract(row.discountAmount).max(BigDecimal.ZERO),paymentTotalBs=payment.first,paymentStatus=payment.second,
            creditPrincipalBs=creditPrincipal,lineCount=lineValues.second,
            duplicateInvoiceCount=duplicates
        ))
        connection.prepareStatement(
            """UPDATE facturas SET integrity_status=?,integrity_score=?,calculated_total_bs=?,integrity_difference_bs=?,
                      document_hash=?,algorithm_version=?,validation_warnings=?,integrity_verified_at=NOW()
               WHERE order_id=?"""
        ).use { statement ->
            statement.setString(1,evaluated.status);statement.setInt(2,evaluated.integrityScore);statement.setBigDecimal(3,evaluated.calculatedTotalBs)
            statement.setBigDecimal(4,evaluated.differenceBs);statement.setString(5,evaluated.documentHash);statement.setString(6,InvoiceIntegrityEngine.ALGORITHM_VERSION)
            statement.setString(7,evaluated.warnings.joinToString("\n"));statement.setLong(8,orderId);statement.executeUpdate()
        }
        return InvoiceIntegrityDto(evaluated.status,evaluated.integrityScore,evaluated.calculatedTotalBs.toDouble(),evaluated.differenceBs.toDouble(),
            evaluated.documentHash,InvoiceIntegrityEngine.ALGORITHM_VERSION,evaluated.warnings,OffsetDateTime.now(ZoneOffset.UTC).toString())
    }

    fun registerBudgetMovement(
        accountantId: Long,
        request: BudgetMovementRequest
    ): AccountantWalletDto {
        requireAccountant(accountantId)
        val requestedType = request.type.trim().uppercase()
        val allowedTypes = setOf(
            "BANK_INCOME", "INVENTORY_COST", "OPERATING_EXPENSE", "ADMINISTRATIVE_EXPENSE",
            "COMMERCIAL_EXPENSE", "FINANCIAL_EXPENSE", "EXTRAORDINARY_EXPENSE", "RESERVE", "RELEASE",
            "ADJUSTMENT_CREDIT", "ADJUSTMENT_DEBIT"
        )
        if (requestedType !in allowedTypes) throw AppException("Tipo de movimiento presupuestario inválido.")
        val amount = MoneyMath.positive(MoneyMath.usd(request.amountUsd), "Monto presupuestario")
        val bcv = safeCurrentBcvRate()
        if (bcv.rate <= 0.0) throw AppException("La tasa BCV no está disponible. No se registró el movimiento.")
        val amountBs = MoneyMath.usdToVes(amount, MoneyMath.rate(bcv.rate))
        val description = request.description.trim().ifBlank { "Movimiento presupuestario" }.take(500)
        val expenseCategory = when (requestedType) {
            "OPERATING_EXPENSE" -> request.expenseCategory?.trim()?.uppercase()?.takeIf { it in OPERATING_EXPENSE_CATEGORIES } ?: "OTHER_OPERATING"
            "ADMINISTRATIVE_EXPENSE" -> request.expenseCategory?.trim()?.uppercase()?.takeIf { it in ADMINISTRATIVE_EXPENSE_CATEGORIES } ?: "OTHER_ADMINISTRATIVE"
            else -> null
        }
        val transactionDate = request.transactionDate?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrElse { throw AppException("La fecha del movimiento debe usar el formato AAAA-MM-DD.") }
        } ?: LocalDate.now(ZoneOffset.UTC)
        val paymentMethod = request.paymentMethod?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.take(40)
        val costBehavior = request.costBehavior?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.also {
            if (it !in setOf("FIXED", "VARIABLE")) throw AppException("El comportamiento del costo debe ser FIXED o VARIABLE.")
        }
        val recurrence = request.recurrence?.trim()?.uppercase()?.takeIf(String::isNotBlank)?.also {
            if (it !in setOf("ONE_TIME", "WEEKLY", "MONTHLY", "QUARTERLY", "ANNUAL")) {
                throw AppException("La recurrencia del costo no es válida.")
            }
        }
        val originalCurrency = request.originalCurrency.trim().uppercase().ifBlank { "USD" }
        if (originalCurrency !in setOf("USD", "VES")) throw AppException("La moneda original debe ser USD o VES.")
        val originalAmount = request.originalAmount?.let { MoneyMath.positive(BigDecimal.valueOf(it), "Monto original") }
            ?: if (originalCurrency == "VES") amountBs else amount
        val receiptPath = request.receiptPath?.trim()?.takeIf(String::isNotBlank)?.take(500)?.also {
            if (it.contains("..") || File(it).isAbsolute) throw AppException("La ruta del comprobante no es válida.")
        }
        val idempotencyKey = request.idempotencyKey?.trim()?.takeIf { it.isNotBlank() }?.take(120)
            ?: "BUDGET-$accountantId-${UUID.randomUUID()}"

        return database.transaction { connection ->
            ensureWalletV28Schema(connection)
            ensureAccountantWallet(connection, accountantId)
            val budgetContext = budgetPlanningService.prepareMovementContext(
                connection = connection,
                accountantId = accountantId,
                requestedType = requestedType,
                request = request,
                amount = amount
            )
            val type = budgetContext.normalizedType

            val duplicate = connection.prepareStatement(
                "SELECT 1 FROM movimientos_presupuestarios WHERE contador_id=? AND idempotency_key=? AND estado='COMPLETED'"
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setString(2, idempotencyKey)
                statement.executeQuery().use { it.next() }
            }
            if (duplicate) return@transaction accountantWalletSnapshot(connection, accountantId, bcv)

            val balanceBefore = connection.prepareStatement(
                "SELECT COALESCE(saldo_disponible_usd,0) FROM carteras_presupuesto_contador WHERE contador_id=? FOR UPDATE"
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw AppException("No fue posible abrir el presupuesto central.")
                    result.getBigDecimal(1)?.setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)
                        ?: BigDecimal.ZERO.setScale(MoneyMath.USD_SCALE)
                }
            }

            val reservedBefore = currentReservedBudget(connection, accountantId)
            when (type) {
                "RESERVE" -> {
                    val reservedAfter = reservedBefore.add(amount, MoneyMath.CONTEXT)
                    if (reservedAfter > balanceBefore) {
                        throw AppException("No puedes reservar más fondos que el saldo central disponible.")
                    }
                }
                "RELEASE" -> if (amount > reservedBefore) {
                    throw AppException("No puedes liberar más fondos de los que están reservados.")
                }
            }

            val balanceAfter = when (type) {
                "BANK_INCOME", "ADJUSTMENT_CREDIT" -> balanceBefore.add(amount, MoneyMath.CONTEXT)
                "INVENTORY_COST", "OPERATING_EXPENSE", "ADMINISTRATIVE_EXPENSE",
                "COMMERCIAL_EXPENSE", "FINANCIAL_EXPENSE", "EXTRAORDINARY_EXPENSE", "ADJUSTMENT_DEBIT" -> {
                    val spendable = balanceBefore.subtract(reservedBefore, MoneyMath.CONTEXT)
                        .coerceAtLeast(BigDecimal.ZERO)
                    if (amount > spendable) {
                        throw AppException("El presupuesto libre no tiene saldo suficiente. Los fondos reservados no pueden gastarse.")
                    }
                    balanceBefore.subtract(amount, MoneyMath.CONTEXT)
                }
                else -> balanceBefore
            }.setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)

            if (balanceAfter != balanceBefore) {
                val updated = connection.prepareStatement(
                    "UPDATE carteras_presupuesto_contador SET saldo_disponible_usd=?,updated_at=NOW() WHERE contador_id=?"
                ).use { statement ->
                    statement.setBigDecimal(1, balanceAfter)
                    statement.setLong(2, accountantId)
                    statement.executeUpdate()
                }
                if (updated != 1) throw AppException("El presupuesto cambió durante la operación. Actualiza e intenta nuevamente.")
            }

            val reference = "BUD-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8).uppercase()}"
            val movementId = connection.prepareStatement(
                """INSERT INTO movimientos_presupuestarios(
                       contador_id,tipo,monto_usd,tasa_bcv,monto_bs,saldo_antes_usd,
                       saldo_despues_usd,referencia,descripcion,categoria_gasto,categoria_costo_id,
                       centro_costo_id,periodo_presupuestario_id,partida_presupuestaria_id,compromiso_id,
                       responsable_usuario_id,proveedor,numero_factura,fecha_operacion,metodo_pago,
                       comportamiento_costo,recurrencia,referencia_proyecto,comprobante_path,
                       moneda_original,monto_original,idempotency_key,estado_control_presupuesto,estado
                   ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'COMPLETED')
                   RETURNING id"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setString(2, type)
                statement.setBigDecimal(3, amount)
                statement.setBigDecimal(4, MoneyMath.rate(bcv.rate))
                statement.setBigDecimal(5, amountBs)
                statement.setBigDecimal(6, balanceBefore)
                statement.setBigDecimal(7, balanceAfter)
                statement.setString(8, reference)
                statement.setString(9, description)
                statement.setString(10, expenseCategory)
                if (budgetContext.categoryId == null) statement.setNull(11, java.sql.Types.BIGINT) else statement.setLong(11, budgetContext.categoryId)
                if (budgetContext.costCenterId == null) statement.setNull(12, java.sql.Types.BIGINT) else statement.setLong(12, budgetContext.costCenterId)
                if (budgetContext.periodId == null) statement.setNull(13, java.sql.Types.BIGINT) else statement.setLong(13, budgetContext.periodId)
                if (budgetContext.budgetLineId == null) statement.setNull(14, java.sql.Types.BIGINT) else statement.setLong(14, budgetContext.budgetLineId)
                if (budgetContext.commitmentId == null) statement.setNull(15, java.sql.Types.BIGINT) else statement.setLong(15, budgetContext.commitmentId)
                if (request.responsibleUserId == null) statement.setNull(16, java.sql.Types.BIGINT) else statement.setLong(16, request.responsibleUserId)
                statement.setString(17, request.supplier?.trim()?.take(220))
                statement.setString(18, request.invoiceNumber?.trim()?.take(120))
                statement.setObject(19, transactionDate)
                statement.setString(20, paymentMethod)
                statement.setString(21, costBehavior)
                statement.setString(22, recurrence)
                statement.setString(23, request.projectReference?.trim()?.take(120))
                statement.setString(24, receiptPath)
                statement.setString(25, originalCurrency)
                statement.setBigDecimal(26, originalAmount)
                statement.setString(27, idempotencyKey)
                statement.setString(28, budgetContext.controlStatus)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw AppException("No fue posible registrar el movimiento presupuestario.")
                    result.getLong(1)
                }
            }
            budgetPlanningService.settleCommitment(connection, accountantId, budgetContext.commitmentId, movementId)
            audit(
                connection,
                accountantId,
                "BUDGET_MOVEMENT_CREATED",
                "movimientos_presupuestarios",
                reference,
                buildString {
                    append("$type")
                    (budgetContext.categoryName ?: expenseCategoryLabel(type, expenseCategory))?.let { append(" · ").append(it) }
                    append(" por US$ ${amount.toPlainString()}: $description")
                }
            )
            accountantWalletSnapshot(connection, accountantId, bcv)
        }
    }

    private fun currentReservedBudget(connection: Connection, accountantId: Long): BigDecimal =
        connection.prepareStatement(
            """SELECT COALESCE(SUM(CASE WHEN tipo='RESERVE' THEN monto_usd WHEN tipo='RELEASE' THEN -monto_usd ELSE 0 END),0)
               FROM movimientos_presupuestarios WHERE contador_id=? AND estado='COMPLETED'"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result ->
                val value = if (result.next()) result.getBigDecimal(1) ?: BigDecimal.ZERO else BigDecimal.ZERO
                if (value.signum() < 0) BigDecimal.ZERO.setScale(MoneyMath.USD_SCALE)
                else value.setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)
            }
        }

    private fun advancedBudgetSnapshot(
        connection: Connection,
        accountantId: Long,
        core: AccountantWalletCore,
        bcv: BcvRate
    ): AdvancedBudgetDto {
        data class BudgetMovementTotals(
            val bankAdjustments: BigDecimal,
            val inventoryCosts: BigDecimal,
            val operatingExpenses: BigDecimal,
            val administrativeExpenses: BigDecimal,
            val commercialExpenses: BigDecimal,
            val financialExpenses: BigDecimal,
            val extraordinaryExpenses: BigDecimal,
            val reserved: BigDecimal
        )
        val budgetMovementTotals = connection.prepareStatement(
            """SELECT
                   COALESCE(SUM(CASE
                       WHEN tipo IN ('BANK_INCOME','ADJUSTMENT_CREDIT') THEN monto_usd
                       WHEN tipo='ADJUSTMENT_DEBIT' THEN -monto_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN tipo='INVENTORY_COST' THEN monto_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN tipo='OPERATING_EXPENSE' THEN monto_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN tipo='ADMINISTRATIVE_EXPENSE' THEN monto_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN tipo='COMMERCIAL_EXPENSE' THEN monto_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN tipo='FINANCIAL_EXPENSE' THEN monto_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN tipo='EXTRAORDINARY_EXPENSE' THEN monto_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN tipo='RESERVE' THEN monto_usd WHEN tipo='RELEASE' THEN -monto_usd ELSE 0 END),0)
               FROM movimientos_presupuestarios
               WHERE contador_id=? AND estado='COMPLETED'"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result ->
                if (!result.next()) BudgetMovementTotals(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
                )
                else BudgetMovementTotals(
                    result.getBigDecimal(1) ?: BigDecimal.ZERO,
                    result.getBigDecimal(2) ?: BigDecimal.ZERO,
                    result.getBigDecimal(3) ?: BigDecimal.ZERO,
                    result.getBigDecimal(4) ?: BigDecimal.ZERO,
                    result.getBigDecimal(5) ?: BigDecimal.ZERO,
                    result.getBigDecimal(6) ?: BigDecimal.ZERO,
                    result.getBigDecimal(7) ?: BigDecimal.ZERO,
                    result.getBigDecimal(8)?.coerceAtLeast(BigDecimal.ZERO) ?: BigDecimal.ZERO
                )
            }
        }
        val adminAvailable = connection.prepareStatement(
            "SELECT COALESCE(SUM(saldo_disponible_usd),0) FROM carteras_credimpulso_admin"
        ).use { statement -> statement.executeQuery().use { result -> if (result.next()) result.getBigDecimal(1) ?: BigDecimal.ZERO else BigDecimal.ZERO } }
        val invested = connection.prepareStatement(
            "SELECT COALESCE(SUM(monto_usd),0) FROM asignaciones_presupuesto_admin WHERE contador_id=? AND estado='COMPLETED'"
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result -> if (result.next()) result.getBigDecimal(1) ?: BigDecimal.ZERO else BigDecimal.ZERO }
        }
        data class LoanTotals(val disbursed: BigDecimal, val active: Int, val overdue: Int, val paid: Int)
        val loans = connection.prepareStatement(
            """SELECT COALESCE(SUM(CASE WHEN status<>'CANCELLED' THEN principal_usd ELSE 0 END),0),
                      COUNT(*) FILTER (WHERE status IN ('ACTIVE','OVERDUE')),
                      COUNT(*) FILTER (WHERE status='OVERDUE'),
                      COUNT(*) FILTER (WHERE status='PAID')
               FROM prestamos_credito"""
        ).use { statement -> statement.executeQuery().use { result ->
            if (!result.next()) LoanTotals(BigDecimal.ZERO,0,0,0)
            else LoanTotals(result.getBigDecimal(1) ?: BigDecimal.ZERO,result.getInt(2),result.getInt(3),result.getInt(4))
        } }
        data class InstallmentTotals(val recovered: BigDecimal, val outstanding: BigDecimal, val overdue: BigDecimal, val due30: BigDecimal)
        val installments = connection.prepareStatement(
            """SELECT
                   COALESCE(SUM(CASE WHEN ci.status='PAID' THEN ci.amount_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN ci.status<>'PAID' THEN ci.amount_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN ci.status='OVERDUE' OR (ci.status='PENDING' AND ci.due_date<CURRENT_DATE) THEN ci.amount_usd ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN ci.status<>'PAID' AND ci.due_date<=CURRENT_DATE+30 THEN ci.amount_usd ELSE 0 END),0)
               FROM cuotas_credito ci
               JOIN prestamos_credito l ON l.id=ci.loan_id
               WHERE l.status<>'CANCELLED'"""
        ).use { statement -> statement.executeQuery().use { result ->
            if (!result.next()) InstallmentTotals(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)
            else InstallmentTotals(
                result.getBigDecimal(1) ?: BigDecimal.ZERO,
                result.getBigDecimal(2) ?: BigDecimal.ZERO,
                result.getBigDecimal(3) ?: BigDecimal.ZERO,
                result.getBigDecimal(4) ?: BigDecimal.ZERO
            )
        } }

        val bankBudget = MoneyMath.usd(core.initialBudgetUsd).add(budgetMovementTotals.bankAdjustments, MoneyMath.CONTEXT)
            .setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)
        val central = MoneyMath.usd(core.balanceUsd)
        val reserved = budgetMovementTotals.reserved.coerceAtLeast(BigDecimal.ZERO).setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)
        val calculated = BudgetMath.calculate(
            BudgetMathInput(
                bankBudgetUsd = bankBudget,
                centralAvailableUsd = central,
                administratorsAvailableUsd = adminAvailable,
                investedUsd = invested,
                operatingExpensesUsd = budgetMovementTotals.operatingExpenses,
                administrativeExpensesUsd = budgetMovementTotals.administrativeExpenses,
                loansDisbursedUsd = loans.disbursed,
                loansRecoveredUsd = installments.recovered,
                loansOutstandingUsd = installments.outstanding,
                overdueLoansUsd = installments.overdue,
                reservedUsd = reserved,
                expectedCollections30DaysUsd = installments.due30,
                inventoryCostsUsd = budgetMovementTotals.inventoryCosts,
                commercialExpensesUsd = budgetMovementTotals.commercialExpenses,
                financialExpensesUsd = budgetMovementTotals.financialExpenses,
                extraordinaryExpensesUsd = budgetMovementTotals.extraordinaryExpenses
            )
        )
        fun bs(value: BigDecimal): Double = MoneyMath.usdToVesOrZero(value.toDouble(), bcv.rate).toDouble()

        val budgetMovements = connection.prepareStatement(
            """SELECT m.id,m.tipo,m.monto_usd,m.monto_bs,m.tasa_bcv,m.saldo_antes_usd,m.saldo_despues_usd,
                      m.referencia,m.descripcion,m.categoria_gasto,c.codigo,c.nombre,c.grupo,
                      cc.id,cc.nombre,periodo_presupuestario_id,partida_presupuestaria_id,compromiso_id,
                      m.proveedor,m.numero_factura,m.fecha_operacion,m.metodo_pago,m.comportamiento_costo,
                      m.recurrencia,m.referencia_proyecto,m.comprobante_path,m.created_at
               FROM movimientos_presupuestarios m
               LEFT JOIN catalogo_costos c ON c.id=m.categoria_costo_id
               LEFT JOIN centros_costo cc ON cc.id=m.centro_costo_id
               WHERE m.contador_id=? AND m.estado='COMPLETED'
               ORDER BY m.created_at DESC LIMIT 100"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(BudgetMovementDto(
                    id = result.getLong(1),
                    type = result.getString(2).orEmpty(),
                    amountUsd = result.getBigDecimal(3)?.toDouble() ?: 0.0,
                    amountBs = result.getBigDecimal(4)?.toDouble() ?: 0.0,
                    bcvRate = result.getBigDecimal(5)?.toDouble() ?: 0.0,
                    balanceBeforeUsd = result.getBigDecimal(6)?.toDouble() ?: 0.0,
                    balanceAfterUsd = result.getBigDecimal(7)?.toDouble() ?: 0.0,
                    reference = result.getString(8).orEmpty(),
                    description = result.getString(9),
                    expenseCategory = result.getString(10),
                    expenseCategoryLabel = expenseCategoryLabel(result.getString(2).orEmpty(), result.getString(10)),
                    costCategoryCode = result.getString(11),
                    costCategoryLabel = result.getString(12),
                    costGroup = result.getString(13),
                    costCenterId = result.getLong(14).let { if (result.wasNull()) null else it },
                    costCenterName = result.getString(15),
                    budgetPeriodId = result.getLong(16).let { if (result.wasNull()) null else it },
                    budgetLineId = result.getLong(17).let { if (result.wasNull()) null else it },
                    commitmentId = result.getLong(18).let { if (result.wasNull()) null else it },
                    supplier = result.getString(19),
                    invoiceNumber = result.getString(20),
                    transactionDate = result.getObject(21, LocalDate::class.java)?.toString(),
                    paymentMethod = result.getString(22),
                    costBehavior = result.getString(23),
                    recurrence = result.getString(24),
                    projectReference = result.getString(25),
                    receiptPath = result.getString(26),
                    createdAt = result.getTimestamp(27)?.toInstant()?.toString().orEmpty()
                ))
            } }
        }

        fun expenseBreakdown(type: String, categories: Map<String, String>): List<ExpenseCategorySummaryDto> =
            connection.prepareStatement(
                """SELECT COALESCE(categoria_gasto,''), COALESCE(SUM(monto_usd),0)
                   FROM movimientos_presupuestarios
                   WHERE contador_id=? AND estado='COMPLETED' AND tipo=?
                   GROUP BY categoria_gasto
                   ORDER BY SUM(monto_usd) DESC"""
            ).use { statement ->
                statement.setLong(1, accountantId)
                statement.setString(2, type)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) {
                        val category = result.getString(1).orEmpty().ifBlank {
                            if (type == "OPERATING_EXPENSE") "OTHER_OPERATING" else "OTHER_ADMINISTRATIVE"
                        }
                        val amount = result.getBigDecimal(2) ?: BigDecimal.ZERO
                        add(ExpenseCategorySummaryDto(category, categories[category] ?: category, amount.toDouble(), bs(amount)))
                    }
                } }
            }
        val operatingBreakdown = expenseBreakdown("OPERATING_EXPENSE", OPERATING_EXPENSE_CATEGORIES)
        val administrativeBreakdown = expenseBreakdown("ADMINISTRATIVE_EXPENSE", ADMINISTRATIVE_EXPENSE_CATEGORIES)
        val detailedCostBreakdown = connection.prepareStatement(
            """SELECT c.codigo,c.nombre,c.grupo,COALESCE(SUM(m.monto_usd),0)
               FROM movimientos_presupuestarios m
               JOIN catalogo_costos c ON c.id=m.categoria_costo_id
               WHERE m.contador_id=? AND m.estado='COMPLETED'
                 AND m.tipo IN ('INVENTORY_COST','OPERATING_EXPENSE','ADMINISTRATIVE_EXPENSE',
                                'COMMERCIAL_EXPENSE','FINANCIAL_EXPENSE','EXTRAORDINARY_EXPENSE')
               GROUP BY c.codigo,c.nombre,c.grupo ORDER BY c.grupo,SUM(m.monto_usd) DESC"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val amount = result.getBigDecimal(4) ?: BigDecimal.ZERO
                    add(ExpenseCategorySummaryDto(
                        category = result.getString(1), label = result.getString(2),
                        amountUsd = amount.toDouble(), amountBs = bs(amount), group = result.getString(3)
                    ))
                }
            } }
        }

        return AdvancedBudgetDto(
            bankBudgetUsd = bankBudget.toDouble(), bankBudgetBs = bs(bankBudget),
            centralAvailableUsd = central.toDouble(), centralAvailableBs = bs(central),
            administratorsAvailableUsd = adminAvailable.toDouble(), administratorsAvailableBs = bs(adminAvailable),
            consolidatedAvailableUsd = calculated.consolidatedAvailableUsd.toDouble(), consolidatedAvailableBs = bs(calculated.consolidatedAvailableUsd),
            investedUsd = invested.toDouble(), investedBs = bs(invested),
            operatingExpensesUsd = budgetMovementTotals.operatingExpenses.toDouble(), operatingExpensesBs = bs(budgetMovementTotals.operatingExpenses),
            administrativeExpensesUsd = budgetMovementTotals.administrativeExpenses.toDouble(), administrativeExpensesBs = bs(budgetMovementTotals.administrativeExpenses),
            inventoryCostsUsd = budgetMovementTotals.inventoryCosts.toDouble(), inventoryCostsBs = bs(budgetMovementTotals.inventoryCosts),
            commercialExpensesUsd = budgetMovementTotals.commercialExpenses.toDouble(), commercialExpensesBs = bs(budgetMovementTotals.commercialExpenses),
            financialExpensesUsd = budgetMovementTotals.financialExpenses.toDouble(), financialExpensesBs = bs(budgetMovementTotals.financialExpenses),
            extraordinaryExpensesUsd = budgetMovementTotals.extraordinaryExpenses.toDouble(), extraordinaryExpensesBs = bs(budgetMovementTotals.extraordinaryExpenses),
            operatingExpenseBreakdown = operatingBreakdown, administrativeExpenseBreakdown = administrativeBreakdown,
            detailedCostBreakdown = detailedCostBreakdown,
            totalExpensesUsd = calculated.totalExpensesUsd.toDouble(), totalExpensesBs = bs(calculated.totalExpensesUsd),
            loansDisbursedUsd = loans.disbursed.toDouble(), loansDisbursedBs = bs(loans.disbursed),
            loansRecoveredUsd = installments.recovered.toDouble(), loansRecoveredBs = bs(installments.recovered),
            loansOutstandingUsd = installments.outstanding.toDouble(), loansOutstandingBs = bs(installments.outstanding),
            overdueLoansUsd = installments.overdue.toDouble(), overdueLoansBs = bs(installments.overdue),
            reservedUsd = reserved.toDouble(), reservedBs = bs(reserved),
            totalCommittedUsd = calculated.totalCommittedUsd.toDouble(), totalCommittedBs = bs(calculated.totalCommittedUsd),
            expectedCollections30DaysUsd = installments.due30.toDouble(), expectedCollections30DaysBs = bs(installments.due30),
            projectedAvailable30DaysUsd = calculated.projectedAvailable30DaysUsd.toDouble(), projectedAvailable30DaysBs = bs(calculated.projectedAvailable30DaysUsd),
            executionPercent = calculated.executionPercent.toDouble(),
            recoveryPercent = calculated.recoveryPercent.toDouble(),
            liquidityCoveragePercent = calculated.liquidityCoveragePercent.toDouble(),
            integrityDifferenceUsd = calculated.integrityDifferenceUsd.toDouble(), integrityDifferenceBs = bs(calculated.integrityDifferenceUsd.abs()),
            integrityStatus = calculated.integrityStatus,
            activeLoans = loans.active, overdueLoans = loans.overdue, paidLoans = loans.paid,
            calculatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            movements = budgetMovements
        )
    }

    private fun BigDecimal.coerceAtLeast(minimum: BigDecimal): BigDecimal = if (this < minimum) minimum else this


    fun adminCredimpulsoWallet(adminId: Long): AdminCredimpulsoWalletDto {
        val wallet = try {
            database.transaction { connection ->
                ensureAdminCredimpulsoWallet(connection, adminId)
                adminCredimpulsoWalletSnapshot(connection, adminId)
            }
        } catch (error: Exception) {
            // Una cartera todavía vacía nunca debe derribar la pantalla ni la sesión.
            logger.error("No fue posible sincronizar la cartera Crédito Kredi+ del administrador {}. Se devuelve saldo cero.", adminId, error)
            emptyAdminCredimpulsoWallet()
        }
        return walletWithBcv(wallet)
    }

    private fun emptyAdminCredimpulsoWallet(): AdminCredimpulsoWalletDto =
        AdminCredimpulsoWalletDto(
            walletAddress = "",
            balanceUsd = 0.0,
            totalTransferredUsd = 0.0,
            lendingCapacityUsd = 0.0,
            retainedBalanceUsd = 0.0,
            blocked = false,
            blockReason = null,
            evaluatedInstallments = 0,
            approvedInstallments = 0,
            evaluatedUsers = 0,
            evaluationLimit = 3,
            requiredApprovedInstallments = 2,
            movements = emptyList()
        )

    fun addAdminWalletFunds(
        adminId: Long,
        request: AdminWalletFundRequest
    ): AdminCredimpulsoWalletDto {
        throw ForbiddenException(
            "Los fondos de la cartera administrativa solo pueden ser asignados por el Contador desde el presupuesto central."
        )
    }

    fun transferFromAdminWallet(
        adminId: Long,
        request: AdminWalletTransferRequest
    ): AdminCredimpulsoWalletDto {
        require(request.amountUsd > 0.0) { "El monto a transferir debe ser mayor que cero." }

        val transferResult = database.transaction { connection ->
            ensureWalletV28Schema(connection)
            ensureAdminCredimpulsoWallet(connection, adminId)
            val idempotencyKey = request.idempotencyKey?.trim()?.takeIf { it.isNotBlank() }?.take(120)
                ?: "ADMIN-$adminId-${UUID.randomUUID()}"
            val duplicate = connection.prepareStatement(
                "SELECT related_user_id,reference FROM transacciones_carteras_continuas WHERE idempotency_key=? AND status='COMPLETED'"
            ).use { statement ->
                statement.setString(1, idempotencyKey)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getLong(1) to result.getString(2).orEmpty() else null
                }
            }
            if (duplicate != null) return@transaction WalletTransferNotificationContext(0L, duplicate.second, 0.0)
            val compliance = refreshAdminWalletCompliance(connection, adminId)
            if (compliance.blocked) {
                throw AppException(
                    compliance.reason
                        ?: "El saldo de la cartera está retenido hasta aprobar al menos 2 de 3 cuotas de usuarios diferentes."
                )
            }

            val recipientIdentifier = (request.recipientUsername ?: request.username).orEmpty().trim().removePrefix("@")
            val requestedUserId = request.userId
            if (recipientIdentifier.isBlank() && requestedUserId == null) {
                throw AppException("Ingresa el usuario o la dirección de cartera receptora.")
            }
            val looksLikeWalletAddress = recipientIdentifier.startsWith("ISU-", ignoreCase = true)
            if (recipientIdentifier.isNotBlank() && !looksLikeWalletAddress && !USERNAME_REGEX.matches(recipientIdentifier)) {
                throw AppException("Ingresa un usuario válido o una dirección de cartera ISU- válida.")
            }
            if (looksLikeWalletAddress && !recipientIdentifier.matches(Regex("(?i)^ISU-[A-F0-9]{16,64}$"))) {
                throw AppException("La dirección de cartera indicada no es válida.")
            }

            val recipientSql = if (recipientIdentifier.isNotBlank()) {
                """SELECT u.id,u.username,
                          COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.username),
                          u.role,u.account_status,u.verification_status
                   FROM usuarios u
                   LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                   LEFT JOIN cuentas_credito cc ON cc.user_id=u.id
                   WHERE LOWER(u.username)=LOWER(?) OR UPPER(COALESCE(cc.wallet_address,''))=UPPER(?)
                   FOR UPDATE OF u"""
            } else {
                """SELECT u.id,u.username,
                          COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.username),
                          u.role,u.account_status,u.verification_status
                   FROM usuarios u
                   LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                   WHERE u.id=?
                   FOR UPDATE OF u"""
            }
            val recipient = connection.prepareStatement(recipientSql).use { statement ->
                if (recipientIdentifier.isNotBlank()) {
                    statement.setString(1, recipientIdentifier)
                    statement.setString(2, recipientIdentifier)
                } else statement.setLong(1, requestedUserId ?: throw AppException("Selecciona el usuario receptor."))
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        throw AppException("No existe un usuario activo asociado al usuario o dirección de cartera indicada.")
                    }
                    val role = Roles.canonical(result.getString("role"))
                    val accountStatus = result.getString("account_status")
                    val verificationStatus = result.getString("verification_status")
                    if (role != Roles.BENEFICIARY) {
                        throw AppException("El usuario indicado no pertenece a una billetera Crédito Kredi+ de usuario.")
                    }
                    if (accountStatus != "ACTIVE" || verificationStatus != "VERIFIED") {
                        throw AppException("La cuenta asociada a ese usuario todavía no está activa y verificada.")
                    }
                    Triple(result.getLong("id"), result.getString("username").orEmpty(), result.getString(3))
                }
            }

            // La transferencia es interna hacia la cartera Credimpulso del usuario.
            // La cuenta bancaria de desembolso sigue disponible para desembolsos externos,
            // pero no bloquea un movimiento interno de saldo.
            ensureCreditAccount(connection, recipient.first)

            val adminBalanceBefore = connection.prepareStatement(
                "SELECT saldo_disponible_usd FROM carteras_credimpulso_admin WHERE admin_id=? FOR UPDATE"
            ).use { statement ->
                statement.setLong(1, adminId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw AppException("No fue posible abrir la cartera del administrador.")
                    result.getBigDecimal(1).toDouble()
                }
            }

            if (MoneyMath.greaterThanUsd(request.amountUsd, adminBalanceBefore)) {
                throw AppException("La cartera no tiene saldo suficiente para realizar esta transferencia.")
            }

            lockCreditAccount(connection, recipient.first)
            val userAccountBefore = creditAccountSnapshot(connection, recipient.first)
            val userCreditBefore = userAccountBefore.creditLimitUsd to userAccountBefore.usedUsd

            val adminBalanceAfter = MoneyMath.subtractUsd(adminBalanceBefore, request.amountUsd).toDouble()
            val userLimitAfter = MoneyMath.addUsd(userCreditBefore.first, request.amountUsd).toDouble()
            val userAvailableBefore = MoneyMath.subtractUsd(userCreditBefore.first, userCreditBefore.second)
                .max(MoneyMath.ZERO).toDouble()
            val userAvailableAfter = MoneyMath.subtractUsd(userLimitAfter, userCreditBefore.second)
                .max(MoneyMath.ZERO).toDouble()
            val reference = "CRED-${System.currentTimeMillis()}"

            connection.prepareStatement(
                """UPDATE carteras_credimpulso_admin
                   SET saldo_disponible_usd=?,
                       total_transferido_usd=total_transferido_usd+?,
                       updated_at=NOW()
                   WHERE admin_id=?"""
            ).use { statement ->
                statement.setBigDecimal(1, MoneyMath.usd(adminBalanceAfter, "Saldo administrativo"))
                statement.setBigDecimal(2, MoneyMath.usd(request.amountUsd, "Monto solicitado"))
                statement.setLong(3, adminId)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """UPDATE cuentas_credito
                   SET credit_limit_usd=?,updated_at=NOW()
                   WHERE user_id=?"""
            ).use { statement ->
                statement.setBigDecimal(1, MoneyMath.usd(userLimitAfter, "Límite del usuario"))
                statement.setLong(2, recipient.first)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """INSERT INTO movimientos_cartera_credimpulso(
                       admin_id,user_id,tipo,monto_usd,saldo_antes_usd,saldo_despues_usd,referencia,descripcion
                   ) VALUES (?,?,'TRANSFERENCIA',?,?,?,?,?)"""
            ).use { statement ->
                statement.setLong(1, adminId)
                statement.setLong(2, recipient.first)
                statement.setBigDecimal(3, MoneyMath.usd(request.amountUsd, "Monto solicitado"))
                statement.setBigDecimal(4, MoneyMath.usd(adminBalanceBefore, "Saldo administrativo anterior"))
                statement.setBigDecimal(5, MoneyMath.usd(adminBalanceAfter, "Saldo administrativo"))
                statement.setString(6, reference)
                statement.setString(7, request.description.trim().take(500))
                statement.executeUpdate()
            }

            val adminWalletAddress = connection.prepareStatement(
                "SELECT COALESCE(wallet_address,'') FROM carteras_credimpulso_admin WHERE admin_id=?"
            ).use { statement ->
                statement.setLong(1, adminId)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1).orEmpty() else "" }
            }
            val userWalletAddress = connection.prepareStatement(
                "SELECT COALESCE(wallet_address,'') FROM cuentas_credito WHERE user_id=?"
            ).use { statement ->
                statement.setLong(1, recipient.first)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1).orEmpty() else "" }
            }
            connection.prepareStatement(
                """INSERT INTO transacciones_carteras_continuas(
                       idempotency_key,operation_type,source_wallet_address,destination_wallet_address,
                       amount_usd,status,reference,actor_user_id,related_user_id,metadata,completed_at
                   ) VALUES (?,'ADMIN_TO_USER_TRANSFER',?,?,?,'COMPLETED',?,?,?,?::jsonb,NOW())
                   ON CONFLICT(idempotency_key) DO NOTHING"""
            ).use { statement ->
                statement.setString(1, idempotencyKey)
                statement.setString(2, adminWalletAddress)
                statement.setString(3, userWalletAddress)
                statement.setBigDecimal(4, MoneyMath.usd(request.amountUsd, "Monto solicitado"))
                statement.setString(5, reference)
                statement.setLong(6, adminId)
                statement.setLong(7, recipient.first)
                statement.setString(8, notificationPayloadJson(mapOf(
                    "description" to request.description.trim().take(500),
                    "recipientUsername" to recipient.second
                )))
                statement.executeUpdate()
            }

            recordCredimpulsoTransaction(
                connection = connection,
                userId = recipient.first,
                type = "CREDIT_TRANSFER",
                amountUsd = request.amountUsd,
                amountBs = 0.0,
                bcvRate = 0.0,
                balanceBeforeUsd = userAvailableBefore,
                balanceAfterUsd = userAvailableAfter,
                description = request.description.trim().ifBlank { "Saldo recibido desde la cartera Crédito Kredi+" },
                performedBy = adminId
            )

            audit(
                connection,
                adminId,
                "ADMIN_CREDIT_WALLET_TRANSFER",
                "CREDIT_WALLET",
                recipient.first.toString(),
                "US$ ${request.amountUsd} · $reference"
            )

            WalletTransferNotificationContext(recipient.first, reference, userAvailableAfter)
        }

        if (transferResult.userId > 0) runCatching {
            val notification = WalletNotificationFactory.received(
                amountUsd = request.amountUsd,
                availableBalanceUsd = transferResult.availableBalanceUsd,
                reference = transferResult.reference
            )
            notifyUsers(
                listOf(transferResult.userId),
                notification.title,
                notification.body,
                notification.type,
                notification.data
            )
        }.onFailure { error ->
            logger.warn(
                "La transferencia se completó, pero no fue posible enviar la notificación al usuario {}.",
                transferResult.userId,
                error
            )
        }

        return adminCredimpulsoWallet(adminId)
    }

    private fun ensureAdminCredimpulsoWallet(connection: Connection, adminId: Long) {
        ensureWalletV27Schema(connection)
        connection.prepareStatement(
            """INSERT INTO carteras_credimpulso_admin(admin_id)
               SELECT id FROM usuarios
               WHERE id=? AND UPPER(role) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
               ON CONFLICT(admin_id) DO NOTHING"""
        ).use { statement ->
            statement.setLong(1, adminId)
            statement.executeUpdate()
        }

        connection.prepareStatement(
            """UPDATE carteras_credimpulso_admin
               SET wallet_address=COALESCE(NULLIF(wallet_address,''),
                   'ISA-' || UPPER(SUBSTRING(MD5('ADMIN:' || admin_id::TEXT),1,32))),
                   updated_at=NOW()
               WHERE admin_id=?"""
        ).use { statement ->
            statement.setLong(1, adminId)
            statement.executeUpdate()
        }

        val exists = connection.prepareStatement(
            "SELECT 1 FROM carteras_credimpulso_admin WHERE admin_id=?"
        ).use { statement ->
            statement.setLong(1, adminId)
            statement.executeQuery().use { result -> result.next() }
        }
        if (!exists) throw ForbiddenException("La cartera Crédito Kredi+ está disponible únicamente para administradores.")
    }

    private fun adminCredimpulsoWalletSnapshot(
        connection: Connection,
        adminId: Long
    ): AdminCredimpulsoWalletDto {
        val summary = connection.prepareStatement(
            "SELECT saldo_disponible_usd,total_transferido_usd,COALESCE(wallet_address,'') FROM carteras_credimpulso_admin WHERE admin_id=?"
        ).use { statement ->
            statement.setLong(1, adminId)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    Triple(result.getBigDecimal(1).toDouble(), result.getBigDecimal(2).toDouble(), result.getString(3).orEmpty())
                } else {
                    Triple(0.0, 0.0, "")
                }
            }
        }

        // Una instalación heredada puede tardar un despliegue en incorporar las columnas
        // de cumplimiento. La lectura del saldo no debe fallar por ello.
        val compliance = safeAdminWalletCompliance(connection, adminId)
        val movements = safeAdminWalletMovements(connection, adminId)
        val retainedBalance = if (compliance.blocked) summary.first else 0.0

        return AdminCredimpulsoWalletDto(
            walletAddress = summary.third,
            balanceUsd = summary.first,
            totalTransferredUsd = summary.second,
            lendingCapacityUsd = if (compliance.blocked) 0.0 else summary.first,
            retainedBalanceUsd = retainedBalance,
            blocked = compliance.blocked,
            blockReason = compliance.reason,
            evaluatedInstallments = compliance.evaluatedInstallments,
            approvedInstallments = compliance.approvedInstallments,
            evaluatedUsers = compliance.evaluatedUsers,
            evaluationLimit = 3,
            requiredApprovedInstallments = 2,
            movements = movements
        )
    }

    private fun safeAdminWalletCompliance(
        connection: Connection,
        adminId: Long
    ): AdminWalletCompliance {
        val savepoint = connection.setSavepoint("wallet_compliance_$adminId")
        return try {
            refreshAdminWalletCompliance(connection, adminId).also {
                runCatching { connection.releaseSavepoint(savepoint) }
            }
        } catch (error: Exception) {
            runCatching { connection.rollback(savepoint) }
            runCatching { connection.releaseSavepoint(savepoint) }
            logger.warn(
                "No se pudo actualizar el control de cuotas de la cartera {}. Se mantiene disponible la lectura del saldo.",
                adminId,
                error
            )
            AdminWalletCompliance(
                blocked = false,
                reason = null,
                evaluatedInstallments = 0,
                approvedInstallments = 0,
                evaluatedUsers = 0
            )
        }
    }

    private fun safeAdminWalletMovements(
        connection: Connection,
        adminId: Long
    ): List<AdminCredimpulsoWalletMovementDto> {
        val savepoint = connection.setSavepoint("wallet_movements_$adminId")
        return try {
            connection.prepareStatement(
                """SELECT m.id,m.tipo,m.monto_usd,m.saldo_antes_usd,m.saldo_despues_usd,m.user_id,
                          COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),u.email),
                          m.referencia,m.descripcion,m.created_at
                   FROM movimientos_cartera_credimpulso m
                   LEFT JOIN usuarios u ON u.id=m.user_id
                   LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                   WHERE m.admin_id=?
                   ORDER BY m.created_at DESC
                   LIMIT 100"""
            ).use { statement ->
                statement.setLong(1, adminId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                AdminCredimpulsoWalletMovementDto(
                                    id = result.getLong(1),
                                    type = result.getString(2),
                                    amountUsd = result.getBigDecimal(3).toDouble(),
                                    balanceBeforeUsd = result.getBigDecimal(4).toDouble(),
                                    balanceAfterUsd = result.getBigDecimal(5).toDouble(),
                                    userId = result.getLong(6).takeUnless { result.wasNull() },
                                    userName = result.getString(7),
                                    reference = result.getString(8),
                                    description = result.getString(9),
                                    createdAt = result.getTimestamp(10).toInstant().toString()
                                )
                            )
                        }
                    }
                }
            }.also {
                runCatching { connection.releaseSavepoint(savepoint) }
            }
        } catch (error: Exception) {
            runCatching { connection.rollback(savepoint) }
            runCatching { connection.releaseSavepoint(savepoint) }
            logger.warn("No se pudo leer el historial de la cartera {}. Se devuelve historial vacío.", adminId, error)
            emptyList()
        }
    }

    /**
     * Regla global de cartera (Cambios 23): se revisa la cuota más reciente ya exigible
     * de cada usuario que recibió presupuesto desde la cartera del administrador. Cuando
     * existen al menos 3 usuarios diferentes, las 3 cuotas más recientes forman la ventana
     * de control. Si menos de 2 están aprobadas/pagadas, todo el saldo queda retenido.
     */
    private fun refreshAdminWalletCompliance(
        connection: Connection,
        adminId: Long
    ): AdminWalletCompliance {
        val metrics = connection.prepareStatement(
            """
            WITH usuarios_financiados AS (
                SELECT DISTINCT user_id
                FROM movimientos_cartera_credimpulso
                WHERE admin_id=? AND tipo='TRANSFERENCIA' AND user_id IS NOT NULL
            ), cuotas_exigibles AS (
                SELECT DISTINCT ON (cl.user_id)
                       cl.user_id,ci.id,ci.status,ci.due_date
                FROM usuarios_financiados uf
                JOIN prestamos_credito cl ON cl.user_id=uf.user_id AND cl.status<>'CANCELLED'
                JOIN cuotas_credito ci ON ci.loan_id=cl.id
                WHERE ci.due_date<=CURRENT_DATE
                ORDER BY cl.user_id,ci.due_date DESC,ci.installment_number DESC,ci.id DESC
            ), ventana_control AS (
                SELECT user_id,id,status,due_date
                FROM cuotas_exigibles
                ORDER BY due_date DESC,id DESC
                LIMIT 3
            )
            SELECT COUNT(*)::INT AS cuotas_evaluadas,
                   COUNT(*) FILTER (WHERE status='PAID')::INT AS cuotas_aprobadas,
                   COUNT(DISTINCT user_id)::INT AS usuarios_evaluados
            FROM ventana_control
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, adminId)
            statement.executeQuery().use { result ->
                result.next()
                Triple(
                    result.getInt("cuotas_evaluadas"),
                    result.getInt("cuotas_aprobadas"),
                    result.getInt("usuarios_evaluados")
                )
            }
        }

        val blocked = shouldBlockAdminWallet(
            evaluatedInstallments = metrics.first,
            approvedInstallments = metrics.second,
            evaluatedUsers = metrics.third
        )
        val reason = if (blocked) {
            "Saldo retenido: se aprobaron ${metrics.second} de 3 cuotas correspondientes a 3 usuarios diferentes. Se requieren al menos 2 cuotas aprobadas para liberar el presupuesto."
        } else null

        connection.prepareStatement(
            """
            UPDATE carteras_credimpulso_admin
            SET saldo_bloqueado=?,
                motivo_bloqueo=?,
                cuotas_evaluadas=?,
                cuotas_aprobadas=?,
                usuarios_evaluados=?,
                bloqueado_desde=CASE
                    WHEN ? AND NOT saldo_bloqueado THEN NOW()
                    WHEN NOT ? THEN NULL
                    ELSE bloqueado_desde
                END,
                updated_at=NOW()
            WHERE admin_id=?
            """.trimIndent()
        ).use { statement ->
            statement.setBoolean(1, blocked)
            if (reason == null) statement.setNull(2, java.sql.Types.VARCHAR) else statement.setString(2, reason)
            statement.setInt(3, metrics.first)
            statement.setInt(4, metrics.second)
            statement.setInt(5, metrics.third)
            statement.setBoolean(6, blocked)
            statement.setBoolean(7, blocked)
            statement.setLong(8, adminId)
            statement.executeUpdate()
        }

        return AdminWalletCompliance(
            blocked = blocked,
            reason = reason,
            evaluatedInstallments = metrics.first,
            approvedInstallments = metrics.second,
            evaluatedUsers = metrics.third
        )
    }

    fun refreshAllCreditHistories() {
        database.transaction { connection ->
            val userIds = connection.prepareStatement("SELECT user_id FROM cuentas_credito ORDER BY user_id").use { statement ->
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
            }
            userIds.forEach { userId ->
                withSavepointFallback(
                    connection = connection,
                    fallback = false,
                    context = "actualizar historial crediticio de userId=$userId"
                ) {
                    refreshCreditStatuses(connection, userId)
                    refreshCredimpulsoLevel(connection, userId)
                    true
                }
            }
        }
    }

    fun adminCreditLoans(): List<AdminCreditLoanDto> {
        runCatching { refreshAllCreditHistories() }
            .onFailure { logger.warn("El historial crediticio se actualizará después; los préstamos seguirán visibles.", it) }
        return database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT cl.id,cl.user_id,
                   COALESCE(NULLIF(up.full_name,''),u.email),
                   COALESCE(i.invoice_number,cl.invoice_number,'CRED-' || cl.id::text),
                   cl.principal_usd,cl.principal_bs,cl.bcv_rate,cl.status,cl.created_at
            FROM prestamos_credito cl
            JOIN usuarios u ON u.id=cl.user_id
            LEFT JOIN perfiles_usuario up ON up.user_id=cl.user_id
            LEFT JOIN facturas i ON i.order_id=cl.order_id
            ORDER BY cl.created_at DESC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val loanId = result.getLong(1)
                    add(
                        AdminCreditLoanDto(
                            id = loanId,
                            userId = result.getLong(2),
                            customerName = result.getString(3),
                            invoiceNumber = result.getString(4),
                            principalUsd = result.getBigDecimal(5).toDouble(),
                            principalBs = result.getBigDecimal(6).toDouble(),
                            bcvRate = result.getBigDecimal(7).toDouble(),
                            status = result.getString(8),
                            createdAt = result.getObject(9, OffsetDateTime::class.java).toString(),
                            installments = creditInstallmentsForLoan(connection, loanId, result.getString(4))
                        )
                    )
                }
            } }
        }
        }
    }

    fun credimpulsoTransactions(userId: Long): List<CredimpulsoTransactionDto> =
        database.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT ct.id,ct.user_id,
                          COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.email),
                          ct.transaction_type,ct.amount_usd,ct.amount_bs,ct.bcv_rate,
                          ct.balance_before_usd,ct.balance_after_usd,ct.description,ct.loan_id,ct.installment_id,ct.created_at,COALESCE(i.invoice_number,cl.invoice_number)
                   FROM transacciones_credimpulso ct
                   JOIN usuarios u ON u.id=ct.user_id
                   LEFT JOIN perfiles_usuario up ON up.user_id=ct.user_id
                   LEFT JOIN prestamos_credito cl ON cl.id=ct.loan_id
                   LEFT JOIN facturas i ON i.order_id=COALESCE(ct.order_id,cl.order_id)
                   WHERE ct.user_id=?
                     AND UPPER(COALESCE(u.role,''))='BENEFICIARY'
                     AND UPPER(COALESCE(u.account_kind,'BENEFICIARY'))='BENEFICIARY'
                   ORDER BY ct.created_at DESC
                   LIMIT 300"""
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                CredimpulsoTransactionDto(
                                    id = result.getLong(1),
                                    userId = result.getLong(2),
                                    customerName = result.getString(3),
                                    transactionType = result.getString(4),
                                    amountUsd = result.getBigDecimal(5).toDouble(),
                                    amountBs = result.getBigDecimal(6).toDouble(),
                                    bcvRate = result.getBigDecimal(7).toDouble(),
                                    balanceBeforeUsd = result.getBigDecimal(8).toDouble(),
                                    balanceAfterUsd = result.getBigDecimal(9).toDouble(),
                                    description = result.getString(10),
                                    loanId = result.getObject(11)?.let { result.getLong(11) },
                                    installmentId = result.getObject(12)?.let { result.getLong(12) },
                                    createdAt = result.getObject(13, OffsetDateTime::class.java).toString(),
                                    invoiceNumber = result.getString(14)
                                )
                            )
                        }
                    }
                }
            }
        }

    fun adminCredimpulsoTransactions(): List<CredimpulsoTransactionDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT ct.id,ct.user_id,
                      COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),up.full_name,u.email),
                      ct.transaction_type,ct.amount_usd,ct.amount_bs,ct.bcv_rate,
                      ct.balance_before_usd,ct.balance_after_usd,ct.description,ct.loan_id,ct.installment_id,ct.created_at,COALESCE(i.invoice_number,cl.invoice_number)
               FROM transacciones_credimpulso ct
               JOIN usuarios u ON u.id=ct.user_id
               LEFT JOIN perfiles_usuario up ON up.user_id=ct.user_id
               LEFT JOIN prestamos_credito cl ON cl.id=ct.loan_id
               LEFT JOIN facturas i ON i.order_id=COALESCE(ct.order_id,cl.order_id)
               ORDER BY ct.created_at DESC
               LIMIT 500"""
        ).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(
                    CredimpulsoTransactionDto(
                        id=result.getLong(1), userId=result.getLong(2), customerName=result.getString(3),
                        transactionType=result.getString(4), amountUsd=result.getBigDecimal(5).toDouble(), amountBs=result.getBigDecimal(6).toDouble(),
                        bcvRate=result.getBigDecimal(7).toDouble(), balanceBeforeUsd=result.getBigDecimal(8).toDouble(), balanceAfterUsd=result.getBigDecimal(9).toDouble(),
                        description=result.getString(10), loanId=result.getObject(11)?.let { result.getLong(11) }, installmentId=result.getObject(12)?.let { result.getLong(12) },
                        createdAt=result.getObject(13, OffsetDateTime::class.java).toString(), invoiceNumber=result.getString(14)
                    )
                )
            } }
        }
    }

    /**
     * Fuente pública del visor de trazabilidad de Kredi+.
     *
     * Importante: la aplicación todavía no usa una blockchain pública. Esta consulta
     * consolida el libro contable interno de PostgreSQL sin revelar datos personales.
     */
    private fun publicLedgerIntegrityDigest(vararg values: Any?): String {
        val canonical = values.joinToString("|") { value -> value?.toString()?.trim().orEmpty() }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun publicLedgerTransactions(
        requestedPage: Int,
        requestedPageSize: Int,
        search: String?,
        operationType: String?,
        status: String?,
        wallet: String?,
        fromDate: String? = null,
        toDate: String? = null,
        sort: String? = null
    ): PublicLedgerPageDto = database.dataSource.connection.use { connection ->
        ensureWalletV28Schema(connection)

        val page = requestedPage.coerceIn(1, 1_000_000)
        val pageSize = requestedPageSize.coerceIn(1, 250)
        val offset = (page - 1) * pageSize

        val ledgerCte = """
            WITH public_ledger AS (
                SELECT
                    (1000000000::BIGINT + cr.id)::BIGINT AS sequence_number,
                    COALESCE(NULLIF(cr.wallet_transaction_id, ''), 'ISL-CR-' || cr.id::TEXT)::TEXT AS transaction_id,
                    COALESCE(NULLIF(cr.wallet_reference, ''), 'CREDIT-' || cr.id::TEXT)::TEXT AS reference,
                    'CREDIT_APPROVAL'::TEXT AS operation_type,
                    cr.status::TEXT AS status,
                    NULL::TEXT AS source_wallet_address,
                    NULL::TEXT AS destination_wallet_address,
                    cr.requested_amount_usd::NUMERIC AS amount_usd,
                    cr.approved_amount_bs::NUMERIC AS amount_bs,
                    cr.approval_bcv_rate::NUMERIC AS bcv_rate,
                    'Aprobación de plan de compra Kredi+'::TEXT AS description,
                    cr.created_at::TIMESTAMPTZ AS created_at,
                    COALESCE(cr.reviewed_at, cr.created_at)::TIMESTAMPTZ AS completed_at,
                    'CREDIT_APPROVALS'::TEXT AS ledger_source
                FROM solicitudes_credito cr
                LEFT JOIN cuentas_credito cc ON cc.user_id = cr.user_id
                WHERE cr.status = 'APPROVED'

                UNION ALL

                SELECT
                    (2000000000::BIGINT + ct.id)::BIGINT AS sequence_number,
                    ('ISL-CT-' || ct.id::TEXT)::TEXT AS transaction_id,
                    COALESCE(NULLIF(i.invoice_number, ''), 'CRED-' || ct.id::TEXT)::TEXT AS reference,
                    ct.transaction_type::TEXT AS operation_type,
                    'COMPLETED'::TEXT AS status,
                    NULL::TEXT AS source_wallet_address,
                    NULL::TEXT AS destination_wallet_address,
                    ct.amount_usd::NUMERIC AS amount_usd,
                    ct.amount_bs::NUMERIC AS amount_bs,
                    ct.bcv_rate::NUMERIC AS bcv_rate,
                    CASE ct.transaction_type
                        WHEN 'PURCHASE' THEN 'Compra mediante plan Kredi+'
                        WHEN 'INSTALLMENT_PAYMENT' THEN 'Cuota registrada en Kredi+'
                        WHEN 'REFUND' THEN 'Reverso de compra Kredi+'
                        WHEN 'ADJUSTMENT' THEN 'Ajuste contable Crédito Kredi+'
                        ELSE 'Actividad Kredi+'
                    END::TEXT AS description,
                    ct.created_at::TIMESTAMPTZ AS created_at,
                    ct.created_at::TIMESTAMPTZ AS completed_at,
                    'KREDI_ACTIVITY'::TEXT AS ledger_source
                FROM transacciones_credimpulso ct
                LEFT JOIN cuentas_credito cc ON cc.user_id = ct.user_id
                LEFT JOIN carteras_credimpulso_admin aw ON aw.admin_id = ct.performed_by
                LEFT JOIN prestamos_credito cl ON cl.id = ct.loan_id
                LEFT JOIN facturas i ON i.order_id = COALESCE(ct.order_id, cl.order_id)
                WHERE ct.transaction_type NOT IN ('CREDIT_TRANSFER', 'CREDIT_REQUEST_APPROVAL')
            )
        """.trimIndent()

        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<String>()

        search?.trim()?.take(120)?.takeIf { it.isNotBlank() }?.let { value ->
            conditions += """LOWER(CONCAT_WS(' ', transaction_id, reference, operation_type, status, ledger_source)) LIKE ?"""
            parameters += "%${value.lowercase()}%"
        }
        operationType?.trim()?.take(60)?.takeIf { it.isNotBlank() && !it.equals("ALL", true) }?.let { value ->
            conditions += "UPPER(operation_type) = UPPER(?)"
            parameters += value
        }
        status?.trim()?.take(30)?.takeIf { it.isNotBlank() && !it.equals("ALL", true) }?.let { value ->
            conditions += "UPPER(status) = UPPER(?)"
            parameters += value
        }
        wallet?.trim()?.take(80)?.takeIf { it.isNotBlank() }?.let { value ->
            conditions += "1=0" // filtro heredado de cartera: ya no se expone en el visor público
        }
        fromDate?.trim()?.take(10)?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        }?.let { value ->
            conditions += "created_at >= ?::date"
            parameters += value.toString()
        }
        toDate?.trim()?.take(10)?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        }?.let { value ->
            conditions += "created_at < (?::date + INTERVAL '1 day')"
            parameters += value.toString()
        }

        val whereClause = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val orderClause = when (sort?.trim()?.uppercase()) {
            "OLDEST" -> "created_at ASC, sequence_number ASC"
            "AMOUNT_DESC" -> "amount_usd DESC, created_at DESC"
            "AMOUNT_ASC" -> "amount_usd ASC, created_at DESC"
            else -> "created_at DESC, sequence_number DESC"
        }

        fun bindFilters(statement: java.sql.PreparedStatement, startIndex: Int = 1): Int {
            var index = startIndex
            parameters.forEach { parameter ->
                statement.setString(index++, parameter)
            }
            return index
        }

        val totalItems = connection.prepareStatement(
            "$ledgerCte SELECT COUNT(*) FROM public_ledger$whereClause"
        ).use { statement ->
            bindFilters(statement)
            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1)
            }
        }

        val transactions = connection.prepareStatement(
            """$ledgerCte
               SELECT sequence_number,transaction_id,reference,operation_type,status,
                      source_wallet_address,destination_wallet_address,amount_usd,amount_bs,bcv_rate,
                      description,created_at,completed_at,ledger_source
               FROM public_ledger$whereClause
               ORDER BY $orderClause
               LIMIT ? OFFSET ?"""
        ).use { statement ->
            var index = bindFilters(statement)
            statement.setInt(index++, pageSize)
            statement.setInt(index, offset)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val sequenceNumber = result.getLong(1)
                        val transactionId = result.getString(2)
                        val reference = result.getString(3)
                        val operationTypeValue = result.getString(4)
                        val statusValue = result.getString(5)
                        val sourceWallet = result.getString(6)
                        val destinationWallet = result.getString(7)
                        val amountUsd = result.getBigDecimal(8)?.toDouble() ?: 0.0
                        val amountBs = result.getBigDecimal(9)?.toDouble()
                        val bcvRate = result.getBigDecimal(10)?.toDouble()
                        val description = result.getString(11)
                        val createdAt = result.getObject(12, OffsetDateTime::class.java).toString()
                        val completedAt = result.getObject(13, OffsetDateTime::class.java)?.toString()
                        val ledgerSource = result.getString(14)
                        val confirmationCount = if (statusValue.uppercase() in setOf("COMPLETED", "APPROVED", "PAID")) 1 else 0
                        add(
                            PublicLedgerTransactionDto(
                                sequenceNumber = sequenceNumber,
                                transactionId = transactionId,
                                reference = reference,
                                operationType = operationTypeValue,
                                status = statusValue,
                                sourceWalletAddress = sourceWallet,
                                destinationWalletAddress = destinationWallet,
                                amountUsd = amountUsd,
                                amountBs = amountBs,
                                bcvRate = bcvRate,
                                description = description,
                                createdAt = createdAt,
                                completedAt = completedAt,
                                ledgerSource = ledgerSource,
                                confirmationCount = confirmationCount,
                                integrityStatus = "CONSISTENT",
                                integrityHash = publicLedgerIntegrityDigest(
                                    sequenceNumber, transactionId, reference, operationTypeValue, statusValue,
                                    sourceWallet, destinationWallet, amountUsd, amountBs, bcvRate,
                                    description, createdAt, completedAt, ledgerSource
                                )
                            )
                        )
                    }
                }
            }
        }

        val stats = connection.prepareStatement(
            """$ledgerCte
               SELECT
                   COUNT(*)::BIGINT,
                   COALESCE(SUM(amount_usd), 0)::NUMERIC,
                   COALESCE(SUM(amount_bs), 0)::NUMERIC,
                   COALESCE(MAX(sequence_number), 0)::BIGINT,
                   MAX(created_at),
                   0::BIGINT,
                   COUNT(*) FILTER (WHERE UPPER(status) IN ('COMPLETED','APPROVED','PAID'))::BIGINT,
                   COUNT(*) FILTER (WHERE UPPER(status) IN ('PENDING','PROCESSING','REVIEW'))::BIGINT,
                   COUNT(*) FILTER (WHERE UPPER(status) IN ('FAILED','REJECTED','CANCELLED'))::BIGINT,
                   COUNT(*) FILTER (WHERE created_at >= CURRENT_DATE)::BIGINT
               FROM public_ledger"""
        ).use { statement ->
            statement.executeQuery().use { result ->
                result.next()
                PublicLedgerStatsDto(
                    totalTransactions = result.getLong(1),
                    totalVolumeUsd = result.getBigDecimal(2)?.toDouble() ?: 0.0,
                    totalVolumeBs = result.getBigDecimal(3)?.toDouble() ?: 0.0,
                    latestSequence = result.getLong(4),
                    latestTransactionAt = result.getObject(5, OffsetDateTime::class.java)?.toString(),
                    walletCount = result.getLong(6),
                    smartContractCount = 0,
                    confirmedCount = result.getLong(7),
                    pendingCount = result.getLong(8),
                    rejectedCount = result.getLong(9),
                    todayCount = result.getLong(10)
                )
            }
        }

        PublicLedgerPageDto(
            generatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            page = page,
            pageSize = pageSize,
            totalItems = totalItems,
            totalPages = if (totalItems == 0L) 0 else ((totalItems + pageSize - 1) / pageSize).toInt(),
            stats = stats,
            transactions = transactions
        )
    }

    fun markCreditInstallmentPaid(adminId: Long, installmentId: Long) {
        val notice = database.transaction { connection ->
            markCreditInstallmentPaid(connection, adminId, installmentId)
        }
        notice?.let(::notifyCreditInstallmentPaid)
    }

    /**
     * Registra el pago de una cuota usando la transacción ya abierta por el llamador.
     * Esto permite que la aprobación del comprobante y la actualización del crédito
     * se confirmen o reviertan como una sola operación atómica.
     */
    private fun markCreditInstallmentPaid(
        connection: Connection,
        adminId: Long,
        installmentId: Long
    ): CreditInstallmentPaymentNotice? {
        val installment = connection.prepareStatement(
            """SELECT cl.user_id,ci.loan_id,ci.installment_number,ci.status,ci.amount_usd,ci.original_amount_bs,cl.bcv_rate,
                      ci.due_date,cl.order_id,COALESCE(i.invoice_number,cl.invoice_number,'CRED-' || cl.id::text)
               FROM cuotas_credito ci
               JOIN prestamos_credito cl ON cl.id=ci.loan_id
               LEFT JOIN facturas i ON i.order_id=cl.order_id
               WHERE ci.id=? FOR UPDATE OF ci"""
        ).use { statement ->
            statement.setLong(1, installmentId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("No se encontró la cuota de Crédito Kredi+.")
                CreditInstallmentPaymentRow(
                    userId = result.getLong(1),
                    loanId = result.getLong(2),
                    installmentNumber = result.getInt(3),
                    status = result.getString(4),
                    amountUsd = result.getBigDecimal(5).toDouble(),
                    amountBs = result.getBigDecimal(6).toDouble(),
                    bcvRate = result.getBigDecimal(7).toDouble(),
                    dueDate = result.getObject(8, LocalDate::class.java),
                    orderId = result.getLong(9).takeUnless { result.wasNull() },
                    invoiceNumber = result.getString(10)
                )
            }
        }
        if (installment.status == "PAID") return null

        val wasOverdue = installment.status == "OVERDUE" || installment.dueDate.isBefore(LocalDate.now())
        // Todas las operaciones que alteran el saldo disponible pasan por el mismo bloqueo
        // de cuenta para que compras y pagos simultáneos desde varios dispositivos sean consistentes.
        lockCreditAccount(connection, installment.userId)
        val balanceBefore = creditAccountSnapshot(connection, installment.userId).availableUsd
        connection.prepareStatement("UPDATE cuotas_credito SET status='PAID',paid_at=NOW(),paid_by=? WHERE id=?").use {
            it.setLong(1, adminId)
            it.setLong(2, installmentId)
            it.executeUpdate()
        }
        val remaining = connection.prepareStatement(
            "SELECT COUNT(*) AS unpaid, COUNT(*) FILTER (WHERE status='OVERDUE') AS overdue FROM cuotas_credito WHERE loan_id=? AND status<>'PAID'"
        ).use { statement ->
            statement.setLong(1, installment.loanId)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt("unpaid") to result.getInt("overdue")
            }
        }
        val loanStatus = when {
            remaining.first == 0 -> "PAID"
            remaining.second > 0 -> "OVERDUE"
            else -> "ACTIVE"
        }
        connection.prepareStatement("UPDATE prestamos_credito SET status=?,updated_at=NOW() WHERE id=?").use {
            it.setString(1, loanStatus)
            it.setLong(2, installment.loanId)
            it.executeUpdate()
        }
        connection.prepareStatement("UPDATE cuentas_credito SET updated_at=NOW() WHERE user_id=?").use {
            it.setLong(1, installment.userId)
            it.executeUpdate()
        }
        val levelProgress = refreshCredimpulsoLevel(connection, installment.userId)
        val balanceAfter = creditAccountSnapshot(connection, installment.userId).availableUsd
        if (levelProgress.current.level > 1) {
            audit(
                connection,
                adminId,
                "CREDIMPULSO_LEVEL_RECALCULATED",
                "USER",
                installment.userId.toString(),
                "Nivel actual ${levelProgress.current.level} · ${levelProgress.current.name}"
            )
        }
        if (!wasOverdue) {
            registerOnTimePaymentEvent(
                connection = connection,
                userId = installment.userId,
                loanId = installment.loanId,
                installmentId = installmentId,
                orderId = installment.orderId,
                invoiceNumber = installment.invoiceNumber,
                dueDate = installment.dueDate
            )
        }
        recordCredimpulsoTransaction(
            connection = connection,
            userId = installment.userId,
            type = "INSTALLMENT_PAYMENT",
            amountUsd = installment.amountUsd,
            amountBs = installment.amountBs,
            bcvRate = installment.bcvRate,
            balanceBeforeUsd = balanceBefore,
            balanceAfterUsd = balanceAfter,
            description = "Pago de cuota ${installment.installmentNumber} del préstamo ${installment.loanId}",
            loanId = installment.loanId,
            installmentId = installmentId,
            performedBy = adminId
        )
        audit(
            connection,
            adminId,
            "ADMIN_MARKED_CREDIT_INSTALLMENT_PAID",
            "CREDIT_INSTALLMENT",
            installmentId.toString(),
            "Préstamo ${installment.loanId} · cuota ${installment.installmentNumber}"
        )
        return CreditInstallmentPaymentNotice(
            userId = installment.userId,
            loanId = installment.loanId,
            installmentId = installmentId,
            installmentNumber = installment.installmentNumber
        )
    }

    private fun notifyCreditInstallmentPaid(notice: CreditInstallmentPaymentNotice) {
        notifyUsers(
            listOf(notice.userId),
            "Cuota Crédito Kredi+ registrada",
            "La cuota ${notice.installmentNumber} fue marcada como pagada. Tu saldo disponible fue actualizado.",
            "CREDIMPULSO_INSTALLMENT_PAID",
            mapOf("loanId" to notice.loanId.toString(), "installmentId" to notice.installmentId.toString())
        )
    }

    private fun <T> withSavepointFallback(
        connection: Connection,
        fallback: T,
        context: String,
        block: () -> T
    ): T {
        val savepoint = runCatching { connection.setSavepoint() }.getOrNull()

        if (savepoint == null) {
            return runCatching(block)
                .onFailure { error ->
                    logger.warn("Crédito Kredi+: falló {}.", context, error)
                }
                .getOrDefault(fallback)
        }

        return try {
            val result = block()
            runCatching { connection.releaseSavepoint(savepoint) }
            result
        } catch (error: Throwable) {
            runCatching { connection.rollback(savepoint) }
            logger.warn("Crédito Kredi+: falló {}; se aplicará un valor seguro.", context, error)
            fallback
        }
    }

    private fun ensureCreditAccount(connection: Connection, userId: Long) {
        ensureWalletV27Schema(connection)
        val firstRule = defaultCredimpulsoLevelRules().first()
        val initialLimit = firstRule.baseAmountUsd * firstRule.creditMultiplier
        connection.prepareStatement(
            """INSERT INTO cuentas_credito(user_id,level,credit_limit_usd,status)
               SELECT id,?,?, 'ACTIVE' FROM usuarios
               WHERE id=? AND UPPER(role) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS','WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA')
                 AND (verification_status='VERIFIED' OR account_status='ACTIVE')
               ON CONFLICT(user_id) DO NOTHING"""
        ).use {
            it.setInt(1, firstRule.level)
            it.setBigDecimal(2, MoneyMath.usd(initialLimit, "Límite inicial"))
            it.setLong(3, userId)
            it.executeUpdate()
        }
        connection.prepareStatement(
            """UPDATE cuentas_credito
               SET wallet_address=COALESCE(NULLIF(wallet_address,''),
                   'ISU-' || UPPER(SUBSTRING(MD5('USER:' || user_id::TEXT),1,32))),
                   updated_at=NOW()
               WHERE user_id=?"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
        val exists = connection.prepareStatement("SELECT 1 FROM cuentas_credito WHERE user_id=?").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { it.next() }
        }
        if (!exists) throw ForbiddenException("Crédito Kredi+ está disponible para usuarios con una cuenta activa.")
    }

    private fun credimpulsoLevelRules(connection: Connection): List<CredimpulsoLevelRuleDto> {
        val defaults = defaultCredimpulsoLevelRules()

        val rules = withSavepointFallback(
            connection = connection,
            fallback = emptyList<CredimpulsoLevelRuleDto>(),
            context = "leer reglas configurables de niveles Crédito Kredi+"
        ) {
            connection.prepareStatement(
                """
                SELECT 1 AS level,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_1
                UNION ALL
                SELECT 2,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_2
                UNION ALL
                SELECT 3,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_3
                UNION ALL
                SELECT 4,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_4
                UNION ALL
                SELECT 5,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_5
                UNION ALL
                SELECT 6,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_6
                ORDER BY level
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                CredimpulsoLevelRuleDto(
                                    level = result.getInt(1),
                                    name = result.getString(2),
                                    completedPaymentsRequired = result.getInt(3),
                                    creditMultiplier = result.getInt(4),
                                    downPaymentPercent = result.getBigDecimal(5).toDouble(),
                                    baseAmountUsd = result.getBigDecimal(6).toDouble(),
                                    maxInstallments = result.getInt(7).coerceIn(2, 6)
                                )
                            )
                        }
                    }
                }
            }
        }

        val valid = rules
            .filter { it.level in 1..6 }
            .distinctBy { it.level }
            .sortedBy { it.level }

        return if (valid.size == 6) valid else defaults
    }

    private fun defaultCredimpulsoLevelRules(): List<CredimpulsoLevelRuleDto> = listOf(
        CredimpulsoLevelRuleDto(1, "Santa Ana", 0, 1, 20.0, 60.0, 2),
        CredimpulsoLevelRuleDto(2, "El Ávila", 3, 2, 16.0, 60.0, 2),
        CredimpulsoLevelRuleDto(3, "Autana", 6, 3, 12.0, 60.0, 3),
        CredimpulsoLevelRuleDto(4, "Auyantepuy", 12, 4, 8.0, 60.0, 4),
        CredimpulsoLevelRuleDto(5, "Pico Bolívar", 20, 5, 4.0, 60.0, 5),
        CredimpulsoLevelRuleDto(6, "Salto Ángel", 30, 6, 0.0, 60.0, 6)
    )

    private fun refreshCredimpulsoLevel(connection: Connection, userId: Long): CredimpulsoLevelProgress {
        val rules = credimpulsoLevelRules(connection)

        val completedPayments = withSavepointFallback(
            connection = connection,
            fallback = 0,
            context = "contar cuotas pagadas para userId=$userId"
        ) {
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM cuotas_credito ci
                JOIN prestamos_credito cl ON cl.id=ci.loan_id
                WHERE cl.user_id=? AND ci.status='PAID'
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

        val earned = rules
            .filter { completedPayments >= it.completedPaymentsRequired }
            .maxByOrNull { it.level }
            ?: rules.minBy { it.level }

        // La mora activa reduce un nivel por cada bloque completo de 15 días.
        // Ej.: 0-14 días = sin bajada; 15-29 = -1; 30-44 = -2, etc.
        val overdueDays = withSavepointFallback(
            connection = connection,
            fallback = 0,
            context = "calcular días máximos de mora para userId=$userId"
        ) {
            connection.prepareStatement(
                """
                SELECT COALESCE(MAX(GREATEST(CURRENT_DATE-ci.due_date,0)),0)
                FROM cuotas_credito ci
                JOIN prestamos_credito cl ON cl.id=ci.loan_id
                WHERE cl.user_id=?
                  AND ci.status<>'PAID'
                  AND ci.due_date<CURRENT_DATE
                  AND cl.status<>'CANCELLED'
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1).coerceAtLeast(0)
                }
            }
        }
        val overduePenaltySteps = (overdueDays / 15).coerceAtLeast(0)
        val effectiveLevel = (earned.level - overduePenaltySteps).coerceIn(1, 6)
        val current = rules.firstOrNull { it.level == effectiveLevel } ?: rules.first()
        val next = rules.filter { it.level > effectiveLevel }.minByOrNull { it.level }
        val previousLevel = connection.prepareStatement(
            "SELECT level FROM cuentas_credito WHERE user_id=? FOR UPDATE"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getInt(1).coerceIn(1, 6) else 1
            }
        }

        connection.prepareStatement(
            """
            UPDATE cuentas_credito
            SET level=?,
                updated_at=NOW()
            WHERE user_id=?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, effectiveLevel)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }

        // Registra la bajada solo cuando cambia realmente el nivel persistido.
        if (effectiveLevel < previousLevel) {
            ensureCreditHistory(connection, userId)
            val history = creditHistorySnapshot(connection, userId, lock = true)
            connection.prepareStatement(
                """
                INSERT INTO eventos_historial_crediticio(
                    user_id,event_type,score_before,score_after,due_date,details
                ) VALUES (?,'LEVEL_DOWNGRADED',?,?,CURRENT_DATE,?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setInt(2, history.scorePercentage)
                statement.setInt(3, history.scorePercentage)
                statement.setString(4, "Nivel $previousLevel → $effectiveLevel por $overdueDays días de mora activa (penalización cada 15 días)")
                statement.executeUpdate()
            }
        }


        return CredimpulsoLevelProgress(
            current = current,
            next = next,
            completedPayments = completedPayments,
            rules = rules,
            overdueDays = overdueDays,
            overduePenaltySteps = overduePenaltySteps
        )
    }

    private fun lockCreditAccount(connection: Connection, userId: Long) {
        connection.prepareStatement("SELECT user_id FROM cuentas_credito WHERE user_id=? FOR UPDATE").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("No se encontró la cuenta Crédito Kredi+.")
            }
        }
    }

    private fun ensureCreditHistory(connection: Connection, userId: Long) {
        connection.prepareStatement(
            """INSERT INTO historial_crediticio_usuarios(user_id)
               VALUES (?) ON CONFLICT(user_id) DO NOTHING"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }

    private fun creditHistorySnapshot(connection: Connection, userId: Long, lock: Boolean = false): CreditHistorySnapshot {
        ensureCreditHistory(connection, userId)
        val suffix = if (lock) " FOR UPDATE" else ""
        return connection.prepareStatement(
            "SELECT porcentaje,pagos_atrasados,pagos_a_tiempo,estado FROM historial_crediticio_usuarios WHERE user_id=?$suffix"
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) CreditHistorySnapshot(100, 0, 0, "ACTIVE")
                else CreditHistorySnapshot(
                    scorePercentage = result.getInt(1).coerceIn(0, 100),
                    latePaymentCount = result.getInt(2).coerceAtLeast(0),
                    onTimePaymentCount = result.getInt(3).coerceAtLeast(0),
                    status = result.getString(4) ?: "ACTIVE"
                )
            }
        }
    }

    private fun registerLatePaymentEvent(
        connection: Connection,
        userId: Long,
        loanId: Long,
        installmentId: Long,
        orderId: Long?,
        invoiceNumber: String,
        dueDate: LocalDate
    ) {
        ensureCreditHistory(connection, userId)
        val alreadyRecorded = connection.prepareStatement(
            "SELECT 1 FROM eventos_historial_crediticio WHERE installment_id=? AND event_type='LATE_PAYMENT'"
        ).use { statement ->
            statement.setLong(1, installmentId)
            statement.executeQuery().use { it.next() }
        }
        if (alreadyRecorded) return

        val before = creditHistorySnapshot(connection, userId, lock = true)
        val lateCount = before.latePaymentCount + 1
        val scoreAfter = (before.scorePercentage - 35).coerceAtLeast(0)
        val suspended = lateCount >= 2
        val historyStatus = when {
            before.status == "CLOSED" -> "CLOSED"
            suspended -> "SUSPENDED"
            else -> before.status
        }

        connection.prepareStatement(
            """INSERT INTO eventos_historial_crediticio(
                   user_id,loan_id,installment_id,order_id,invoice_number,event_type,
                   score_before,score_after,due_date,details
               ) VALUES (?,?,?,?,?,'LATE_PAYMENT',?,?,?,?)"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, loanId)
            statement.setLong(3, installmentId)
            if (orderId == null) statement.setNull(4, java.sql.Types.BIGINT) else statement.setLong(4, orderId)
            statement.setString(5, invoiceNumber)
            statement.setInt(6, before.scorePercentage)
            statement.setInt(7, scoreAfter)
            statement.setObject(8, dueDate)
            statement.setString(9, "Cuota vencida de la factura $invoiceNumber")
            statement.executeUpdate()
        }

        connection.prepareStatement(
            """UPDATE historial_crediticio_usuarios
               SET porcentaje=?,pagos_atrasados=?,estado=?,
                   suspendido_at=CASE WHEN ? THEN COALESCE(suspendido_at,NOW()) ELSE suspendido_at END,
                   motivo_suspension=CASE WHEN ? THEN 'Dos cuotas pagadas fuera de fecha' ELSE motivo_suspension END,
                   updated_at=NOW()
               WHERE user_id=?"""
        ).use { statement ->
            statement.setInt(1, scoreAfter)
            statement.setInt(2, lateCount)
            statement.setString(3, historyStatus)
            statement.setBoolean(4, suspended)
            statement.setBoolean(5, suspended)
            statement.setLong(6, userId)
            statement.executeUpdate()
        }

        if (suspended) {
            connection.prepareStatement("UPDATE cuentas_credito SET status='SUSPENDED',updated_at=NOW() WHERE user_id=? AND status<>'CLOSED'").use {
                it.setLong(1, userId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO eventos_historial_crediticio(
                       user_id,loan_id,installment_id,order_id,invoice_number,event_type,
                       score_before,score_after,due_date,details
                   ) VALUES (?,?,?,?,?,'CREDIT_SUSPENDED',?,?,?,?)
                   ON CONFLICT DO NOTHING"""
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, loanId)
                statement.setLong(3, installmentId)
                if (orderId == null) statement.setNull(4, java.sql.Types.BIGINT) else statement.setLong(4, orderId)
                statement.setString(5, invoiceNumber)
                statement.setInt(6, scoreAfter)
                statement.setInt(7, scoreAfter)
                statement.setObject(8, dueDate)
                statement.setString(9, "Crédito suspendido automáticamente tras dos atrasos")
                statement.executeUpdate()
            }
        }
    }

    private fun registerOnTimePaymentEvent(
        connection: Connection,
        userId: Long,
        loanId: Long,
        installmentId: Long,
        orderId: Long?,
        invoiceNumber: String,
        dueDate: LocalDate
    ) {
        val alreadyRecorded = connection.prepareStatement(
            "SELECT 1 FROM eventos_historial_crediticio WHERE installment_id=? AND event_type='ON_TIME_PAYMENT'"
        ).use { statement ->
            statement.setLong(1, installmentId)
            statement.executeQuery().use { it.next() }
        }
        if (alreadyRecorded) return
        val before = creditHistorySnapshot(connection, userId, lock = true)
        val scoreAfter = (before.scorePercentage + 3).coerceAtMost(100)
        connection.prepareStatement(
            """INSERT INTO eventos_historial_crediticio(
                   user_id,loan_id,installment_id,order_id,invoice_number,event_type,
                   score_before,score_after,due_date,details
               ) VALUES (?,?,?,?,?,'ON_TIME_PAYMENT',?,?,?,?)"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, loanId)
            statement.setLong(3, installmentId)
            if (orderId == null) statement.setNull(4, java.sql.Types.BIGINT) else statement.setLong(4, orderId)
            statement.setString(5, invoiceNumber)
            statement.setInt(6, before.scorePercentage)
            statement.setInt(7, scoreAfter)
            statement.setObject(8, dueDate)
            statement.setString(9, "Cuota pagada a tiempo de la factura $invoiceNumber")
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """UPDATE historial_crediticio_usuarios
               SET porcentaje=?,pagos_a_tiempo=pagos_a_tiempo+1,updated_at=NOW()
               WHERE user_id=?"""
        ).use { statement ->
            statement.setInt(1, scoreAfter)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }
    }

    private fun refreshCreditStatuses(connection: Connection, userId: Long) {
        ensureCreditHistory(connection, userId)
        val newlyOverdue = connection.prepareStatement(
            """SELECT ci.id,ci.loan_id,cl.order_id,ci.due_date,
                      COALESCE(i.invoice_number,cl.invoice_number,'CRED-' || cl.id::text)
               FROM cuotas_credito ci
               JOIN prestamos_credito cl ON cl.id=ci.loan_id
               LEFT JOIN facturas i ON i.order_id=cl.order_id
               WHERE cl.user_id=? AND ci.status='PENDING' AND ci.due_date<CURRENT_DATE
               ORDER BY ci.due_date,ci.id
               FOR UPDATE OF ci"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                buildList<OverdueInstallmentRow> {
                    while (result.next()) {
                        add(
                            OverdueInstallmentRow(
                                installmentId = result.getLong(1),
                                loanId = result.getLong(2),
                                orderId = result.getObject(3)?.let { result.getLong(3) },
                                dueDate = result.getObject(4, LocalDate::class.java),
                                invoiceNumber = result.getString(5)
                            )
                        )
                    }
                }
            }
        }

        if (newlyOverdue.isNotEmpty()) {
            connection.prepareStatement(
                """UPDATE cuotas_credito ci SET status='OVERDUE'
                   FROM prestamos_credito cl
                   WHERE ci.loan_id=cl.id AND cl.user_id=? AND ci.status='PENDING' AND ci.due_date<CURRENT_DATE"""
            ).use { it.setLong(1, userId); it.executeUpdate() }

            newlyOverdue.forEach { row ->
                registerLatePaymentEvent(
                    connection = connection,
                    userId = userId,
                    installmentId = row.installmentId,
                    loanId = row.loanId,
                    orderId = row.orderId,
                    dueDate = row.dueDate,
                    invoiceNumber = row.invoiceNumber
                )
            }
        }

        connection.prepareStatement(
            """UPDATE prestamos_credito cl SET status='OVERDUE',updated_at=NOW()
               WHERE cl.user_id=? AND cl.status='ACTIVE'
                 AND EXISTS (SELECT 1 FROM cuotas_credito ci WHERE ci.loan_id=cl.id AND ci.status='OVERDUE')"""
        ).use { it.setLong(1, userId); it.executeUpdate() }
    }

    private fun creditAccountSnapshot(connection: Connection, userId: Long): CreditAccountSnapshot {
        return connection.prepareStatement(
            """
            SELECT ca.level,ca.credit_limit_usd,ca.status,ca.preferred_installments,
                   COALESCE(SUM(CASE WHEN ci.status<>'PAID' THEN ci.amount_usd ELSE 0 END),0) AS used_usd
            FROM cuentas_credito ca
            LEFT JOIN prestamos_credito cl ON cl.user_id=ca.user_id AND cl.status<>'CANCELLED'
            LEFT JOIN cuotas_credito ci ON ci.loan_id=cl.id
            WHERE ca.user_id=?
            GROUP BY ca.level,ca.credit_limit_usd,ca.status,ca.preferred_installments
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("No se encontró la cuenta Crédito Kredi+.")
                val limit = result.getBigDecimal(2).toDouble()
                val used = result.getBigDecimal(5).toDouble()
                CreditAccountSnapshot(
                    level = result.getInt(1),
                    creditLimitUsd = limit,
                    usedUsd = used,
                    availableUsd = (limit - used).coerceAtLeast(0.0),
                    status = result.getString(3),
                    preferredInstallments = result.getInt(4).coerceIn(2, 6)
                )
            }
        }
    }

    private fun creditInstallments(connection: Connection, userId: Long): List<CreditInstallmentDto> = connection.prepareStatement(
        """SELECT ci.id,ci.loan_id,ci.installment_number,ci.amount_usd,ci.original_amount_bs,ci.due_date,ci.status,
                  COALESCE(i.invoice_number,cl.invoice_number,'CRED-' || cl.id::text),cl.order_id,
                  CASE WHEN o.fair_id IS NOT NULL THEN o.fair_id ELSE -cl.id END AS payment_fair_id,
                  COALESCE(f.name,'Préstamo ' || COALESCE(cl.invoice_number,'CRED-' || cl.id::text)) AS payment_fair_name,
                  COALESCE(f.payment_mode,cl.repayment_payment_mode) AS payment_mode,
                  COALESCE(f.business_id,cl.repayment_business_id) AS business_id,
                  COALESCE(b.commercial_name,cl.repayment_business_commercial_name) AS business_commercial_name,
                  COALESCE(b.legal_name,cl.repayment_business_legal_name) AS business_legal_name,
                  COALESCE(b.rif,cl.repayment_business_rif) AS business_rif,
                  COALESCE(b.logo_path,cl.repayment_business_logo_path) AS business_logo_path,
                  COALESCE(pd.mobile_bank,cl.repayment_mobile_bank) AS mobile_bank,
                  COALESCE(pd.mobile_phone,cl.repayment_mobile_phone) AS mobile_phone,
                  COALESCE(pd.mobile_identity_number,cl.repayment_mobile_identity_number) AS mobile_identity_number,
                  COALESCE(pd.mobile_holder_name,cl.repayment_mobile_holder_name) AS mobile_holder_name,
                  COALESCE(pd.bank_name,cl.repayment_bank_name) AS bank_name,
                  COALESCE(pd.bank_account_type,cl.repayment_bank_account_type) AS bank_account_type,
                  COALESCE(pd.bank_account_number,cl.repayment_bank_account_number) AS bank_account_number,
                  COALESCE(pd.bank_identity_number,cl.repayment_bank_identity_number) AS bank_identity_number,
                  COALESCE(pd.bank_holder_name,cl.repayment_bank_holder_name) AS bank_holder_name
           FROM cuotas_credito ci
           JOIN prestamos_credito cl ON cl.id=ci.loan_id
           LEFT JOIN pedidos o ON o.id=cl.order_id
           LEFT JOIN jornadas f ON f.id=o.fair_id
           LEFT JOIN negocios_asociados b ON b.id=COALESCE(f.business_id,cl.repayment_business_id)
           LEFT JOIN detalles_pago_jornada pd ON pd.fair_id=f.id
           LEFT JOIN facturas i ON i.order_id=cl.order_id
           WHERE cl.user_id=? ORDER BY ci.due_date,ci.installment_number"""
    ).use { statement ->
        statement.setLong(1, userId)
        statement.executeQuery().use { result -> buildList {
            while (result.next()) add(
                CreditInstallmentDto(
                    result.getLong(1), result.getLong(2), result.getInt(3), result.getBigDecimal(4).toDouble(),
                    result.getBigDecimal(5).toDouble(), result.getObject(6, LocalDate::class.java).toString(), result.getString(7), result.getString(8),
                    result.getLong(9),
                    result.getLong("payment_fair_id"), paymentDestinationDto(result)
                )
            )
        } }
    }

    private fun creditInstallmentsForLoan(connection: Connection, loanId: Long, invoiceNumber: String): List<CreditInstallmentDto> = connection.prepareStatement(
        """SELECT ci.id,ci.loan_id,ci.installment_number,ci.amount_usd,ci.original_amount_bs,ci.due_date,ci.status,
                  cl.order_id,
                  CASE WHEN o.fair_id IS NOT NULL THEN o.fair_id ELSE -cl.id END AS payment_fair_id,
                  COALESCE(f.name,'Préstamo ' || COALESCE(cl.invoice_number,?)) AS payment_fair_name,
                  COALESCE(f.payment_mode,cl.repayment_payment_mode) AS payment_mode,
                  COALESCE(f.business_id,cl.repayment_business_id) AS business_id,
                  COALESCE(b.commercial_name,cl.repayment_business_commercial_name) AS business_commercial_name,
                  COALESCE(b.legal_name,cl.repayment_business_legal_name) AS business_legal_name,
                  COALESCE(b.rif,cl.repayment_business_rif) AS business_rif,
                  COALESCE(b.logo_path,cl.repayment_business_logo_path) AS business_logo_path,
                  COALESCE(pd.mobile_bank,cl.repayment_mobile_bank) AS mobile_bank,
                  COALESCE(pd.mobile_phone,cl.repayment_mobile_phone) AS mobile_phone,
                  COALESCE(pd.mobile_identity_number,cl.repayment_mobile_identity_number) AS mobile_identity_number,
                  COALESCE(pd.mobile_holder_name,cl.repayment_mobile_holder_name) AS mobile_holder_name,
                  COALESCE(pd.bank_name,cl.repayment_bank_name) AS bank_name,
                  COALESCE(pd.bank_account_type,cl.repayment_bank_account_type) AS bank_account_type,
                  COALESCE(pd.bank_account_number,cl.repayment_bank_account_number) AS bank_account_number,
                  COALESCE(pd.bank_identity_number,cl.repayment_bank_identity_number) AS bank_identity_number,
                  COALESCE(pd.bank_holder_name,cl.repayment_bank_holder_name) AS bank_holder_name
           FROM cuotas_credito ci
           JOIN prestamos_credito cl ON cl.id=ci.loan_id
           LEFT JOIN pedidos o ON o.id=cl.order_id
           LEFT JOIN jornadas f ON f.id=o.fair_id
           LEFT JOIN negocios_asociados b ON b.id=COALESCE(f.business_id,cl.repayment_business_id)
           LEFT JOIN detalles_pago_jornada pd ON pd.fair_id=f.id
           WHERE ci.loan_id=? ORDER BY ci.installment_number"""
    ).use { statement ->
        statement.setString(1, invoiceNumber)
        statement.setLong(2, loanId)
        statement.executeQuery().use { result -> buildList {
            while (result.next()) add(
                CreditInstallmentDto(
                    result.getLong(1), result.getLong(2), result.getInt(3), result.getBigDecimal(4).toDouble(),
                    result.getBigDecimal(5).toDouble(), result.getObject(6, LocalDate::class.java).toString(), result.getString(7), invoiceNumber,
                    result.getLong(8),
                    result.getLong("payment_fair_id"), paymentDestinationDto(result)
                )
            )
        } }
    }

    fun purchases(userId: Long): List<PurchaseDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT o.id,o.total,o.item_count,o.status,i.invoice_number,o.created_at,
                   CASE WHEN o.financing_type='CREDIMPULSO' THEN 'CREDIMPULSO' ELSE p.method END AS payment_method,
                   o.fair_id AS payment_fair_id,f.name AS payment_fair_name,f.payment_mode AS payment_mode,
                   f.business_id,b.commercial_name AS business_commercial_name,b.legal_name AS business_legal_name,
                   b.rif AS business_rif,b.logo_path AS business_logo_path,
                   pd.mobile_bank,pd.mobile_phone,pd.mobile_identity_number,pd.mobile_holder_name,
                   pd.bank_name,pd.bank_account_type,pd.bank_account_number,pd.bank_identity_number,pd.bank_holder_name
            FROM pedidos o
            JOIN jornadas f ON f.id=o.fair_id
            LEFT JOIN negocios_asociados b ON b.id=f.business_id
            LEFT JOIN detalles_pago_jornada pd ON pd.fair_id=f.id
            LEFT JOIN facturas i ON i.order_id=o.id
            LEFT JOIN LATERAL (SELECT method FROM pagos WHERE order_id=o.id ORDER BY created_at DESC LIMIT 1) p ON TRUE
            WHERE o.user_id=? ORDER BY o.created_at DESC
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(PurchaseDto(result.getLong(1), result.getBigDecimal(2).toDouble(), result.getInt(3), result.getString(4), result.getString(5).orEmpty(), result.getObject(6, OffsetDateTime::class.java).toInstant().toEpochMilli(), result.getString(7), result.getLong("payment_fair_id"), paymentDestinationDto(result)))
            } }
        }
    }

    fun createPurchase(userId: Long, request: CreatePurchaseRequest): PurchaseDto {
        val catalogRate = runCatching { bcvRateService.currentUsdRate().rate }
            .getOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
        val creditRate = if (request.paymentMethod == "CREDIMPULSO") {
            catalogRate ?: throw AppException("No se pudo consultar una tasa BCV válida para calcular Crédito Kredi+. Intenta nuevamente.")
        } else null

        var requiresVerification = false
        var notificationProofPath: String? = null
        var creditPrincipalUsd: Double? = null
        var creditPrincipalUsdExact: java.math.BigDecimal? = null
        var creditInstallmentCount: Int? = null

        val purchase = database.transaction { connection ->
            catalogRate?.let { current ->
                val rate = MoneyMath.positive(MoneyMath.rate(current), "Tasa BCV")
                refreshProductPricesForCurrentBcv(connection, rate)
                refreshComboPricesForCurrentBcv(connection, rate)
            }
            val directRequests = request.items
                .filter { it.quantity > 0 }
                .groupBy { it.productId }
                .mapValues { (_, rows) -> rows.sumOf { it.quantity } }
            val comboRequests = request.comboItems
                .filter { it.quantity > 0 }
                .groupBy { it.comboId }
                .mapValues { (_, rows) -> rows.sumOf { it.quantity } }
            if (directRequests.isEmpty() && comboRequests.isEmpty()) throw AppException("El carrito está vacío.")

            // El catálogo es permanente e independiente de las jornadas visibles. Para conservar
            // integridad contable usamos una jornada interna no publicada que nunca se muestra al usuario.
            val catalogPurchase = request.fairId <= 0L
            val catalogSaleType = when {
                directRequests.isNotEmpty() && comboRequests.isEmpty() -> "PRODUCT"
                comboRequests.isNotEmpty() && directRequests.isEmpty() -> "COMBO"
                directRequests.isNotEmpty() && comboRequests.isNotEmpty() -> {
                    val destinations = catalogSalesDestinations()
                    if (destinations.productBusinessId == null || destinations.comboBusinessId == null || destinations.productBusinessId != destinations.comboBusinessId) {
                        throw AppException("Los productos y combos tienen destinos de pago distintos. Completa la compra por separado.")
                    }
                    "PRODUCT"
                }
                else -> "PRODUCT"
            }
            val effectiveFairId = if (catalogPurchase) ensurePermanentCatalogFair(connection, catalogSaleType) else request.fairId
            if (!catalogPurchase) {
                connection.prepareStatement("SELECT active,published FROM jornadas WHERE id=? FOR SHARE").use { statement ->
                    statement.setLong(1, effectiveFairId)
                    statement.executeQuery().use { result ->
                        if (!result.next() || !result.getBoolean("active") || !result.getBoolean("published")) {
                            throw AppException("La jornada seleccionada ya no está disponible. Actualiza la lista de jornadas.")
                        }
                    }
                }
            }
            val fair = fairDto(connection, effectiveFairId)
            val isCredit = request.paymentMethod == "CREDIMPULSO"
            if (!isCredit && request.paymentMethod !in setOf("MOBILE_PAYMENT", "BANK_TRANSFER")) {
                throw AppException("Método de pago inválido.")
            }

            val resolvedItems = directRequests.map { (productId, quantity) ->
                connection.prepareStatement(
                    """
                    SELECT p.id,p.name,p.unit,p.base_price
                    FROM productos p
                    WHERE p.id=? AND p.active=TRUE
                      AND (? OR EXISTS (SELECT 1 FROM productos_jornada fp WHERE fp.fair_id=? AND fp.product_id=p.id))
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, productId)
                    statement.setBoolean(2, catalogPurchase)
                    statement.setLong(3, effectiveFairId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw AppException("Uno de los productos ya no está disponible en esta jornada.")
                        PurchaseResolved(
                            id = result.getLong(1),
                            name = result.getString(2),
                            unit = result.getString(3),
                            stock = 0,
                            price = result.getBigDecimal(4).setScale(MoneyMath.VES_SCALE, RoundingMode.HALF_EVEN),
                            quantity = quantity
                        )
                    }
                }
            }

            val resolvedCombos = comboRequests.map { (comboId, requestedQuantity) ->
                val components = connection.prepareStatement(
                    """
                    SELECT c.name,c.combo_price_bs,cp.product_id,cp.quantity,p.name,p.unit,p.base_price,fp.product_id
                    FROM combos c
                    JOIN productos_combo cp ON cp.combo_id=c.id
                    JOIN productos p ON p.id=cp.product_id AND p.active=TRUE
                    LEFT JOIN productos_jornada fp ON fp.fair_id=? AND fp.product_id=p.id
                    WHERE c.id=? AND c.active=TRUE
                    ORDER BY cp.product_id
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, effectiveFairId)
                    statement.setLong(2, comboId)
                    statement.executeQuery().use { result ->
                        val rows = mutableListOf<ComboPurchaseComponent>()
                        var comboName = ""
                        var comboCommercialPrice = BigDecimal.ZERO
                        while (result.next()) {
                            comboName = result.getString(1)
                            comboCommercialPrice = result.getBigDecimal(2) ?: BigDecimal.ZERO
                            if (!catalogPurchase && result.getObject(8) == null) {
                                throw AppException("El combo $comboName contiene productos que no están disponibles en esta jornada.")
                            }
                            val inventoryPrice = result.getBigDecimal(7)
                            rows += ComboPurchaseComponent(
                                productId = result.getLong(3),
                                quantityPerCombo = result.getInt(4),
                                productName = result.getString(5),
                                unit = result.getString(6),
                                fairPrice = inventoryPrice.setScale(MoneyMath.VES_SCALE, RoundingMode.HALF_EVEN)
                            )
                        }
                        if (rows.isEmpty()) throw AppException("Uno de los combos ya no está disponible.")
                        Triple(comboName, comboCommercialPrice, rows)
                    }
                }
                val individualTotal = MoneyMath.sum(components.third.map { component -> MoneyMath.multiplyMoney(component.fairPrice, component.quantityPerCombo) })
                val unitPrice = components.second.takeIf { it.signum() > 0 }
                    ?.setScale(MoneyMath.VES_SCALE, RoundingMode.HALF_EVEN)
                    ?: individualTotal
                ComboPurchaseResolved(comboId, components.first, requestedQuantity, unitPrice, components.third)
            }

            val requiredStock = mutableMapOf<Long, Int>()
            resolvedItems.forEach { item -> requiredStock[item.id] = (requiredStock[item.id] ?: 0) + item.quantity }
            resolvedCombos.forEach { combo ->
                combo.components.forEach { component ->
                    requiredStock[component.productId] = (requiredStock[component.productId] ?: 0) + (component.quantityPerCombo * combo.quantity)
                }
            }
            requiredStock.toSortedMap().forEach { (productId, requiredQuantity) ->
                connection.prepareStatement("SELECT name,stock FROM productos WHERE id=? AND active=TRUE FOR UPDATE").use { statement ->
                    statement.setLong(1, productId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw AppException("Uno de los productos del carrito ya no está disponible.")
                        if (requiredQuantity > result.getInt(2)) {
                            throw AppException("Cantidad insuficiente para ${result.getString(1)}.")
                        }
                    }
                }
            }

            val directTotalsByProduct = resolvedItems.associate { item -> item.id to MoneyMath.multiplyMoney(item.price, item.quantity) }
            val comboTotalsByCombo = resolvedCombos.associate { combo -> combo.id to MoneyMath.multiplyMoney(combo.unitPrice, combo.quantity) }
            val subtotalBd = MoneyMath.sum(directTotalsByProduct.values + comboTotalsByCombo.values)
            if (subtotalBd.signum() <= 0) throw AppException("El total de la compra debe ser mayor que cero.")
            val promotionRate = connection.prepareStatement("SELECT COALESCE(NULLIF(MAX(bcv_rate),0),1) FROM productos").use { st ->
                st.executeQuery().use { r -> r.next(); r.getBigDecimal(1) ?: BigDecimal.ONE }
            }
            val appliedPromotion = bestPromotionForPurchase(connection, effectiveFairId, directTotalsByProduct, comboTotalsByCombo, subtotalBd, promotionRate)
            val discountBd = appliedPromotion?.discountAmount ?: BigDecimal.ZERO.setScale(MoneyMath.VES_SCALE)
            val totalBd = subtotalBd.subtract(discountBd).max(BigDecimal.ZERO).setScale(MoneyMath.VES_SCALE,RoundingMode.HALF_EVEN)
            val total = totalBd.toDouble()
            val itemCount = resolvedItems.sumOf { it.quantity } + resolvedCombos.sumOf { it.quantity }
            if (totalBd.signum() <= 0) throw AppException("El total final de la compra debe ser mayor que cero.")

            var selectedBank: BankDto? = null
            var reference = ""
            var originPhone = ""
            var proofPath: String? = null

            if (isCredit) {
                ensureCreditAccount(connection, userId)
                // Serializa las compras de crédito de una misma cuenta. Dos teléfonos no pueden
                // consumir simultáneamente el mismo saldo disponible de US$60.
                lockCreditAccount(connection, userId)
                refreshCreditStatuses(connection, userId)
                val progress = refreshCredimpulsoLevel(connection, userId)
                val account = creditAccountSnapshot(connection, userId)
                val history = creditHistorySnapshot(connection, userId)
                if (account.status != "ACTIVE" || history.status == "SUSPENDED") {
                    throw AppException("Tu Crédito Kredi+ está suspendido por tu historial crediticio.")
                }
                creditInstallmentCount = account.preferredInstallments.coerceIn(2, progress.current.maxInstallments)
                val rate = creditRate ?: throw AppException("No hay tasa BCV disponible para Crédito Kredi+.")
                val rateBd = MoneyMath.rate(rate)
                val principalUsdExact = MoneyMath.vesToUsd(totalBd, rateBd)
                val availableUsdExact = MoneyMath.usd(account.availableUsd, "Saldo Crédito Kredi+")
                if (principalUsdExact < java.math.BigDecimal("0.02")) {
                    throw AppException("El monto es demasiado bajo para financiarse con Crédito Kredi+.")
                }
                if (principalUsdExact > availableUsdExact) {
                    throw AppException("Tu saldo Crédito Kredi+ disponible es US$ ${"%.2f".format(account.availableUsd)} y esta compra requiere US$ ${"%.2f".format(principalUsdExact.toDouble())}.")
                }
                creditPrincipalUsdExact = principalUsdExact
                creditPrincipalUsd = principalUsdExact.toDouble()
            } else {
                reference = request.paymentReference.trim()
                if (reference.length < 4 || reference.length > 180 || !reference.all(Char::isDigit)) {
                    throw AppException("Copia la referencia completa del pago usando solo números.")
                }
                originPhone = normalizeVenezuelanPhone(request.originPhone)
                    ?: throw AppException("Ingresa el número de celular desde donde salió el pago.")
                val registeredPhone = connection.prepareStatement("SELECT phone FROM perfiles_usuario WHERE user_id=?").use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw NotFoundException("No se encontró el perfil del usuario.")
                        normalizeVenezuelanPhone(result.getString(1))
                    }
                }
                if (!request.paidFromDifferentPhone && registeredPhone != null && originPhone != registeredPhone) {
                    throw AppException("Si el pago salió de otro celular, marca la opción correspondiente y adjunta el comprobante.")
                }
                proofPath = request.proofPath?.trim()?.takeIf { it.isNotBlank() }
                notificationProofPath = proofPath
                val expectedProofPrefix = "payment-proofs/$userId/"
                if (proofPath == null || !proofPath.startsWith(expectedProofPrefix)) {
                    throw AppException("Debes adjuntar una captura válida del comprobante para confirmar el pago.")
                }
                val proofFile = File(uploadRoot, proofPath).canonicalFile
                val canonicalRoot = uploadRoot.canonicalFile
                if (!proofFile.path.startsWith(canonicalRoot.path + File.separator) || !proofFile.isFile || proofFile.length() <= 0L) {
                    throw AppException("No se encontró el comprobante adjunto. Selecciona la imagen nuevamente.")
                }
                selectedBank = connection.prepareStatement("SELECT code,name FROM directorio_bancos WHERE code=? AND active=TRUE").use { statement ->
                    statement.setString(1, request.originBankCode.trim())
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw AppException("Selecciona un banco válido.")
                        BankDto(result.getString(1), result.getString(2))
                    }
                }
                val allowed = when (request.paymentMethod) {
                    "MOBILE_PAYMENT" -> fair.paymentMode in setOf("MOBILE_PAYMENT", "BOTH")
                    else -> fair.paymentMode in setOf("BANK_TRANSFER", "BOTH")
                }
                if (!allowed) throw AppException("Ese método de pago no está habilitado para esta jornada.")
            }

            val orderStatus = if (isCredit) "Crédito activo" else "Pago reportado"
            val financingType = if (isCredit) "CREDIMPULSO" else "DIRECT_PAYMENT"
            val orderId = connection.prepareStatement(
                "INSERT INTO pedidos(user_id,fair_id,status,item_count,subtotal,total,financing_type,original_subtotal,discount_amount,promotion_id,promotion_name_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id"
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, effectiveFairId)
                statement.setString(3, orderStatus)
                statement.setInt(4, itemCount)
                statement.setBigDecimal(5, subtotalBd)
                statement.setBigDecimal(6, totalBd)
                statement.setString(7, financingType)
                statement.setBigDecimal(8, subtotalBd)
                statement.setBigDecimal(9, discountBd)
                if (appliedPromotion == null) statement.setNull(10, java.sql.Types.BIGINT) else statement.setLong(10, appliedPromotion.id)
                statement.setString(11, appliedPromotion?.name)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }

            resolvedItems.forEach { item ->
                connection.prepareStatement("INSERT INTO items_pedido(order_id,product_id,product_name_snapshot,unit_snapshot,quantity,unit_price) VALUES (?,?,?,?,?,?)").use { statement ->
                    statement.setLong(1, orderId)
                    statement.setLong(2, item.id)
                    statement.setString(3, item.name)
                    statement.setString(4, item.unit)
                    statement.setInt(5, item.quantity)
                    statement.setBigDecimal(6, item.price)
                    statement.executeUpdate()
                }
            }
            resolvedCombos.forEach { combo ->
                connection.prepareStatement("INSERT INTO combos_pedido(order_id,combo_id,combo_name_snapshot,quantity,unit_price) VALUES (?,?,?,?,?)").use { statement ->
                    statement.setLong(1, orderId)
                    statement.setLong(2, combo.id)
                    statement.setString(3, combo.name)
                    statement.setInt(4, combo.quantity)
                    statement.setBigDecimal(5, combo.unitPrice)
                    statement.executeUpdate()
                }
            }
            requiredStock.forEach { (productId, quantity) ->
                connection.prepareStatement("UPDATE productos SET stock=stock-?,updated_at=NOW() WHERE id=?").use {
                    it.setInt(1, quantity)
                    it.setLong(2, productId)
                    it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO movimientos_inventario(product_id,movement_type,quantity_delta,reference_type,reference_id,performed_by) VALUES (?,'SALE',?,'ORDER',?,?)").use {
                    it.setLong(1, productId)
                    it.setInt(2, -quantity)
                    it.setString(3, orderId.toString())
                    it.setLong(4, userId)
                    it.executeUpdate()
                }
            }

            if (!isCredit) {
                val bank = selectedBank ?: throw AppException("Selecciona un banco válido.")
                val paymentId = connection.prepareStatement(
                    """INSERT INTO pagos(order_id,method,origin_bank,origin_bank_code,origin_phone,paid_from_different_phone,proof_file_path,reference_number,amount_paid,status)
                       VALUES (?,?,?,?,?,?,?,?,?,'REPORTED') RETURNING id"""
                ).use { statement ->
                    statement.setLong(1, orderId)
                    statement.setString(2, request.paymentMethod)
                    statement.setString(3, "${bank.code} - ${bank.name}")
                    statement.setString(4, bank.code)
                    statement.setString(5, originPhone)
                    statement.setBoolean(6, request.paidFromDifferentPhone)
                    statement.setString(7, proofPath)
                    statement.setString(8, reference)
                    statement.setBigDecimal(9, totalBd)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
                connection.prepareStatement(
                    """INSERT INTO solicitudes_verificacion_pago(payment_id,order_id,user_id,origin_bank_code,origin_bank_name_snapshot,origin_phone,reference_number,proof_file_path)
                       VALUES (?,?,?,?,?,?,?,?)"""
                ).use { statement ->
                    statement.setLong(1, paymentId)
                    statement.setLong(2, orderId)
                    statement.setLong(3, userId)
                    statement.setString(4, bank.code)
                    statement.setString(5, bank.name)
                    statement.setString(6, originPhone)
                    statement.setString(7, reference)
                    statement.setString(8, proofPath)
                    statement.executeUpdate()
                }
                requiresVerification = true
            }

            val invoiceNumber = "IS-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}-${orderId.toString().padStart(6, '0')}"
            connection.prepareStatement("INSERT INTO facturas(order_id,invoice_number) VALUES (?,?)").use {
                it.setLong(1, orderId)
                it.setString(2, invoiceNumber)
                it.executeUpdate()
            }

            if (isCredit) {
                val rate = creditRate ?: throw AppException("No hay tasa BCV disponible para Crédito Kredi+.")
                val principalUsdExact = creditPrincipalUsdExact ?: throw AppException("No fue posible calcular el crédito.")
                val principalUsd = principalUsdExact.toDouble()
                val installmentCount = creditInstallmentCount ?: 2
                val currentCreditLevel = creditAccountSnapshot(connection, userId).level
                val loanId = connection.prepareStatement(
                    """INSERT INTO prestamos_credito(user_id,order_id,level,principal_usd,principal_bs,bcv_rate,installment_count,status)
                       VALUES (?,?,?,?,?,?,?, 'ACTIVE') RETURNING id"""
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setLong(2, orderId)
                    statement.setInt(3, currentCreditLevel)
                    statement.setBigDecimal(4, principalUsdExact)
                    statement.setBigDecimal(5, totalBd)
                    statement.setBigDecimal(6, MoneyMath.rate(rate))
                    statement.setInt(7, installmentCount)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
                val usdInstallments = MoneyMath.splitExact(principalUsdExact, installmentCount, MoneyMath.USD_SCALE)
                val bsInstallments = MoneyMath.splitExact(totalBd, installmentCount, MoneyMath.VES_SCALE)
                for (number in 1..installmentCount) {
                    val amountUsd = usdInstallments[number - 1]
                    val amountBs = bsInstallments[number - 1]
                    val dueDate = LocalDate.now().plusDays(30L * number)
                    connection.prepareStatement(
                        "INSERT INTO cuotas_credito(loan_id,installment_number,amount_usd,original_amount_bs,due_date,status) VALUES (?,?,?,?,?,'PENDING')"
                    ).use { statement ->
                        statement.setLong(1, loanId)
                        statement.setInt(2, number)
                        statement.setBigDecimal(3, amountUsd)
                        statement.setBigDecimal(4, amountBs)
                        statement.setObject(5, dueDate)
                        statement.executeUpdate()
                    }
                }
                val balanceAfter = (creditAccountSnapshot(connection, userId).availableUsd).coerceAtLeast(0.0)
                recordCredimpulsoTransaction(
                    connection = connection, userId = userId, type = "PURCHASE", amountUsd = principalUsd, amountBs = total, bcvRate = rate,
                    balanceBeforeUsd = balanceAfter + principalUsd, balanceAfterUsd = balanceAfter,
                    description = "Compra ${invoiceNumber} financiada en $installmentCount cuotas", loanId = loanId, orderId = orderId, performedBy = userId
                )
                audit(connection, userId, "USER_USED_CREDIMPULSO", "ORDER", orderId.toString(), "US$ ${"%.2f".format(principalUsd)} · $installmentCount cuotas")
            } else {
                val bank = selectedBank ?: throw AppException("Selecciona un banco válido.")
                audit(connection, userId, "USER_REPORTED_PAYMENT", "ORDER", orderId.toString(), "Referencia $reference · ${bank.code}")
            }

            verifyAndSealInvoice(connection, orderId)
            PurchaseDto(orderId, total, itemCount, orderStatus, invoiceNumber, System.currentTimeMillis(), request.paymentMethod, effectiveFairId, paymentDestinationDto(connection, effectiveFairId), subtotalBd.toDouble(), discountBd.toDouble(), appliedPromotion?.name)
        }

        if (request.paymentMethod == "CREDIMPULSO") {
            val principal = creditPrincipalUsd ?: 0.0
            val installmentCount = creditInstallmentCount ?: 2
            notifyUsers(
                listOf(userId),
                "Compra aprobada con Crédito Kredi+",
                "Tu compra ${purchase.invoiceNumber} fue financiada en $installmentCount cuotas. Crédito utilizado: US$ ${"%.2f".format(principal)}.",
                "CREDIMPULSO_PURCHASE",
                mapOf("orderId" to purchase.id.toString(), "invoiceNumber" to purchase.invoiceNumber, "principalUsd" to "%.2f".format(principal))
            )
            notifyAdmins(
                "Nueva compra con Crédito Kredi+",
                "La factura ${purchase.invoiceNumber} fue financiada con Crédito Kredi+ Nivel 1.",
                "CREDIMPULSO_PURCHASE",
                mapOf("orderId" to purchase.id.toString(), "invoiceNumber" to purchase.invoiceNumber)
            )
        } else {
            notifyAdmins(
                "Pago reportado",
                "Se reportó el pago de la factura ${purchase.invoiceNumber}.",
                "PAYMENT_REPORTED",
                mapOf("orderId" to purchase.id.toString(), "invoiceNumber" to purchase.invoiceNumber)
            )
            if (requiresVerification) {
                notifyAdmins(
                    "Verificación de pago requerida",
                    "La factura ${purchase.invoiceNumber} tiene un comprobante adjunto pendiente de validación bancaria.",
                    "PAYMENT_VERIFICATION_REQUIRED",
                    buildMap {
                        put("orderId", purchase.id.toString())
                        put("invoiceNumber", purchase.invoiceNumber)
                        notificationProofPath?.let { put("proofPath", it) }
                    }
                )
            }
        }
        return purchase
    }

    fun invoice(userId: Long, purchaseId: Long): InvoiceDto = database.dataSource.connection.use { connection ->
        val administrator = currentRole(userId) == "ADMIN"
        val ownerFilter = if (administrator) "" else " AND o.user_id=?"
        val header = connection.prepareStatement(
            """
            SELECT o.id,o.total,o.created_at,u.email,up.full_name,
                   up.first_name,up.middle_name,up.last_name,up.second_last_name,up.birth_date,up.employment_type,
                   f.name,f.place,i.invoice_number,
                   CASE WHEN o.financing_type='CREDIMPULSO' THEN 'CREDIMPULSO' ELSE p.method END,
                   CASE WHEN o.financing_type='CREDIMPULSO' THEN '' ELSE p.reference_number END,
                   CASE WHEN o.financing_type='CREDIMPULSO' THEN '' ELSE p.origin_bank END,
                   CASE WHEN o.financing_type='CREDIMPULSO' THEN '' ELSE p.origin_phone END,
                   up.phone,up.state,up.municipality,up.parish,up.community,up.address,
                   dv.document_type,dv.document_number,
                   COALESCE(o.original_subtotal,o.subtotal),o.discount_amount,o.promotion_name_snapshot
            FROM pedidos o
            JOIN usuarios u ON u.id=o.user_id
            JOIN perfiles_usuario up ON up.user_id=u.id
            JOIN jornadas f ON f.id=o.fair_id
            JOIN facturas i ON i.order_id=o.id
            LEFT JOIN LATERAL (
                SELECT method,reference_number,origin_bank,origin_phone FROM pagos WHERE order_id=o.id ORDER BY created_at DESC LIMIT 1
            ) p ON TRUE
            LEFT JOIN LATERAL (
                SELECT document_type,document_number FROM verificaciones_documentos WHERE user_id=u.id ORDER BY submitted_at DESC LIMIT 1
            ) dv ON TRUE
            WHERE o.id=?$ownerFilter
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, purchaseId)
            if (!administrator) statement.setLong(2, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("No se encontró la factura.")
                InvoiceHeader(
                    id = result.getLong(1), total = result.getBigDecimal(2).toDouble(), createdAt = result.getObject(3, OffsetDateTime::class.java),
                    email = result.getString(4), customerName = result.getString(5),
                    firstName = result.getString(6).orEmpty(), middleName = result.getString(7).orEmpty(), lastName = result.getString(8).orEmpty(),
                    secondLastName = result.getString(9).orEmpty(), birthDate = result.getDate(10)?.toLocalDate()?.toString().orEmpty(),
                    employmentType = result.getString(11).orEmpty(), fairName = result.getString(12), fairPlace = result.getString(13),
                    invoiceNumber = result.getString(14), paymentMethod = result.getString(15), reference = result.getString(16).orEmpty(),
                    originBank = result.getString(17).orEmpty(), originPhone = result.getString(18).orEmpty(), customerPhone = result.getString(19).orEmpty(),
                    state = result.getString(20).orEmpty(), municipality = result.getString(21).orEmpty(), parish = result.getString(22).orEmpty(),
                    community = result.getString(23).orEmpty(), address = result.getString(24).orEmpty(),
                    documentType = result.getString(25).orEmpty(), documentNumber = result.getString(26).orEmpty(),
                    subtotal = result.getBigDecimal(27)?.toDouble() ?: result.getBigDecimal(2).toDouble(),
                    discountAmount = result.getBigDecimal(28)?.toDouble() ?: 0.0, promotionName = result.getString(29)
                )
            }
        }
        val lines = connection.prepareStatement(
            """
            SELECT name_snapshot,unit_snapshot,quantity,unit_price
            FROM (
                SELECT 1 AS source_order,id,product_name_snapshot AS name_snapshot,unit_snapshot,quantity,unit_price
                FROM items_pedido WHERE order_id=?
                UNION ALL
                SELECT 2 AS source_order,id,combo_name_snapshot AS name_snapshot,'Combo' AS unit_snapshot,quantity,unit_price
                FROM combos_pedido WHERE order_id=?
            ) invoice_lines
            ORDER BY source_order,id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, purchaseId)
            statement.setLong(2, purchaseId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(InvoiceLineDto(result.getString(1), result.getString(2), result.getInt(3), result.getBigDecimal(4).toDouble()))
            } }
        }
        val integrity = verifyAndSealInvoice(connection, purchaseId)
        InvoiceDto(
            purchaseId = header.id,
            invoiceNumber = header.invoiceNumber,
            createdAtMillis = header.createdAt.toInstant().toEpochMilli(),
            customerName = header.customerName,
            customerEmail = header.email,
            fairName = header.fairName,
            fairPlace = header.fairPlace,
            paymentMethod = paymentLabel(header.paymentMethod),
            paymentReference = header.reference,
            paymentInstructions = paymentInstructions(connection, purchaseId),
            lines = lines,
            total = header.total,
            subtotal = header.subtotal,
            discountAmount = header.discountAmount,
            promotionName = header.promotionName,
            customerFirstName = header.firstName,
            customerMiddleName = header.middleName,
            customerLastName = header.lastName,
            customerSecondLastName = header.secondLastName,
            customerBirthDate = header.birthDate,
            customerEmploymentType = header.employmentType,
            customerDocumentType = header.documentType,
            customerDocument = header.documentNumber,
            customerPhone = header.customerPhone,
            customerState = header.state,
            customerMunicipality = header.municipality,
            customerParish = header.parish,
            customerCommunity = header.community,
            customerAddress = header.address,
            paymentOriginBank = header.originBank,
            paymentOriginPhone = header.originPhone,
            integrity = integrity
        )
    }


    fun createUserPaymentReport(userId: Long, request: CreateUserPaymentReportRequest): UserPaymentReportDto {
        val reportId = database.transaction { connection ->
            val targetType = request.targetType.trim().uppercase()
            if (targetType !in setOf("ORDER", "CREDIT_INSTALLMENT")) {
                throw AppException("Selecciona un destino de pago válido.")
            }

            val target = when (targetType) {
                "ORDER" -> {
                    val orderId = request.orderId ?: throw AppException("Selecciona el pedido que pagaste.")
                    connection.prepareStatement(
                        """SELECT o.id,o.total,COALESCE(i.invoice_number,'PED-' || o.id::text),o.status
                           FROM pedidos o LEFT JOIN facturas i ON i.order_id=o.id
                           WHERE o.id=? AND o.user_id=?"""
                    ).use { statement ->
                        statement.setLong(1, orderId)
                        statement.setLong(2, userId)
                        statement.executeQuery().use { result ->
                            if (!result.next()) throw NotFoundException("No se encontró el pedido seleccionado.")
                            val status = result.getString(4).orEmpty().uppercase()
                            if (status.contains("VERIFICADO") || status in setOf("PAID", "COMPLETED", "APPROVED")) {
                                throw AppException("Este pedido ya aparece como pagado.")
                            }
                            val existingPaymentStatus = connection.prepareStatement(
                                "SELECT status FROM pagos WHERE order_id=? ORDER BY created_at DESC LIMIT 1"
                            ).use { paymentStatement ->
                                paymentStatement.setLong(1, orderId)
                                paymentStatement.executeQuery().use { paymentResult ->
                                    if (paymentResult.next()) paymentResult.getString(1).orEmpty().uppercase() else ""
                                }
                            }
                            if (existingPaymentStatus == "REPORTED") {
                                throw AppException("Este pedido ya tiene un pago pendiente de revisión.")
                            }
                            if (existingPaymentStatus in setOf("VERIFIED", "APPROVED", "PAID")) {
                                throw AppException("Este pedido ya aparece como pagado.")
                            }
                            PaymentReportTarget(
                                orderId = result.getLong(1),
                                installmentId = null,
                                invoiceNumber = result.getString(3),
                                installmentNumber = null,
                                expectedAmountBs = result.getBigDecimal(2)
                            )
                        }
                    }
                }
                else -> {
                    val installmentId = request.installmentId ?: throw AppException("Selecciona la cuota que pagaste.")
                    connection.prepareStatement(
                        """SELECT cl.order_id,ci.id,COALESCE(i.invoice_number,cl.invoice_number,'CRED-' || cl.id::text),
                                  ci.installment_number,ci.original_amount_bs,ci.status
                           FROM cuotas_credito ci
                           JOIN prestamos_credito cl ON cl.id=ci.loan_id
                           LEFT JOIN facturas i ON i.order_id=cl.order_id
                           WHERE ci.id=? AND cl.user_id=?"""
                    ).use { statement ->
                        statement.setLong(1, installmentId)
                        statement.setLong(2, userId)
                        statement.executeQuery().use { result ->
                            if (!result.next()) throw NotFoundException("No se encontró la cuota seleccionada.")
                            if (result.getString(6).equals("PAID", true)) {
                                throw AppException("Esta cuota ya aparece como pagada.")
                            }
                            val orderId = result.getLong(1).takeUnless { result.wasNull() }
                            PaymentReportTarget(
                                orderId = orderId,
                                installmentId = result.getLong(2),
                                invoiceNumber = result.getString(3),
                                installmentNumber = result.getInt(4),
                                expectedAmountBs = result.getBigDecimal(5)
                            )
                        }
                    }
                }
            }

            val targetPaymentId = if (targetType == "ORDER") {
                target.orderId ?: throw AppException("No se encontró el pedido asociado al pago.")
            } else {
                target.installmentId ?: throw AppException("No se encontró la cuota asociada al pago.")
            }
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
                statement.setString(1, "payment-report:$targetType:$targetPaymentId")
                statement.executeQuery().use { result -> result.next() }
            }
            val openReportExists = connection.prepareStatement(
                if (targetType == "ORDER") {
                    "SELECT 1 FROM reportes_pago_usuario WHERE target_type='ORDER' AND order_id=? AND status='REPORTED'"
                } else {
                    "SELECT 1 FROM reportes_pago_usuario WHERE installment_id=? AND status='REPORTED'"
                }
            ).use { statement ->
                statement.setLong(1, targetPaymentId)
                statement.executeQuery().use { it.next() }
            }
            if (openReportExists) throw AppException("Ya existe un reporte pendiente para este pago.")

            val amount = MoneyMath.ves(request.amountBs, "Monto reportado")
            if (amount.signum() <= 0) throw AppException("El monto reportado debe ser mayor que cero.")
            val amountComparison = PaymentReviewPolicy.compare(amount, target.expectedAmountBs)
            val reference = PaymentReviewPolicy.normalizeReference(request.referenceNumber)
            val originPhone = normalizeVenezuelanPhone(request.originPhone)
                ?: throw AppException("Ingresa el número de celular desde donde salió el pago.")
            val registeredPhone = connection.prepareStatement("SELECT phone FROM perfiles_usuario WHERE user_id=?").use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result -> if (result.next()) normalizeVenezuelanPhone(result.getString(1).orEmpty()) else null }
            }

            val bank = connection.prepareStatement("SELECT code,name FROM directorio_bancos WHERE code=? AND active=TRUE").use { statement ->
                statement.setString(1, request.originBankCode.trim())
                statement.executeQuery().use { result ->
                    if (!result.next()) throw AppException("Selecciona un banco válido.")
                    BankDto(result.getString(1), result.getString(2))
                }
            }

            val method = PaymentReviewPolicy.normalizeMethod(request.method)

            val proofPath = request.proofPath.trim()
            val expectedPrefix = "payment-proofs/$userId/"
            if (!proofPath.startsWith(expectedPrefix)) {
                throw AppException("Adjunta un comprobante válido desde tu cuenta.")
            }
            val proofFile = File(uploadRoot, proofPath).canonicalFile
            val canonicalRoot = uploadRoot.canonicalFile
            if (!proofFile.path.startsWith(canonicalRoot.path + File.separator) || !proofFile.isFile || proofFile.length() <= 0L) {
                throw AppException("No se encontró el comprobante adjunto.")
            }
            val proofHash = sha256File(proofFile)
            val visualProof = analyzePaymentProofImage(proofFile)

            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
                statement.setString(1, "payment-reference:${bank.code}:$reference")
                statement.executeQuery().use { result -> result.next() }
            }
            val sameReferenceCount = connection.prepareStatement(
                """SELECT
                       (SELECT COUNT(*) FROM reportes_pago_usuario
                        WHERE origin_bank_code=? AND reference_number=? AND status IN ('REPORTED','VERIFIED')) +
                       (SELECT COUNT(*) FROM pagos
                        WHERE COALESCE(origin_bank_code,'')=? AND reference_number=?
                          AND UPPER(status) IN ('REPORTED','VERIFIED','APPROVED','PAID'))"""
            ).use { statement ->
                statement.setString(1, bank.code); statement.setString(2, reference)
                statement.setString(3, bank.code); statement.setString(4, reference)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
            if (sameReferenceCount > 0) {
                throw AppException("Esta referencia bancaria ya fue registrada. Verifica el comprobante antes de continuar.")
            }
            val sameReferenceOtherUsers = connection.prepareStatement(
                """SELECT COUNT(*) FROM (
                       SELECT user_id FROM reportes_pago_usuario WHERE origin_bank_code=? AND reference_number=? AND user_id<>?
                       UNION ALL
                       SELECT o.user_id FROM pagos p JOIN pedidos o ON o.id=p.order_id
                       WHERE COALESCE(p.origin_bank_code,'')=? AND p.reference_number=? AND o.user_id<>?
                   ) duplicated"""
            ).use { statement ->
                statement.setString(1, bank.code); statement.setString(2, reference); statement.setLong(3, userId)
                statement.setString(4, bank.code); statement.setString(5, reference); statement.setLong(6, userId)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
                statement.setString(1, "payment-proof:$proofHash")
                statement.executeQuery().use { result -> result.next() }
            }
            val proofDuplicateCount = connection.prepareStatement(
                "SELECT COUNT(*) FROM reportes_pago_usuario WHERE proof_sha256=? AND status IN ('REPORTED','VERIFIED')"
            ).use { statement ->
                statement.setString(1, proofHash)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
            if (proofDuplicateCount > 0) {
                throw AppException("Esta imagen de comprobante ya fue utilizada en otro reporte.")
            }
            val proofVisualNearDuplicateCount = visualProof.visualHash?.let { candidateHash ->
                connection.prepareStatement(
                    "SELECT proof_visual_hash FROM reportes_pago_usuario WHERE proof_visual_hash IS NOT NULL ORDER BY created_at DESC LIMIT 1000"
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        var matches = 0
                        while (result.next()) {
                            val previousHash = result.getString(1) ?: continue
                            if (visualHashDistance(candidateHash, previousHash) <= 6) matches += 1
                        }
                        matches
                    }
                }
            } ?: 0
            val priorRejected = connection.prepareStatement(
                "SELECT COUNT(*) FROM reportes_pago_usuario WHERE user_id=? AND status='REJECTED'"
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
            val reportsLast24Hours = connection.prepareStatement(
                "SELECT COUNT(*) FROM reportes_pago_usuario WHERE user_id=? AND created_at>=NOW()-INTERVAL '24 hours'"
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }

            val assessment = PaymentFraudEngine.evaluate(
                PaymentFraudInput(
                    expectedAmountBs = target.expectedAmountBs.toDouble(),
                    reportedAmountBs = amount.toDouble(),
                    referenceNumber = reference,
                    bankExists = true,
                    proofPresent = true,
                    proofDuplicateCount = proofDuplicateCount,
                    proofVisualNearDuplicateCount = proofVisualNearDuplicateCount,
                    proofImageReadable = visualProof.readable,
                    sameReferenceCount = sameReferenceCount,
                    sameReferenceOtherUsersCount = sameReferenceOtherUsers,
                    paidFromDifferentPhone = request.paidFromDifferentPhone,
                    originPhoneMatchesProfile = registeredPhone != null && registeredPhone == originPhone,
                    priorRejectedReports = priorRejected,
                    reportsLast24Hours = reportsLast24Hours
                )
            )

            val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }?.take(700)
            val newId = connection.prepareStatement(
                """INSERT INTO reportes_pago_usuario(
                       user_id,target_type,order_id,installment_id,invoice_number,installment_number,
                       method,origin_bank_code,origin_bank_name_snapshot,origin_phone,reference_number,
                       amount_reported_bs,expected_amount_bs,paid_from_different_phone,proof_file_path,proof_sha256,
                       proof_visual_hash,user_notes,risk_score,risk_level,confidence_percent,recommendation,reasons_json,
                       suggestions_json,algorithm_version,amount_difference_bs,amount_difference_percent,decision_version
                   ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id"""
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, targetType)
                if (target.orderId == null) statement.setNull(3, java.sql.Types.BIGINT) else statement.setLong(3, target.orderId)
                if (target.installmentId == null) statement.setNull(4, java.sql.Types.BIGINT) else statement.setLong(4, target.installmentId)
                statement.setString(5, target.invoiceNumber)
                if (target.installmentNumber == null) statement.setNull(6, java.sql.Types.INTEGER) else statement.setInt(6, target.installmentNumber)
                statement.setString(7, method)
                statement.setString(8, bank.code)
                statement.setString(9, bank.name)
                statement.setString(10, originPhone)
                statement.setString(11, reference)
                statement.setBigDecimal(12, amount)
                statement.setBigDecimal(13, target.expectedAmountBs)
                statement.setBoolean(14, request.paidFromDifferentPhone)
                statement.setString(15, proofPath)
                statement.setString(16, proofHash)
                if (visualProof.visualHash == null) statement.setNull(17, java.sql.Types.CHAR) else statement.setString(17, visualProof.visualHash)
                statement.setString(18, notes)
                statement.setInt(19, assessment.riskScore)
                statement.setString(20, assessment.riskLevel)
                statement.setInt(21, assessment.confidencePercent)
                statement.setString(22, assessment.recommendation)
                statement.setString(23, gson.toJson(assessment.reasons))
                statement.setString(24, gson.toJson(assessment.suggestions))
                statement.setString(25, assessment.algorithmVersion)
                statement.setBigDecimal(26, amountComparison.differenceBs)
                statement.setBigDecimal(27, amountComparison.differencePercent)
                statement.setString(28, "PAYMENT-REVIEW-6.5.4")
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
            audit(
                connection,
                userId,
                "USER_REPORTED_PAYMENT_ADVANCED",
                "PAYMENT_REPORT",
                newId.toString(),
                "${target.invoiceNumber} · riesgo ${assessment.riskLevel} ${assessment.riskScore}/100"
            )
            newId
        }

        val created = userPaymentReport(userId, reportId)
        val highRisk = created.assessment.riskLevel in setOf("HIGH", "CRITICAL")
        notifyAdmins(
            title = if (highRisk) "Alerta de pago con riesgo elevado" else "Nuevo pago por verificar",
            body = "${created.invoiceNumber}: reporte recibido con riesgo ${created.assessment.riskLevel.lowercase()} (${created.assessment.riskScore}/100).",
            type = if (highRisk) "USER_PAYMENT_FRAUD_ALERT" else "USER_PAYMENT_REPORT",
            data = mapOf(
                "reportId" to reportId.toString(),
                "riskLevel" to created.assessment.riskLevel,
                "riskScore" to created.assessment.riskScore.toString()
            )
        )
        return created
    }

    fun userPaymentReports(userId: Long): List<UserPaymentReportDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT * FROM reportes_pago_usuario WHERE user_id=? ORDER BY created_at DESC LIMIT 250"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(userPaymentReportDto(result)) } }
        }
    }

    private fun userPaymentReport(userId: Long, reportId: Long): UserPaymentReportDto = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM reportes_pago_usuario WHERE id=? AND user_id=?").use { statement ->
            statement.setLong(1, reportId)
            statement.setLong(2, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("No se encontró el reporte de pago.")
                userPaymentReportDto(result)
            }
        }
    }

    fun adminUserPaymentReports(): List<AdminUserPaymentReportDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT r.*,u.username,up.full_name,up.phone
               FROM reportes_pago_usuario r
               JOIN usuarios u ON u.id=r.user_id
               LEFT JOIN perfiles_usuario up ON up.user_id=u.id
               ORDER BY CASE r.status WHEN 'REPORTED' THEN 0 WHEN 'REJECTED' THEN 1 ELSE 2 END,
                        r.risk_score DESC,r.created_at DESC
               LIMIT 1000"""
        ).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(
                    AdminUserPaymentReportDto(
                        id = result.getLong("id"),
                        userId = result.getLong("user_id"),
                        username = result.getString("username").orEmpty(),
                        buyerName = result.getString("full_name").orEmpty().ifBlank { result.getString("username").orEmpty() },
                        buyerPhone = result.getString("phone").orEmpty(),
                        targetType = result.getString("target_type"),
                        orderId = result.getLong("order_id").takeUnless { result.wasNull() },
                        installmentId = result.getLong("installment_id").takeUnless { result.wasNull() },
                        invoiceNumber = result.getString("invoice_number"),
                        installmentNumber = result.getInt("installment_number").takeUnless { result.wasNull() },
                        method = result.getString("method"),
                        bank = "${result.getString("origin_bank_code")} - ${result.getString("origin_bank_name_snapshot")}",
                        originPhone = result.getString("origin_phone"),
                        referenceNumber = result.getString("reference_number"),
                        proofUrl = publicUrl(result.getString("proof_file_path")).orEmpty(),
                        amountReportedBs = result.getBigDecimal("amount_reported_bs").toDouble(),
                        expectedAmountBs = result.getBigDecimal("expected_amount_bs").toDouble(),
                        paidFromDifferentPhone = result.getBoolean("paid_from_different_phone"),
                        userNotes = result.getString("user_notes"),
                        status = result.getString("status"),
                        assessment = paymentFraudAssessmentDto(result),
                        bankConfirmed = result.getBoolean("bank_confirmed"),
                        adminNotes = result.getString("admin_notes"),
                        createdAt = result.getObject("created_at", OffsetDateTime::class.java).toString(),
                        reviewedAt = result.getObject("reviewed_at", OffsetDateTime::class.java)?.toString()
                    )
                )
            } }
        }
    }

    fun decideUserPaymentReport(adminId: Long, reportId: Long, request: UserPaymentReportDecisionRequest) {
        val preflight = database.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT status,user_id,target_type,order_id,installment_id,invoice_number,risk_score,
                          method,origin_bank_code,origin_bank_name_snapshot,origin_phone,reference_number,
                          amount_reported_bs,expected_amount_bs,paid_from_different_phone,proof_file_path
                   FROM reportes_pago_usuario WHERE id=?"""
            ).use { statement ->
                statement.setLong(1, reportId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("El reporte de pago no existe.")
                    PaymentReportDecisionRow(
                        status = result.getString(1),
                        userId = result.getLong(2),
                        targetType = result.getString(3),
                        orderId = result.getLong(4).takeUnless { result.wasNull() },
                        installmentId = result.getLong(5).takeUnless { result.wasNull() },
                        invoiceNumber = result.getString(6),
                        riskScore = result.getInt(7),
                        method = result.getString(8),
                        originBankCode = result.getString(9),
                        originBankName = result.getString(10),
                        originPhone = result.getString(11),
                        referenceNumber = result.getString(12),
                        amountReportedBs = result.getBigDecimal(13),
                        expectedAmountBs = result.getBigDecimal(14),
                        paidFromDifferentPhone = result.getBoolean(15),
                        proofFilePath = result.getString(16)
                    )
                }
            }
        }
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }?.take(1000)
        val amountComparison = PaymentReviewPolicy.validateDecision(
            currentStatus = preflight.status,
            approved = request.approved,
            bankConfirmed = request.bankConfirmed,
            riskScore = preflight.riskScore,
            reportedAmountBs = preflight.amountReportedBs,
            expectedAmountBs = preflight.expectedAmountBs,
            notes = notes
        )

        var installmentNotice: CreditInstallmentPaymentNotice? = null
        database.transaction { connection ->
            connection.prepareStatement(
                """UPDATE reportes_pago_usuario
                   SET status=?,bank_confirmed=?,admin_notes=?,reviewed_by=?,reviewed_at=NOW(),updated_at=NOW(),
                       amount_difference_bs=?,amount_difference_percent=?,decision_version='PAYMENT-REVIEW-6.5.4'
                   WHERE id=? AND status='REPORTED'"""
            ).use { statement ->
                statement.setString(1, if (request.approved) "VERIFIED" else "REJECTED")
                statement.setBoolean(2, request.approved && request.bankConfirmed)
                statement.setString(3, notes)
                statement.setLong(4, adminId)
                statement.setBigDecimal(5, amountComparison.differenceBs)
                statement.setBigDecimal(6, amountComparison.differencePercent)
                statement.setLong(7, reportId)
                if (statement.executeUpdate() != 1) throw AppException("El reporte fue revisado por otro administrador.")
            }
            if (preflight.targetType == "ORDER" && preflight.orderId != null) {
                val paymentStatus = if (request.approved) "VERIFIED" else "REJECTED"
                val existingPaymentUpdated = connection.prepareStatement(
                    """UPDATE pagos SET method=?,origin_bank=?,origin_bank_code=?,origin_phone=?,
                              paid_from_different_phone=?,proof_file_path=?,reference_number=?,amount_paid=?,
                              status=?,verified_by=?,verified_at=NOW(),transaction_at=NOW()
                       WHERE id=(SELECT id FROM pagos WHERE order_id=? ORDER BY created_at DESC LIMIT 1)"""
                ).use { statement ->
                    statement.setString(1, preflight.method)
                    statement.setString(2, "${preflight.originBankCode} - ${preflight.originBankName}")
                    statement.setString(3, preflight.originBankCode)
                    statement.setString(4, preflight.originPhone)
                    statement.setBoolean(5, preflight.paidFromDifferentPhone)
                    statement.setString(6, preflight.proofFilePath)
                    statement.setString(7, preflight.referenceNumber)
                    statement.setBigDecimal(8, preflight.amountReportedBs)
                    statement.setString(9, paymentStatus)
                    statement.setLong(10, adminId)
                    statement.setLong(11, preflight.orderId)
                    statement.executeUpdate()
                }
                if (existingPaymentUpdated == 0) {
                    connection.prepareStatement(
                        """INSERT INTO pagos(order_id,method,origin_bank,origin_bank_code,origin_phone,
                                   paid_from_different_phone,proof_file_path,reference_number,amount_paid,status,
                                   verified_by,verified_at,transaction_at)
                           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW())"""
                    ).use { statement ->
                        statement.setLong(1, preflight.orderId)
                        statement.setString(2, preflight.method)
                        statement.setString(3, "${preflight.originBankCode} - ${preflight.originBankName}")
                        statement.setString(4, preflight.originBankCode)
                        statement.setString(5, preflight.originPhone)
                        statement.setBoolean(6, preflight.paidFromDifferentPhone)
                        statement.setString(7, preflight.proofFilePath)
                        statement.setString(8, preflight.referenceNumber)
                        statement.setBigDecimal(9, preflight.amountReportedBs)
                        statement.setString(10, paymentStatus)
                        statement.setLong(11, adminId)
                        statement.setObject(12, OffsetDateTime.now(ZoneOffset.UTC))
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement(
                    "UPDATE pedidos SET status=?,updated_at=NOW() WHERE id=?"
                ).use { statement ->
                    statement.setString(1, if (request.approved) "Pago verificado" else "Pago rechazado")
                    statement.setLong(2, preflight.orderId)
                    statement.executeUpdate()
                }
            }
            if (request.approved && preflight.installmentId != null) {
                installmentNotice = markCreditInstallmentPaid(connection, adminId, preflight.installmentId)
            }
            audit(
                connection,
                adminId,
                if (request.approved) "ADMIN_VERIFIED_USER_PAYMENT_REPORT" else "ADMIN_REJECTED_USER_PAYMENT_REPORT",
                "PAYMENT_REPORT",
                reportId.toString(),
                "${preflight.invoiceNumber} · banca confirmada=${request.approved && request.bankConfirmed}" +
                    " · diferencia Bs ${amountComparison.differenceBs.toPlainString()} (${amountComparison.differencePercent.toPlainString()}%)" +
                    notes?.let { " · $it" }.orEmpty()
            )
        }
        installmentNotice?.let(::notifyCreditInstallmentPaid)
        notifyUsers(
            listOf(preflight.userId),
            if (request.approved) "Pago verificado" else "Reporte de pago rechazado",
            if (request.approved) "El pago de ${preflight.invoiceNumber} fue confirmado por el administrador."
            else "El reporte de ${preflight.invoiceNumber} fue rechazado. ${notes.orEmpty()}",
            if (request.approved) "USER_PAYMENT_REPORT_VERIFIED" else "USER_PAYMENT_REPORT_REJECTED",
            mapOf("reportId" to reportId.toString())
        )
    }

    private fun userPaymentReportDto(result: ResultSet): UserPaymentReportDto = UserPaymentReportDto(
        id = result.getLong("id"),
        targetType = result.getString("target_type"),
        orderId = result.getLong("order_id").takeUnless { result.wasNull() },
        installmentId = result.getLong("installment_id").takeUnless { result.wasNull() },
        invoiceNumber = result.getString("invoice_number"),
        installmentNumber = result.getInt("installment_number").takeUnless { result.wasNull() },
        method = result.getString("method"),
        bank = "${result.getString("origin_bank_code")} - ${result.getString("origin_bank_name_snapshot")}",
        originPhone = result.getString("origin_phone"),
        referenceNumber = result.getString("reference_number"),
        proofUrl = publicUrl(result.getString("proof_file_path")).orEmpty(),
        amountReportedBs = result.getBigDecimal("amount_reported_bs").toDouble(),
        expectedAmountBs = result.getBigDecimal("expected_amount_bs").toDouble(),
        status = result.getString("status"),
        assessment = paymentFraudAssessmentDto(result),
        bankConfirmed = result.getBoolean("bank_confirmed"),
        adminNotes = result.getString("admin_notes"),
        createdAt = result.getObject("created_at", OffsetDateTime::class.java).toString(),
        reviewedAt = result.getObject("reviewed_at", OffsetDateTime::class.java)?.toString()
    )

    private fun paymentFraudAssessmentDto(result: ResultSet): PaymentFraudAssessmentDto = PaymentFraudAssessmentDto(
        riskScore = result.getInt("risk_score"),
        riskLevel = result.getString("risk_level"),
        confidencePercent = result.getInt("confidence_percent"),
        recommendation = result.getString("recommendation").orEmpty(),
        reasons = decodeJsonStringList(result.getString("reasons_json")),
        suggestions = decodeJsonStringList(result.getString("suggestions_json")),
        algorithmVersion = result.getString("algorithm_version"),
        bankConfirmationRequired = true
    )

    private fun analyzePaymentProofImage(file: File): VisualProofAnalysis = runCatching {
        val image = ImageIO.read(file) ?: return@runCatching VisualProofAnalysis(null, false)
        if (image.width <= 0 || image.height <= 0) return@runCatching VisualProofAnalysis(null, false)
        var hash = 0L
        var bit = 63
        for (y in 0 until 8) {
            val sampleY = ((y + 0.5) * image.height / 8.0).toInt().coerceIn(0, image.height - 1)
            for (x in 0 until 8) {
                val leftX = ((x + 0.5) * image.width / 9.0).toInt().coerceIn(0, image.width - 1)
                val rightX = ((x + 1.5) * image.width / 9.0).toInt().coerceIn(0, image.width - 1)
                val left = image.getRGB(leftX, sampleY)
                val right = image.getRGB(rightX, sampleY)
                if (pixelLuminance(left) > pixelLuminance(right)) hash = hash or (1L shl bit)
                bit -= 1
            }
        }
        val hashText = java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
        VisualProofAnalysis(hashText, image.width >= 320 && image.height >= 320)
    }.getOrDefault(VisualProofAnalysis(null, false))

    private fun pixelLuminance(argb: Int): Int {
        val red = (argb shr 16) and 0xff
        val green = (argb shr 8) and 0xff
        val blue = argb and 0xff
        return red * 299 + green * 587 + blue * 114
    }

    private fun visualHashDistance(first: String, second: String): Int = runCatching {
        val firstValue = java.lang.Long.parseUnsignedLong(first, 16)
        val secondValue = java.lang.Long.parseUnsignedLong(second, 16)
        java.lang.Long.bitCount(firstValue xor secondValue)
    }.getOrDefault(Int.MAX_VALUE)

    private fun decodeJsonStringList(raw: String?): List<String> = runCatching {
        gson.fromJson(raw.orEmpty(), Array<String>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    fun paymentReviews(): List<AdminPaymentReviewDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT p.id,o.id,COALESCE(i.invoice_number,'PED-' || o.id::text),u.id,up.full_name,up.phone,
                   p.method,COALESCE(NULLIF(p.origin_bank,''),b.name,''),COALESCE(p.origin_phone,''),
                   p.reference_number,COALESCE(p.proof_file_path,''),p.amount_paid,o.item_count,o.status,p.status,
                   COALESCE(p.transaction_at,p.created_at),p.created_at,
                   (COALESCE(p.paid_from_different_phone,FALSE) OR COALESCE(p.proof_file_path,'')<>'') AS requires_proof_review
            FROM pagos p
            JOIN pedidos o ON o.id=p.order_id
            JOIN usuarios u ON u.id=o.user_id
            JOIN perfiles_usuario up ON up.user_id=u.id
            LEFT JOIN facturas i ON i.order_id=o.id
            LEFT JOIN directorio_bancos b ON b.code=p.origin_bank_code
            ORDER BY
                CASE p.status WHEN 'REPORTED' THEN 0 WHEN 'REJECTED' THEN 1 ELSE 2 END,
                p.created_at DESC
            LIMIT 1500
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(
                    AdminPaymentReviewDto(
                        paymentId = result.getLong(1),
                        orderId = result.getLong(2),
                        invoiceNumber = result.getString(3),
                        userId = result.getLong(4),
                        buyerName = result.getString(5),
                        buyerPhone = result.getString(6).orEmpty(),
                        method = result.getString(7),
                        bank = result.getString(8).orEmpty(),
                        originPhone = result.getString(9).orEmpty(),
                        referenceNumber = result.getString(10).orEmpty(),
                        proofUrl = publicUrl(result.getString(11)).orEmpty(),
                        amountBs = result.getBigDecimal(12).toDouble(),
                        itemCount = result.getInt(13),
                        orderStatus = result.getString(14),
                        paymentStatus = result.getString(15),
                        transactionAt = result.getObject(16, OffsetDateTime::class.java).toString(),
                        createdAt = result.getObject(17, OffsetDateTime::class.java).toString(),
                        requiresProofReview = result.getBoolean(18)
                    )
                )
            } }
        }
    }

    fun decidePayment(adminId: Long, paymentId: Long, request: PaymentVerificationDecisionRequest) {
        val outcome = database.transaction { connection ->
            val row = connection.prepareStatement(
                """
                SELECT p.status,p.order_id,o.user_id,COALESCE(i.invoice_number,'PED-' || o.id::text),
                       r.id AS verification_request_id
                FROM pagos p
                JOIN pedidos o ON o.id=p.order_id
                LEFT JOIN facturas i ON i.order_id=o.id
                LEFT JOIN solicitudes_verificacion_pago r ON r.payment_id=p.id
                WHERE p.id=? FOR UPDATE OF p
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, paymentId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("El pago no existe.")
                    PaymentDecisionRow(
                        status = result.getString(1),
                        orderId = result.getLong(2),
                        userId = result.getLong(3),
                        invoiceNumber = result.getString(4),
                        verificationRequestId = result.getLong(5).takeUnless { result.wasNull() }
                    )
                }
            }
            if (row.status != "REPORTED") throw AppException("Este pago ya fue revisado.")

            val newStatus = if (request.approved) "VERIFIED" else "REJECTED"
            connection.prepareStatement(
                "UPDATE pagos SET status=?,verified_by=?,verified_at=NOW() WHERE id=? AND status='REPORTED'"
            ).use { statement ->
                statement.setString(1, newStatus)
                statement.setLong(2, adminId)
                statement.setLong(3, paymentId)
                if (statement.executeUpdate() != 1) throw AppException("El pago fue revisado por otro administrador.")
            }
            connection.prepareStatement("UPDATE pedidos SET status=?,updated_at=NOW() WHERE id=?").use { statement ->
                statement.setString(1, if (request.approved) "Pago verificado" else "Pago rechazado")
                statement.setLong(2, row.orderId)
                statement.executeUpdate()
            }

            row.verificationRequestId?.let { verificationId ->
                connection.prepareStatement(
                    """
                    INSERT INTO decisiones_verificacion_pago(request_id,approved,notes,reviewed_by)
                    VALUES (?,?,?,?) ON CONFLICT (request_id) DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, verificationId)
                    statement.setBoolean(2, request.approved)
                    statement.setString(3, request.notes?.trim()?.takeIf { it.isNotBlank() })
                    statement.setLong(4, adminId)
                    statement.executeUpdate()
                }
            }
            audit(
                connection,
                adminId,
                if (request.approved) "ADMIN_VERIFIED_PAYMENT" else "ADMIN_REJECTED_PAYMENT",
                "PAYMENT",
                paymentId.toString(),
                "${row.invoiceNumber}${request.notes?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
            )
            row
        }
        notifyUsers(
            listOf(outcome.userId),
            if (request.approved) "Pago verificado" else "Pago rechazado",
            if (request.approved) "El pago de la factura ${outcome.invoiceNumber} fue verificado."
            else "El pago de la factura ${outcome.invoiceNumber} fue rechazado. Revisa los datos y contacta al administrador.",
            if (request.approved) "PAYMENT_VERIFIED" else "PAYMENT_REJECTED"
        )
    }

    fun paymentVerifications(): List<PaymentVerificationDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT r.id,r.order_id,i.invoice_number,up.full_name,up.phone,r.origin_bank_code,r.origin_bank_name_snapshot,
                   r.origin_phone,r.reference_number,r.proof_file_path,
                   CASE WHEN d.id IS NULL THEN 'PENDING' WHEN d.approved THEN 'VERIFIED' ELSE 'REJECTED' END AS status,
                   r.created_at
            FROM solicitudes_verificacion_pago r
            JOIN usuarios u ON u.id=r.user_id
            JOIN perfiles_usuario up ON up.user_id=u.id
            JOIN facturas i ON i.order_id=r.order_id
            LEFT JOIN decisiones_verificacion_pago d ON d.request_id=r.id
            ORDER BY r.created_at DESC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(
                    PaymentVerificationDto(
                        id = result.getLong(1), orderId = result.getLong(2), invoiceNumber = result.getString(3),
                        buyerName = result.getString(4), buyerPhone = result.getString(5),
                        bank = "${result.getString(6)} - ${result.getString(7)}", originPhone = result.getString(8),
                        referenceNumber = result.getString(9), proofUrl = publicUrl(result.getString(10)).orEmpty(),
                        status = result.getString(11), createdAt = result.getObject(12, OffsetDateTime::class.java).toString()
                    )
                )
            } }
        }
    }

    fun decidePaymentVerification(adminId: Long, requestId: Long, request: PaymentVerificationDecisionRequest) {
        val outcome = database.transaction { connection ->
            val data = connection.prepareStatement(
                """SELECT r.payment_id,r.user_id,r.order_id,i.invoice_number
                   FROM solicitudes_verificacion_pago r JOIN facturas i ON i.order_id=r.order_id WHERE r.id=? FOR UPDATE"""
            ).use { statement ->
                statement.setLong(1, requestId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("La solicitud de verificación no existe.")
                    listOf(result.getLong(1), result.getLong(2), result.getLong(3)) to result.getString(4)
                }
            }
            val exists = connection.prepareStatement("SELECT 1 FROM decisiones_verificacion_pago WHERE request_id=?").use { statement ->
                statement.setLong(1, requestId); statement.executeQuery().use { it.next() }
            }
            if (exists) throw AppException("Esta verificación ya fue resuelta y no puede modificarse.")
            connection.prepareStatement("INSERT INTO decisiones_verificacion_pago(request_id,approved,notes,reviewed_by) VALUES (?,?,?,?)").use { statement ->
                statement.setLong(1, requestId); statement.setBoolean(2, request.approved); statement.setString(3, request.notes?.trim()?.takeIf { it.isNotEmpty() }); statement.setLong(4, adminId); statement.executeUpdate()
            }
            val paymentUpdated = connection.prepareStatement(
                "UPDATE pagos SET status=?,verified_by=?,verified_at=NOW() WHERE id=? AND status='REPORTED'"
            ).use { statement ->
                statement.setString(1, if (request.approved) "VERIFIED" else "REJECTED")
                statement.setLong(2, adminId)
                statement.setLong(3, data.first[0])
                statement.executeUpdate()
            }
            if (paymentUpdated != 1) throw AppException("El comprobante ya no está pendiente de verificación.")
            connection.prepareStatement("UPDATE pedidos SET status=?,updated_at=NOW() WHERE id=?").use { statement ->
                statement.setString(1, if (request.approved) "Pago verificado" else "Pago rechazado")
                statement.setLong(2, data.first[2])
                statement.executeUpdate()
            }
            audit(connection, adminId, if (request.approved) "ADMIN_VERIFIED_PAYMENT" else "ADMIN_REJECTED_PAYMENT", "PAYMENT_VERIFICATION", requestId.toString(), request.notes)
            Triple(data.first[1], data.second, request.approved)
        }
        notifyUsers(
            listOf(outcome.first),
            if (outcome.third) "Pago verificado" else "Pago requiere revisión",
            if (outcome.third) "El pago de la factura ${outcome.second} fue verificado." else "El pago de la factura ${outcome.second} no fue aprobado. Contacta al administrador.",
            if (outcome.third) "PAYMENT_VERIFIED" else "PAYMENT_REJECTED"
        )
    }

    private data class PaymentDecisionRow(
        val status: String,
        val orderId: Long,
        val userId: Long,
        val invoiceNumber: String,
        val verificationRequestId: Long?
    )

    private data class DecodedInvoiceQrRegistration(
        val recordType: String,
        val invoiceNumber: String,
        val purchaseId: Long?,
        val storageJson: String
    )

    /**
     * Acepta todos los formatos emitidos históricamente por la aplicación.
     *
     * - IS3: formato compacto actual (GZIP + Base64 URL-safe).
     * - GZIP_BASE64: sobre JSON utilizado por las versiones anteriores.
     * - JSON plano: formato legado inicial.
     *
     * El contenido compacto IS3 se normaliza a un objeto JSON antes de guardarlo
     * porque registros_escaneo_qr.raw_payload es JSONB. El checksum sigue calculándose
     * sobre el texto original leído por la cámara para conservar la inmutabilidad.
     */
    private fun decodeInvoiceQrForRegistration(raw: String): DecodedInvoiceQrRegistration {
        if (raw.startsWith("IS3:", ignoreCase = false)) {
            val encoded = raw.removePrefix("IS3:").trim()
            if (encoded.isBlank()) throw AppException("El código QR no contiene datos de factura.")
            val compactJson = runCatching {
                val compressed = Base64.getUrlDecoder().decode(encoded)
                GZIPInputStream(ByteArrayInputStream(compressed))
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }.getOrElse { throw AppException("No fue posible decodificar la factura del código QR.") }
            if (compactJson.length > 200_000) throw AppException("El contenido del código QR supera el tamaño permitido.")

            val values = runCatching { JsonParser.parseString(compactJson).asJsonArray }
                .getOrElse { throw AppException("Los datos internos del código QR no son válidos.") }
            if (values.size() < 28) throw AppException("El código QR no contiene una factura completa.")

            val invoiceNumber = runCatching { values[1].asString.trim() }.getOrDefault("")
            val purchaseId = runCatching { values[0].takeUnless { it.isJsonNull }?.asLong }.getOrNull()
            if (invoiceNumber.isBlank()) throw AppException("La factura del código QR no tiene número válido.")

            val normalizedInvoice = JsonObject().apply {
                addProperty("invoiceNumber", invoiceNumber)
                if (purchaseId != null) addProperty("purchaseId", purchaseId)
            }
            val normalized = JsonObject().apply {
                addProperty("recordType", "CREDICASH_INVOICE")
                addProperty("encoding", "IS3")
                addProperty("payload", raw)
                add("invoice", normalizedInvoice)
            }
            return DecodedInvoiceQrRegistration(
                recordType = "CREDICASH_INVOICE",
                invoiceNumber = invoiceNumber,
                purchaseId = purchaseId,
                storageJson = normalized.toString()
            )
        }

        val root = runCatching { JsonParser.parseString(raw).asJsonObject }
            .getOrElse { throw AppException("El código QR no contiene una factura válida de Kredi+.") }
        val decodedRoot = if (root.get("encoding")?.asString == "GZIP_BASE64") {
            if (root.get("recordType")?.asString !in setOf("CREDICASH_INVOICE", "IMPULSO_SOCIAL_INVOICE")) {
                throw AppException("El código QR no pertenece a una factura de Kredi+.")
            }
            val encoded = root.get("payload")?.asString?.takeIf { it.isNotBlank() }
                ?: throw AppException("El código QR no contiene datos comprimidos de factura.")
            val decodedJson = runCatching {
                val compressed = Base64.getDecoder().decode(encoded)
                GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { throw AppException("No fue posible leer los datos protegidos del código QR.") }
            if (decodedJson.length > 200_000) throw AppException("El contenido del código QR supera el tamaño permitido.")
            runCatching { JsonParser.parseString(decodedJson).asJsonObject }
                .getOrElse { throw AppException("Los datos internos del código QR no son válidos.") }
        } else {
            root
        }

        val recordType = decodedRoot.get("recordType")?.asString.orEmpty()
        if (recordType !in setOf("CREDICASH_INVOICE", "IMPULSO_SOCIAL_INVOICE")) throw AppException("El código QR no pertenece a una factura de Kredi+.")
        val invoice = decodedRoot.getAsJsonObject("invoice") ?: throw AppException("El código QR no contiene datos de factura.")
        val invoiceNumber = runCatching { invoice.get("invoiceNumber")?.asString?.trim().orEmpty() }.getOrDefault("")
        val purchaseId = runCatching { invoice.get("purchaseId")?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()
        if (invoiceNumber.isBlank()) throw AppException("La factura del código QR no tiene número válido.")
        return DecodedInvoiceQrRegistration(recordType, invoiceNumber, purchaseId, raw)
    }

    fun registerQrScan(adminId: Long, request: InvoiceQrScanRequest): QrScanRecordDto {
        val raw = request.rawPayload.trim()
        if (raw.isBlank() || raw.length > 60_000) throw AppException("El código QR no contiene una factura válida.")
        val decoded = decodeInvoiceQrForRegistration(raw)
        val checksum = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return database.transaction { connection ->
            val existing = connection.prepareStatement("SELECT id,created_at FROM registros_escaneo_qr WHERE payload_checksum=?").use { statement ->
                statement.setString(1, checksum)
                statement.executeQuery().use { result -> if (result.next()) QrScanRecordDto(result.getLong(1), checksum, result.getObject(2, OffsetDateTime::class.java).toString()) else null }
            }
            if (existing != null) return@transaction existing
            connection.prepareStatement(
                """INSERT INTO registros_escaneo_qr(record_type,invoice_number,purchase_id,payload_checksum,raw_payload,scanned_by)
                   VALUES (?,?,?,?,?::jsonb,?) RETURNING id,created_at"""
            ).use { statement ->
                statement.setString(1, decoded.recordType)
                statement.setString(2, decoded.invoiceNumber)
                if (decoded.purchaseId == null) statement.setNull(3, java.sql.Types.BIGINT) else statement.setLong(3, decoded.purchaseId)
                statement.setString(4, checksum)
                statement.setString(5, decoded.storageJson)
                statement.setLong(6, adminId)
                statement.executeQuery().use { result ->
                    result.next()
                    QrScanRecordDto(result.getLong(1), checksum, result.getObject(2, OffsetDateTime::class.java).toString())
                }
            }
        }
    }

    fun scannedInvoiceRecords(): List<ScannedInvoiceRecordDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT r.id,r.invoice_number,r.purchase_id,r.scanned_by,
                      COALESCE(NULLIF(TRIM(CONCAT_WS(' ',p.first_name,p.middle_name,p.last_name,p.second_last_name)),''),p.full_name,u.email),
                      r.created_at
               FROM registros_escaneo_qr r
               JOIN usuarios u ON u.id=r.scanned_by
               LEFT JOIN perfiles_usuario p ON p.user_id=u.id
               ORDER BY r.created_at DESC LIMIT 500"""
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(
            ScannedInvoiceRecordDto(
                id = rs.getLong(1), invoiceNumber = rs.getString(2),
                purchaseId = rs.getLong(3).takeUnless { rs.wasNull() },
                scannedBy = rs.getLong(4), scannerName = rs.getString(5),
                createdAt = rs.getObject(6, OffsetDateTime::class.java).toString()
            )
        ) } } }
    }

    fun inventoryDemand(): List<InventoryDemandDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """WITH individual AS (
                   SELECT ip.product_id,SUM(ip.quantity)::int AS qty,COUNT(DISTINCT ip.order_id)::int AS orders
                   FROM items_pedido ip JOIN pedidos o ON o.id=ip.order_id
                   WHERE o.status NOT IN ('CANCELLED','REJECTED') GROUP BY ip.product_id
               ), combo AS (
                   SELECT pc.product_id,SUM(cp.quantity*pc.quantity)::int AS qty,COUNT(DISTINCT cp.order_id)::int AS orders
                   FROM combos_pedido cp JOIN productos_combo pc ON pc.combo_id=cp.combo_id
                   JOIN pedidos o ON o.id=cp.order_id
                   WHERE o.status NOT IN ('CANCELLED','REJECTED') GROUP BY pc.product_id
               )
               SELECT p.id,p.name,p.unit,p.stock,COALESCE(i.qty,0),COALESCE(c.qty,0),
                      COALESCE(i.qty,0)+COALESCE(c.qty,0),COALESCE(i.orders,0)+COALESCE(c.orders,0)
               FROM productos p LEFT JOIN individual i ON i.product_id=p.id LEFT JOIN combo c ON c.product_id=p.id
               ORDER BY (COALESCE(i.qty,0)+COALESCE(c.qty,0)) DESC,p.name"""
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(
            InventoryDemandDto(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getInt(8))
        ) } } }
    }

    fun inventoryIntegrity(): List<InventoryIntegrityItemDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT product_id,product_name,recorded_stock,movement_stock,difference,minimum_stock,low_stock,consistent
               FROM vista_integridad_inventario
               WHERE consistent=FALSE OR low_stock=TRUE
               ORDER BY consistent ASC,ABS(difference) DESC,low_stock DESC,product_name"""
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            InventoryIntegrityItemDto(
                                productId = result.getLong(1),
                                productName = result.getString(2),
                                recordedStock = result.getInt(3),
                                movementStock = result.getInt(4),
                                difference = result.getInt(5),
                                minimumStock = result.getInt(6),
                                lowStock = result.getBoolean(7),
                                consistent = result.getBoolean(8)
                            )
                        )
                    }
                }
            }
        }
    }

    fun operationalQualitySummary(): OperationalQualitySummaryDto = database.dataSource.connection.use { connection ->
        fun count(sql: String): Int = connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
        OperationalQualitySummaryDto(
            generatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            pendingPaymentReports = count("SELECT COUNT(*) FROM reportes_pago_usuario WHERE status='REPORTED'"),
            highRiskPaymentReports = count("SELECT COUNT(*) FROM reportes_pago_usuario WHERE status='REPORTED' AND risk_level IN ('HIGH','CRITICAL')"),
            paymentAmountDifferences = count("SELECT COUNT(*) FROM reportes_pago_usuario WHERE status='REPORTED' AND amount_difference_percent>1"),
            pendingUserVerifications = count("SELECT COUNT(*) FROM usuarios WHERE verification_status NOT IN ('VERIFIED','REJECTED')"),
            inventoryInconsistencies = count("SELECT COUNT(*) FROM vista_integridad_inventario WHERE consistent=FALSE"),
            lowStockProducts = count("SELECT COUNT(*) FROM vista_integridad_inventario WHERE low_stock=TRUE"),
            activeSessions = count("SELECT COUNT(*) FROM sesiones_usuario WHERE revoked_at IS NULL AND expires_at>NOW()")
        )
    }

    fun adminPurchases(): List<PurchaseDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT o.id,o.total,o.item_count,o.status,COALESCE(i.invoice_number,'PED-' || o.id::text),o.created_at,
                      CASE WHEN o.financing_type='CREDIMPULSO' THEN 'CREDIMPULSO' ELSE p.method END,
                      o.fair_id AS payment_fair_id,f.name AS payment_fair_name,f.payment_mode AS payment_mode,
                      f.business_id,b.commercial_name AS business_commercial_name,b.legal_name AS business_legal_name,
                      b.rif AS business_rif,b.logo_path AS business_logo_path,
                      pd.mobile_bank,pd.mobile_phone,pd.mobile_identity_number,pd.mobile_holder_name,
                      pd.bank_name,pd.bank_account_type,pd.bank_account_number,pd.bank_identity_number,pd.bank_holder_name
               FROM pedidos o
               JOIN jornadas f ON f.id=o.fair_id
               LEFT JOIN negocios_asociados b ON b.id=f.business_id
               LEFT JOIN detalles_pago_jornada pd ON pd.fair_id=f.id
               LEFT JOIN facturas i ON i.order_id=o.id
               LEFT JOIN LATERAL (SELECT method FROM pagos WHERE order_id=o.id ORDER BY created_at DESC LIMIT 1) p ON TRUE
               ORDER BY o.created_at DESC LIMIT 1000"""
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(
            PurchaseDto(rs.getLong(1),rs.getBigDecimal(2).toDouble(),rs.getInt(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime::class.java).toInstant().toEpochMilli(),rs.getString(7),rs.getLong("payment_fair_id"),paymentDestinationDto(rs))
        ) } } }
    }

    fun storeUpload(category: String, owner: String, originalName: String, bytes: ByteArray): StoredUpload {
        val safeCategory = category.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeOwner = owner.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val validated = UploadPolicy.validate(safeCategory, bytes)
        val directory = File(uploadRoot, "$safeCategory/$safeOwner").apply {
            if (!exists() && !mkdirs()) throw AppException("No se pudo preparar el almacenamiento del archivo.")
        }
        val fileName = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.${validated.extension}"
        val file = File(directory, fileName)
        val temporary = File(directory, ".$fileName.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = false)
                temporary.delete()
            }
        } catch (error: Exception) {
            temporary.delete()
            file.delete()
            logger.warn("No se pudo guardar el archivo de carga segura", error)
            throw AppException("No se pudo guardar el archivo. Inténtalo nuevamente.")
        }
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            file.delete()
            throw AppException("La carga del archivo no se completó correctamente.")
        }
        val relative = file.relativeTo(uploadRoot).invariantSeparatorsPath
        logger.info(
            "Archivo almacenado: categoria={} propietario={} tipo={} bytes={} sha256={}",
            safeCategory, safeOwner, validated.mimeType, bytes.size, validated.sha256.take(12)
        )
        return StoredUpload(relative, file)
    }


    fun roleExperience(userId: Long): RoleExperienceDto {
        val user = me(userId)
        val role = Roles.canonical(user.role)
        val subRole = database.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COALESCE(admin_subrole,CASE WHEN role='ADMIN' THEN 'GENERAL' WHEN role='ACCOUNTANT' THEN 'ACCOUNTING' WHEN role='WAREHOUSE' THEN 'WAREHOUSE' ELSE 'USER' END) FROM usuarios WHERE id=?"
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1) else "USER" }
            }
        }
        val permissions = permissionsFor(role, subRole)
        val firstName = user.firstName?.takeIf { it.isNotBlank() }
            ?: user.fullName.substringBefore(' ').takeIf { it.isNotBlank() }
            ?: user.username
        val metrics = mutableListOf<RoleMetricDto>()
        val tasks = mutableListOf<RoleTaskDto>()
        val alerts = mutableListOf<String>()

        when (role) {
            Roles.BENEFICIARY -> {
                val credit = runCatching { creditSummary(userId) }.getOrNull()
                val orders = runCatching { purchases(userId) }.getOrDefault(emptyList())
                val reports = runCatching { userPaymentReports(userId) }.getOrDefault(emptyList())
                val pendingOrders = orders.count { it.status.uppercase() !in setOf("PAID","COMPLETED","APPROVED","VERIFIED","DELIVERED") }
                val next = credit?.nextInstallment
                metrics += RoleMetricDto("line", "Línea disponible", "US$ ${"%.2f".format(credit?.availableUsd ?: 0.0)}", "Nivel ${credit?.level ?: 1}", "GOOD")
                metrics += RoleMetricDto("next", "Próxima cuota", next?.let { "US$ ${"%.2f".format(it.amountUsd)}" } ?: "Sin cuotas", next?.dueDate ?: "Al día", if (next == null) "GOOD" else "WARNING")
                metrics += RoleMetricDto("orders", "Pedidos activos", pendingOrders.toString(), "${orders.size} registrados")
                metrics += RoleMetricDto("score", "Historial crediticio", "${credit?.creditScorePercentage ?: 100}%", credit?.creditHistoryStatus ?: "Activo", if ((credit?.creditScorePercentage ?: 100) >= 75) "GOOD" else "WARNING")
                if (next != null) tasks += RoleTaskDto("next-payment", "Próxima cuota", "Vence el ${next.dueDate}", "HIGH", "CREDIMPULSO", "Ver cuota")
                val pendingReports = reports.count { it.status == "REPORTED" }
                if (pendingReports > 0) tasks += RoleTaskDto("payment-review", "Pagos en revisión", "$pendingReports comprobante(s) esperando confirmación", "MEDIUM", "REQUESTS", "Ver reportes", pendingReports)
                if (user.verificationStatus != "VERIFIED") tasks += RoleTaskDto("verification", "Completa tu verificación", "Actualiza tus documentos para utilizar todas las funciones.", "HIGH", "PROFILE", "Verificar")
                if (pendingOrders > 0) tasks += RoleTaskDto("orders", "Pedidos pendientes", "Revisa el estado de tus compras y facturas.", "MEDIUM", "REQUESTS", "Abrir facturas", pendingOrders)
                if ((credit?.creditScorePercentage ?: 100) < 70) alerts += "Paga las próximas cuotas a tiempo para recuperar tu perfil crediticio."
            }
            Roles.ADMIN -> {
                database.dataSource.connection.use { connection ->
                    fun count(sql: String): Int = connection.prepareStatement(sql).use { it.executeQuery().use { r -> r.next(); r.getInt(1) } }
                    val pendingUsers = count("SELECT COUNT(*) FROM verificaciones_documentos WHERE status='PENDING'")
                    val pendingReports = count("SELECT COUNT(*) FROM reportes_pago_usuario WHERE status='REPORTED'")
                    val highRisk = count("SELECT COUNT(*) FROM reportes_pago_usuario WHERE status='REPORTED' AND risk_score>=60")
                    val activePlans = count("SELECT COUNT(*) FROM prestamos_credito WHERE UPPER(status)='ACTIVE'")
                    val pendingApprovals = count("SELECT COUNT(*) FROM solicitudes_doble_aprobacion WHERE status='PENDING'")
                    metrics += RoleMetricDto("payments", "Pagos por verificar", pendingReports.toString(), "$highRisk con riesgo alto", if (highRisk > 0) "DANGER" else "WARNING")
                    metrics += RoleMetricDto("users", "Usuarios por validar", pendingUsers.toString(), "Identidad y documentos")
                    metrics += RoleMetricDto("plans", "Planes activos", activePlans.toString(), "Compras y cuotas en seguimiento")
                    metrics += RoleMetricDto("approvals", "Doble autorización", pendingApprovals.toString(), "Operaciones sensibles")
                    if (highRisk > 0) tasks += RoleTaskDto("fraud", "Alertas de posible fraude", "$highRisk reporte(s) requieren revisión prioritaria.", "CRITICAL", "PAYMENT_PROOFS", "Revisar alertas", highRisk)
                    if (pendingReports > 0) tasks += RoleTaskDto("payments", "Pagos por verificar", "Valida referencia y comprobante contra el medio externo informado.", "HIGH", "PAYMENT_PROOFS", "Abrir pagos", pendingReports)
                    if (pendingUsers > 0) tasks += RoleTaskDto("users", "Identidades pendientes", "Revisa documentos y estado de cuenta.", "MEDIUM", "VERIFICATIONS", "Revisar usuarios", pendingUsers)
                    if (activePlans > 0) tasks += RoleTaskDto("plans", "Planes de compra", "Supervisa compras activas, cuotas y estados.", "MEDIUM", "CREDITS", "Ver planes", activePlans)
                    if (pendingApprovals > 0) tasks += RoleTaskDto("approvals", "Operaciones sensibles", "Hay solicitudes pendientes de una segunda aprobación.", "HIGH", "SENSITIVE_APPROVALS", "Ver solicitudes", pendingApprovals)
                }
            }
            Roles.ACCOUNTANT -> {
                val reconciliation = accountantReconciliation()
                val reports = adminUserPaymentReports()
                val loans = adminCreditLoans()
                val installments = loans.flatMap { it.installments }
                val pendingProofs = reports.count { it.status == "REPORTED" }
                val pendingReconciliation = reconciliation.manualReview + reconciliation.differences + reconciliation.duplicates
                val activeInstallments = installments.count { it.status.uppercase() in setOf("PENDING", "CURRENT") }
                val overdueInstallments = installments.count { it.status.uppercase() == "OVERDUE" }
                metrics += RoleMetricDto("proofs", "Comprobantes pendientes", pendingProofs.toString(), "Reportados por usuarios", if (pendingProofs > 0) "WARNING" else "GOOD")
                metrics += RoleMetricDto("reconciliation", "Conciliación pendiente", pendingReconciliation.toString(), "${reconciliation.matched} conciliados", if (pendingReconciliation > 0) "WARNING" else "GOOD")
                metrics += RoleMetricDto("installments", "Cuotas activas", activeInstallments.toString(), "${loans.count { it.status.uppercase() == "ACTIVE" }} planes activos", "GOOD")
                metrics += RoleMetricDto("overdue", "Cuotas vencidas", overdueInstallments.toString(), "Seguimiento documental", if (overdueInstallments > 0) "DANGER" else "GOOD")
                if (pendingProofs > 0) tasks += RoleTaskDto("proofs", "Revisar comprobantes", "$pendingProofs comprobante(s) esperan validación.", "HIGH", "PAYMENT_PROOFS", "Abrir comprobantes", pendingProofs)
                if (pendingReconciliation > 0) tasks += RoleTaskDto("reconciliation", "Resolver conciliación", "Hay diferencias, duplicados o casos manuales pendientes.", "HIGH", "ACCOUNTANT_RECONCILIATION", "Conciliar", pendingReconciliation)
                if (overdueInstallments > 0) tasks += RoleTaskDto("installments", "Revisar cuotas vencidas", "$overdueInstallments cuota(s) requieren seguimiento.", "MEDIUM", "INSTALLMENTS", "Ver cuotas", overdueInstallments)
                alerts += "Kredi+ registra y valida comprobantes; no custodia saldos ni ejecuta transferencias desde el módulo Contador."
            }
            Roles.WAREHOUSE -> {
                database.dataSource.connection.use { connection ->
                    fun count(sql: String): Int = connection.prepareStatement(sql).use { statement ->
                        statement.executeQuery().use { result -> result.next(); result.getInt(1) }
                    }
                    val availableProducts = count("SELECT COUNT(*) FROM productos WHERE active=TRUE AND stock>0")
                    val lowStock = count("SELECT COUNT(*) FROM productos WHERE active=TRUE AND stock<=minimum_stock")
                    val pendingOrders = count(
                        "SELECT COUNT(*) FROM pedidos WHERE UPPER(status) IN ('PAGO VERIFICADO','CRÉDITO ACTIVO','CREDITO ACTIVO','APPROVED','PAID','VERIFIED')"
                    )
                    val preparingOrders = count("SELECT COUNT(*) FROM pedidos WHERE UPPER(status) IN ('PREPARING','IN_PREPARATION')")
                    val readyOrders = count("SELECT COUNT(*) FROM pedidos WHERE UPPER(status)='READY'")
                    metrics += RoleMetricDto("available", "Productos disponibles", availableProducts.toString(), "Con existencia", "GOOD")
                    metrics += RoleMetricDto("low-stock", "Existencia baja", lowStock.toString(), "Según el mínimo definido en inventario", if (lowStock > 0) "WARNING" else "GOOD")
                    metrics += RoleMetricDto("pending-orders", "Pedidos por preparar", pendingOrders.toString(), "$preparingOrders en preparación", if (pendingOrders > 0) "WARNING" else "GOOD")
                    metrics += RoleMetricDto("ready-orders", "Listos para entregar", readyOrders.toString(), "Despachos preparados", "GOOD")
                    if (pendingOrders > 0) tasks += RoleTaskDto("prepare-orders", "Preparar pedidos", "Hay $pendingOrders pedido(s) aprobados esperando preparación.", "HIGH", "REQUESTS", "Abrir pedidos", pendingOrders)
                    if (lowStock > 0) tasks += RoleTaskDto("low-stock", "Revisar existencia baja", "$lowStock producto(s) requieren reposición o conteo.", "MEDIUM", "INVENTORY", "Abrir inventario", lowStock)
                    if (readyOrders > 0) tasks += RoleTaskDto("dispatch", "Confirmar entregas", "$readyOrders pedido(s) están listos para despacho.", "MEDIUM", "REQUESTS", "Confirmar salida", readyOrders)
                }
            }
        }
        return RoleExperienceDto(role, subRole, permissions, "Hola, $firstName", metrics, tasks, alerts)
    }

    fun adminUserDossier(targetUserId: Long): AdminUserDossierDto {
        val user = me(targetUserId)
        // El Administrador puede consultar el expediente de cualquier cuenta.
        // Importante: una cuenta operativa (ADMIN/WAREHOUSE/ACCOUNTANT) no debe
        // recibir una cuenta de crédito artificial al abrir su expediente.
        val credit = if (Roles.canonical(user.role) == Roles.BENEFICIARY) {
            runCatching { creditSummary(targetUserId) }.getOrNull()
        } else null
        val userPurchases = purchases(targetUserId)
        val reports = adminUserPaymentReports().filter { it.userId == targetUserId }
        val loans = adminCreditLoans().filter { it.userId == targetUserId }
        return database.dataSource.connection.use { connection ->
            fun count(sql: String): Int = connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, targetUserId)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
            val verifications = connection.prepareStatement(
                """SELECT id,user_id,document_type,document_number,front_file_path,back_file_path,selfie_file_path,
                          status,rejection_reason,submitted_at
                   FROM verificaciones_documentos
                   WHERE user_id=?
                   ORDER BY submitted_at DESC,id DESC"""
            ).use { statement ->
                statement.setLong(1, targetUserId)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(verificationDto(connection, result))
                } }
            }
            AdminUserDossierDto(
                user = user,
                credit = credit,
                purchases = userPurchases,
                paymentReports = reports,
                loans = loans,
                activeDevices = count("SELECT COUNT(*) FROM credenciales_biometricas_dispositivo WHERE user_id=? AND enabled=TRUE"),
                notificationCount = count("SELECT COUNT(*) FROM notificaciones WHERE user_id=?"),
                auditEventCount = count("SELECT COUNT(*) FROM registros_auditoria WHERE user_id=?"),
                verifications = verifications
            )
        }
    }

    fun accountantAuditTrail(accountantId: Long): List<Map<String, Any?>> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT ra.id,ra.user_id,COALESCE(u.username,''),ra.action,COALESCE(ra.entity_type,''),COALESCE(ra.entity_id,''),
                      COALESCE(ra.description,''),ra.created_at
               FROM registros_auditoria ra
               LEFT JOIN usuarios u ON u.id=ra.user_id
               WHERE ra.user_id=? OR ra.action LIKE 'ACCOUNTANT_%'
               ORDER BY ra.created_at DESC LIMIT 500"""
        ).use { statement ->
            statement.setLong(1, accountantId)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(linkedMapOf<String, Any?>(
                    "id" to result.getLong(1),
                    "userId" to result.getLong(2).takeIf { !result.wasNull() },
                    "username" to result.getString(3),
                    "action" to result.getString(4),
                    "entityType" to result.getString(5),
                    "entityId" to result.getString(6),
                    "description" to result.getString(7),
                    "createdAt" to result.getObject(8)?.toString()
                ))
            } }
        }
    }

    fun accountantReconciliation(): AccountantReconciliationDto = database.dataSource.connection.use { connection ->
        val items = connection.prepareStatement(
            """SELECT r.id,r.invoice_number,u.username,r.amount_reported_bs,r.expected_amount_bs,
                      r.reference_number,r.origin_bank_code || ' - ' || r.origin_bank_name_snapshot AS bank,
                      r.status,r.risk_level,r.recommendation,r.created_at,
                      c.status AS reconciliation_status,c.confidence_percent,
                      (SELECT COUNT(*) FROM reportes_pago_usuario x WHERE x.origin_bank_code=r.origin_bank_code AND x.reference_number=r.reference_number) AS reference_count
               FROM reportes_pago_usuario r
               JOIN usuarios u ON u.id=r.user_id
               LEFT JOIN conciliaciones_pago c ON c.payment_report_id=r.id
               ORDER BY CASE WHEN c.status IS NULL THEN 0 ELSE 1 END,r.risk_score DESC,r.created_at DESC
               LIMIT 1000"""
        ).use { statement -> statement.executeQuery().use { result -> buildList {
            while (result.next()) {
                val amountDifference = result.getBigDecimal("amount_reported_bs").subtract(result.getBigDecimal("expected_amount_bs")).abs()
                val stored = result.getString("reconciliation_status")
                val inferred = when {
                    stored != null -> stored
                    result.getInt("reference_count") > 1 -> "DUPLICATE"
                    amountDifference > BigDecimal("0.01") -> "DIFFERENCE"
                    result.getString("status") == "VERIFIED" -> "MATCHED"
                    result.getString("risk_level") in setOf("HIGH","CRITICAL") -> "MANUAL_REVIEW"
                    else -> "PROBABLE"
                }
                val confidence = if (stored != null) result.getInt("confidence_percent") else when (inferred) {
                    "MATCHED" -> 100; "PROBABLE" -> 75; "MANUAL_REVIEW" -> 45; else -> 20
                }
                add(ReconciliationItemDto(
                    reportId=result.getLong("id"), invoiceNumber=result.getString("invoice_number"), username=result.getString("username"),
                    amountReportedBs=result.getBigDecimal("amount_reported_bs").toDouble(), expectedAmountBs=result.getBigDecimal("expected_amount_bs").toDouble(),
                    referenceNumber=result.getString("reference_number"), bank=result.getString("bank"), paymentStatus=result.getString("status"),
                    reconciliationStatus=inferred, confidencePercent=confidence, riskLevel=result.getString("risk_level"),
                    recommendation=result.getString("recommendation").orEmpty(), createdAt=result.getObject("created_at", OffsetDateTime::class.java).toString()
                ))
            }
        } } }
        AccountantReconciliationDto(
            total=items.size,
            matched=items.count { it.reconciliationStatus=="MATCHED" },
            probable=items.count { it.reconciliationStatus=="PROBABLE" },
            manualReview=items.count { it.reconciliationStatus=="MANUAL_REVIEW" },
            differences=items.count { it.reconciliationStatus=="DIFFERENCE" },
            duplicates=items.count { it.reconciliationStatus=="DUPLICATE" },
            items=items
        )
    }

    fun decideReconciliation(accountantId: Long, reportId: Long, request: ReconciliationDecisionRequest) {
        val status = request.status.trim().uppercase()
        if (status !in setOf("MATCHED","PROBABLE","MANUAL_REVIEW","DIFFERENCE","DUPLICATE","UNRELATED")) throw AppException("Estado de conciliación inválido.")
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }?.take(1000)
        if (status in setOf("DIFFERENCE","DUPLICATE","UNRELATED") && notes.isNullOrBlank()) throw AppException("Explica la diferencia detectada.")
        database.transaction { connection ->
            connection.prepareStatement(
                """INSERT INTO conciliaciones_pago(payment_report_id,accountant_id,status,confidence_percent,notes,updated_at)
                   VALUES (?,?,?,?,?,NOW())
                   ON CONFLICT(payment_report_id) DO UPDATE SET accountant_id=EXCLUDED.accountant_id,status=EXCLUDED.status,
                       confidence_percent=EXCLUDED.confidence_percent,notes=EXCLUDED.notes,updated_at=NOW()"""
            ).use { statement ->
                statement.setLong(1, reportId); statement.setLong(2, accountantId); statement.setString(3, status)
                statement.setInt(4, request.confidencePercent.coerceIn(0,100)); statement.setString(5, notes); statement.executeUpdate()
            }
            audit(connection, accountantId, "ACCOUNTANT_RECONCILED_PAYMENT", "PAYMENT_REPORT", reportId.toString(), "$status · ${notes.orEmpty()}")
        }
    }

    fun monthlyClose(periodMonth: String): MonthlyCloseDto {
        if (!periodMonth.matches(Regex("^\\d{4}-\\d{2}$"))) throw AppException("El período debe usar el formato AAAA-MM.")
        val reconciliation = accountantReconciliation()
        return database.dataSource.connection.use { connection ->
            val storedStatus = connection.prepareStatement("SELECT status FROM cierres_contables WHERE period_month=?").use { statement ->
                statement.setString(1, periodMonth); statement.executeQuery().use { result -> if (result.next()) result.getString(1) else "OPEN" }
            }
            val pending = reconciliation.manualReview + reconciliation.differences + reconciliation.duplicates
            val invoicesRecorded = connection.prepareStatement("SELECT COUNT(*)=0 FROM pedidos p LEFT JOIN facturas f ON f.order_id=p.id WHERE to_char(p.created_at,'YYYY-MM')=? AND f.id IS NULL").use { statement ->
                statement.setString(1, periodMonth); statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
            }
            MonthlyCloseDto(
                periodMonth=periodMonth,
                status=storedStatus,
                synchronizedMovements=true,
                reconciledPayments=pending==0,
                invoicesRecorded=invoicesRecorded,
                budgetReviewed=true,
                pendingDifferences=pending,
                canClose=pending==0 && invoicesRecorded && storedStatus!="CLOSED"
            )
        }
    }

    fun closeMonthlyPeriod(accountantId: Long, request: MonthlyCloseRequest): MonthlyCloseDto {
        val preview = monthlyClose(request.periodMonth)
        if (!preview.canClose) throw AppException("Resuelve las diferencias pendientes antes de cerrar el período.")
        database.transaction { connection ->
            connection.prepareStatement(
                """INSERT INTO cierres_contables(period_month,accountant_id,status,pending_differences,summary_json,closed_at,updated_at)
                   VALUES (?,?,'CLOSED',0,?::jsonb,NOW(),NOW())
                   ON CONFLICT(period_month) DO UPDATE SET accountant_id=EXCLUDED.accountant_id,status='CLOSED',pending_differences=0,
                       summary_json=EXCLUDED.summary_json,closed_at=NOW(),updated_at=NOW()"""
            ).use { statement ->
                statement.setString(1, request.periodMonth); statement.setLong(2, accountantId)
                statement.setString(3, gson.toJson(mapOf("closedBy" to accountantId, "version" to CREDICASH_APP_VERSION))); statement.executeUpdate()
            }
            audit(connection, accountantId, "ACCOUNTANT_CLOSED_PERIOD", "ACCOUNTING_PERIOD", request.periodMonth, "Cierre mensual completado")
        }
        return monthlyClose(request.periodMonth)
    }

    fun sensitiveApprovals(): List<SensitiveApprovalDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT s.*,COALESCE(rp.full_name,ru.username) requester_name,COALESCE(ap.full_name,au.username) approver_name
               FROM solicitudes_doble_aprobacion s
               JOIN usuarios ru ON ru.id=s.requested_by LEFT JOIN perfiles_usuario rp ON rp.user_id=ru.id
               LEFT JOIN usuarios au ON au.id=s.approved_by LEFT JOIN perfiles_usuario ap ON ap.user_id=au.id
               ORDER BY CASE s.status WHEN 'PENDING' THEN 0 ELSE 1 END,s.created_at DESC LIMIT 500"""
        ).use { statement -> statement.executeQuery().use { result -> buildList {
            while (result.next()) add(SensitiveApprovalDto(
                id=result.getLong("id"),actionType=result.getString("action_type"),entityType=result.getString("entity_type"),
                entityId=result.getLong("entity_id").takeUnless { result.wasNull() },amountUsd=result.getBigDecimal("amount_usd")?.toDouble(),
                description=result.getString("description"),requestedBy=result.getLong("requested_by"),requesterName=result.getString("requester_name"),
                approvedBy=result.getLong("approved_by").takeUnless { result.wasNull() },approverName=result.getString("approver_name"),
                status=result.getString("status"),decisionNotes=result.getString("decision_notes"),
                createdAt=result.getObject("created_at",OffsetDateTime::class.java).toString(),reviewedAt=result.getObject("reviewed_at",OffsetDateTime::class.java)?.toString()
            ))
        } } }
    }

    fun createSensitiveApproval(userId: Long, request: CreateSensitiveApprovalRequest): SensitiveApprovalDto {
        val action = request.actionType.trim().uppercase().take(60)
        val description = request.description.trim().take(1500)
        if (action.isBlank() || description.isBlank()) throw AppException("Describe la operación sensible.")
        val id = database.transaction { connection ->
            connection.prepareStatement(
                """INSERT INTO solicitudes_doble_aprobacion(action_type,entity_type,entity_id,amount_usd,description,requested_by)
                   VALUES (?,?,?,?,?,?) RETURNING id"""
            ).use { statement ->
                statement.setString(1,action);statement.setString(2,request.entityType?.trim()?.uppercase()?.take(60));
                if (request.entityId == null) statement.setNull(3,java.sql.Types.BIGINT) else statement.setLong(3,request.entityId)
                if (request.amountUsd == null) statement.setNull(4,java.sql.Types.NUMERIC) else statement.setBigDecimal(4,BigDecimal.valueOf(request.amountUsd))
                statement.setString(5,description);statement.setLong(6,userId);statement.executeQuery().use { r -> r.next(); r.getLong(1) }
            }.also { audit(connection,userId,"SENSITIVE_APPROVAL_REQUESTED","DUAL_APPROVAL",it.toString(),action) }
        }
        return sensitiveApprovals().first { it.id==id }
    }

    fun decideSensitiveApproval(userId: Long, approvalId: Long, request: SensitiveApprovalDecisionRequest) {
        val notes=request.notes?.trim()?.takeIf { it.isNotBlank() }?.take(1000)
        if (!request.approved && notes.isNullOrBlank()) throw AppException("Explica por qué rechazas la solicitud.")
        database.transaction { connection ->
            data class SensitiveTarget(val requesterId: Long, val status: String, val actionType: String, val entityType: String?, val entityId: Long?)
            val target = connection.prepareStatement(
                "SELECT requested_by,status,action_type,entity_type,entity_id FROM solicitudes_doble_aprobacion WHERE id=? FOR UPDATE"
            ).use { statement ->
                statement.setLong(1,approvalId);statement.executeQuery().use { r ->
                    if(!r.next()) throw NotFoundException("La solicitud no existe.")
                    SensitiveTarget(
                        requesterId = r.getLong(1), status = r.getString(2), actionType = r.getString(3),
                        entityType = r.getString(4), entityId = r.getLong(5).takeUnless { r.wasNull() }
                    )
                }
            }
            if(target.status!="PENDING") throw AppException("La solicitud ya fue revisada.")
            if(target.requesterId==userId) throw AppException("La misma persona no puede solicitar y aprobar la operación.")
            connection.prepareStatement("UPDATE solicitudes_doble_aprobacion SET status=?,approved_by=?,decision_notes=?,reviewed_at=NOW() WHERE id=?").use { statement ->
                statement.setString(1,if(request.approved)"APPROVED" else "REJECTED");statement.setLong(2,userId);statement.setString(3,notes);statement.setLong(4,approvalId);statement.executeUpdate()
            }
            if (target.actionType == "BUDGET_COMMITMENT" && target.entityType == "BUDGET_COMMITMENT" && target.entityId != null) {
                val commitmentStatus = if (request.approved) "COMMITTED" else "CANCELLED"
                val updated = connection.prepareStatement(
                    """UPDATE compromisos_presupuestarios
                       SET estado=?,motivo_estado=?,updated_at=NOW()
                       WHERE id=? AND estado='PENDING'"""
                ).use { statement ->
                    statement.setString(1, commitmentStatus)
                    statement.setString(2, notes)
                    statement.setLong(3, target.entityId)
                    statement.executeUpdate()
                }
                if (updated != 1) throw AppException("El compromiso asociado ya no está pendiente.")
            }
            audit(connection,userId,if(request.approved)"SENSITIVE_APPROVAL_APPROVED" else "SENSITIVE_APPROVAL_REJECTED","DUAL_APPROVAL",approvalId.toString(),notes)
        }
    }

    /**
     * Credicash 7.2.2: el Contador crea directamente Administradores o Almacenistas.
     * No se promocionan beneficiarios registrados. ADMIN y WAREHOUSE pueden recibir,
     * de forma opcional, una segunda cuenta BENEFICIARY vinculada a la misma persona.
     */
    fun createStaffAccount(
        actorId: Long,
        request: StaffAccountCreationRequest,
        frontPath: String,
        backPath: String? = null,
        selfiePath: String
    ): StaffAccountCreationResultDto {
        requireAccountant(actorId)

        val role = when (request.role.trim().uppercase()) {
            "ADMIN", "ADMINISTRATOR", "ADMINISTRADOR" -> Roles.ADMIN
            "WAREHOUSE", "ALMACENISTA", "STOREKEEPER" -> Roles.WAREHOUSE
            else -> throw AppException("Solo puedes crear personal Administrador o Almacenista.")
        }
        val firstName = request.firstName.trim()
        val middleName = request.middleName.orEmpty().trim()
        val lastName = request.lastName.trim()
        val secondLastName = request.secondLastName.orEmpty().trim()
        if (firstName.length < 2 || lastName.length < 2) throw AppException("Ingresa nombre y apellido válidos.")
        listOf(firstName, middleName, lastName, secondLastName).filter { it.isNotBlank() }.forEach {
            if (!isSafePersonName(it)) throw AppException("Nombres y apellidos solo pueden contener letras y espacios.")
        }
        val fullName = listOf(firstName, middleName, lastName, secondLastName).filter { it.isNotBlank() }.joinToString(" ")
        val phone = normalizeVenezuelanPhone(request.phone)
            ?: throw AppException("Ingresa un celular venezolano válido, por ejemplo +58 412-1234567.")
        val birthDate = parseBirthDate(request.birthDate)
        if (ChronoUnit.YEARS.between(birthDate, LocalDate.now()) < 18) throw AppException("El personal debe ser mayor de 18 años.")

        val operationalUsername = validateUsername(request.operationalUsername)
        val operationalEmail = request.operationalEmail.trim().lowercase()
        if (!EMAIL_REGEX.matches(operationalEmail)) throw AppException("Ingresa un correo operativo válido.")
        PasswordPolicy.validationError(request.operationalPassword, operationalUsername, operationalEmail)?.let { throw AppException(it.replace("La contraseña", "La contraseña operativa")) }
        PinPolicy.validationError(request.operationalPin)?.let { throw AppException(it.replace("El PIN", "El PIN operativo")) }

        val documentType = request.documentType.trim().uppercase().ifBlank { "NATIONAL_ID" }
        if (documentType !in setOf("NATIONAL_ID", "PASSPORT")) throw AppException("Tipo de documento inválido. Usa Cédula o Pasaporte; el RIF es exclusivo de Negocios asociados.")
        val documentNumber = request.documentNumber.trim().uppercase().replace(Regex("[^A-Z0-9-]"), "")
        if (!documentNumber.matches(Regex("^[A-Z0-9-]{5,30}$"))) throw AppException("Ingresa un número de documento válido.")
        if (frontPath.isBlank() || selfiePath.isBlank()) throw AppException("Debes cargar documento de identidad y selfie del personal.")

        val allowedAdminSubRoles = setOf("GENERAL", "SUPERVISOR", "ANALYST", "SUPPORT", "AUDITOR", "ANTIFRAUD")
        val adminSubRole = if (role == Roles.ADMIN) {
            request.adminSubRole.orEmpty().trim().uppercase().ifBlank { "GENERAL" }.also {
                if (it !in allowedAdminSubRoles) throw AppException("Perfil administrativo inválido.")
            }
        } else "WAREHOUSE"

        val createBeneficiary = request.createBeneficiaryAccess
        val beneficiaryUsername = request.beneficiaryUsername.orEmpty().trim().let { if (createBeneficiary) validateUsername(it) else "" }
        val beneficiaryEmail = request.beneficiaryEmail.orEmpty().trim().lowercase()
        val beneficiaryPassword = request.beneficiaryPassword.orEmpty()
        val beneficiaryPin = request.beneficiaryPin.orEmpty()
        if (createBeneficiary) {
            if (!EMAIL_REGEX.matches(beneficiaryEmail)) throw AppException("Ingresa un correo válido para el acceso Beneficiario.")
            PasswordPolicy.validationError(beneficiaryPassword, beneficiaryUsername, beneficiaryEmail)?.let { throw AppException(it.replace("La contraseña", "La contraseña del Beneficiario")) }
            PinPolicy.validationError(beneficiaryPin)?.let { throw AppException(it.replace("El PIN", "El PIN del Beneficiario")) }
            if (beneficiaryUsername.equals(operationalUsername, true)) throw AppException("Los dos accesos deben utilizar usuarios distintos.")
            if (beneficiaryEmail.equals(operationalEmail, true)) throw AppException("Los dos accesos deben utilizar correos distintos.")
        }

        val personGroupId = UUID.randomUUID()
        val createdIds = database.transaction { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, phone)
                statement.execute()
            }
            fun exists(sql: String, value: String): Boolean = connection.prepareStatement(sql).use { st ->
                st.setString(1, value)
                st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(username)=LOWER(?))", operationalUsername)) throw AppException("El usuario operativo ya está ocupado.")
            if (exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(email)=LOWER(?))", operationalEmail)) throw AppException("El correo operativo ya está registrado.")
            if (createBeneficiary && exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(username)=LOWER(?))", beneficiaryUsername)) throw AppException("El usuario Beneficiario ya está ocupado.")
            if (createBeneficiary && exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(email)=LOWER(?))", beneficiaryEmail)) throw AppException("El correo Beneficiario ya está registrado.")
            if (exists("SELECT EXISTS(SELECT 1 FROM perfiles_usuario WHERE phone=?)", phone)) throw AppException("Ese teléfono ya pertenece a otra persona registrada.")
            if (exists("SELECT EXISTS(SELECT 1 FROM verificaciones_documentos WHERE UPPER(document_number)=UPPER(?))", documentNumber)) throw AppException("Ese documento ya pertenece a otra persona registrada.")

            fun createUser(username: String, email: String, password: String, pin: String, accountRole: String, kind: String, subRole: String?): Long =
                connection.prepareStatement(
                    """INSERT INTO usuarios(username,email,password_hash,pin_hash,role,account_status,verification_status,email_verified,phone_verified,admin_subrole,person_group_id,account_kind)
                       VALUES (?,?,?,?,?,'ACTIVE','VERIFIED',TRUE,TRUE,?,?,?) RETURNING id"""
                ).use { st ->
                    st.setString(1, username); st.setString(2, email); st.setString(3, passwordSecurity.hash(password)); st.setString(4, passwordSecurity.hash(pin))
                    st.setString(5, accountRole); st.setString(6, subRole); st.setObject(7, personGroupId); st.setString(8, kind)
                    st.executeQuery().use { rs -> if (!rs.next()) throw AppException("No fue posible crear la cuenta."); rs.getLong(1) }
                }

            fun createProfile(userId: Long) {
                connection.prepareStatement(
                    """INSERT INTO perfiles_usuario(user_id,full_name,first_name,middle_name,last_name,second_last_name,phone,birth_date,state,municipality,parish,community,address)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"""
                ).use { st ->
                    st.setLong(1,userId); st.setString(2,fullName); st.setString(3,firstName); st.setString(4,middleName.takeIf { it.isNotBlank() })
                    st.setString(5,lastName); st.setString(6,secondLastName.takeIf { it.isNotBlank() }); st.setString(7,phone); st.setObject(8,birthDate)
                    st.setString(9,request.state?.trim()?.takeIf { it.isNotBlank() }); st.setString(10,request.municipality?.trim()?.takeIf { it.isNotBlank() })
                    st.setString(11,request.parish?.trim()?.takeIf { it.isNotBlank() }); st.setString(12,request.community?.trim()?.takeIf { it.isNotBlank() })
                    st.setString(13,request.address?.trim()?.takeIf { it.isNotBlank() }); st.executeUpdate()
                }
            }

            fun createApprovedDocuments(userId: Long) {
                connection.prepareStatement(
                    """INSERT INTO verificaciones_documentos(user_id,document_type,document_number,front_file_path,back_file_path,selfie_file_path,status,reviewed_by,reviewed_at)
                       VALUES (?,?,?,?,?,?,'APPROVED',?,NOW())"""
                ).use { st ->
                    st.setLong(1,userId); st.setString(2,documentType); st.setString(3,documentNumber); st.setString(4,frontPath); st.setString(5,backPath); st.setString(6,selfiePath); st.setLong(7,actorId); st.executeUpdate()
                }
            }

            val operationalId = createUser(operationalUsername, operationalEmail, request.operationalPassword, request.operationalPin, role, "OPERATIONAL", adminSubRole)
            createProfile(operationalId)
            createApprovedDocuments(operationalId)

            val beneficiaryId = if (createBeneficiary) {
                createUser(beneficiaryUsername, beneficiaryEmail, beneficiaryPassword, beneficiaryPin, Roles.BENEFICIARY, "BENEFICIARY", null).also { id ->
                    createProfile(id)
                    createApprovedDocuments(id)
                    connection.prepareStatement("INSERT INTO perfiles_financieros_usuario(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING").use { st -> st.setLong(1,id); st.executeUpdate() }
                }
            } else null

            if (beneficiaryId != null) {
                connection.prepareStatement("UPDATE usuarios SET linked_account_user_id=? WHERE id=?").use { st -> st.setLong(1,beneficiaryId); st.setLong(2,operationalId); st.executeUpdate() }
                connection.prepareStatement("UPDATE usuarios SET linked_account_user_id=? WHERE id=?").use { st -> st.setLong(1,operationalId); st.setLong(2,beneficiaryId); st.executeUpdate() }

                // La cuenta Beneficiario recibe su propia cartera ISU e historial de crédito.
                // Nunca reutiliza la cartera ISA del Administrador vinculado.
                ensureCreditAccount(connection, beneficiaryId)
                connection.prepareStatement("INSERT INTO usuarios_credimpulso(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING").use { st -> st.setLong(1, beneficiaryId); st.executeUpdate() }
                connection.prepareStatement("INSERT INTO historial_crediticio_usuarios(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING").use { st -> st.setLong(1, beneficiaryId); st.executeUpdate() }
            }
            if (role == Roles.ADMIN) {
                // La identidad operativa mantiene una cartera administrativa ISA separada.
                ensureAdminCredimpulsoWallet(connection, operationalId)
            }
            audit(connection, actorId, "ACCOUNTANT_STAFF_CREATED", "PERSON", personGroupId.toString(), "$role · $fullName · beneficiary=$createBeneficiary · wallets=SEPARATE")
            operationalId to beneficiaryId
        }

        val operational = me(createdIds.first)
        val beneficiary = createdIds.second?.let(::me)
        notifyUsers(listOf(createdIds.first), "Acceso Kredi+ creado", "El Contador creó tu acceso de ${if (role == Roles.ADMIN) "Administrador" else "Almacenista"}.", "STAFF_CREATED")
        beneficiary?.let { notifyUsers(listOf(it.id), "Acceso Beneficiario creado", "Tu acceso Beneficiario está vinculado a tu cuenta de personal.", "BENEFICIARY_CREATED") }
        return StaffAccountCreationResultDto(
            personGroupId = personGroupId.toString(),
            operational = operational,
            beneficiary = beneficiary,
            message = if (beneficiary == null) "Personal creado correctamente." else "Personal y acceso Beneficiario creados y vinculados correctamente."
        )
    }

    /**
     * Añade una identidad Beneficiario a una persona que ya posee un acceso operativo
     * Administrador o Almacenista. La nueva identidad comparte datos personales y
     * documentos, pero conserva credenciales, cartera e historial financiero separados.
     */
    fun createLinkedBeneficiaryAccess(
        actorId: Long,
        operationalUserId: Long,
        request: LinkedBeneficiaryAccessRequest
    ): StaffAccountCreationResultDto {
        requireAccountant(actorId)

        val username = validateUsername(request.username)
        val email = request.email.trim().lowercase()
        if (!EMAIL_REGEX.matches(email)) throw AppException("Ingresa un correo válido para el acceso Beneficiario.")
        PasswordPolicy.validationError(request.password, username, email)?.let {
            throw AppException(it.replace("La contraseña", "La contraseña del Beneficiario"))
        }
        PinPolicy.validationError(request.pin)?.let { throw AppException(it.replace("El PIN", "El PIN del Beneficiario")) }

        val created = database.transaction { connection ->
            val operational = connection.prepareStatement(
                """SELECT id,username,email,role,account_status,verification_status,person_group_id,linked_account_user_id
                   FROM usuarios WHERE id=? FOR UPDATE"""
            ).use { statement ->
                statement.setLong(1, operationalUserId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("La cuenta operativa ya no existe.")
                    val role = Roles.canonical(result.getString("role"))
                    if (role !in setOf(Roles.ADMIN, Roles.WAREHOUSE)) {
                        throw AppException("Solo Administradores y Almacenistas pueden recibir un acceso Beneficiario vinculado.")
                    }
                    if (result.getObject("linked_account_user_id") != null) {
                        throw AppException("Esta persona ya tiene una cuenta Beneficiario vinculada.")
                    }
                    if (!result.getString("verification_status").equals("VERIFIED", true)) {
                        throw AppException("El acceso operativo debe estar verificado antes de añadir un Beneficiario.")
                    }
                    arrayOf(
                        result.getLong("id"),
                        result.getString("username").orEmpty(),
                        result.getString("email").orEmpty(),
                        role,
                        result.getString("account_status").orEmpty(),
                        result.getObject("person_group_id")
                    )
                }
            }

            val operationalUsername = operational[1] as String
            val operationalEmail = operational[2] as String
            if (username.equals(operationalUsername, true)) throw AppException("El Beneficiario debe utilizar un usuario distinto al acceso operativo.")
            if (email.equals(operationalEmail, true)) throw AppException("El Beneficiario debe utilizar un correo distinto al acceso operativo.")

            fun exists(sql: String, value: String): Boolean = connection.prepareStatement(sql).use { statement ->
                statement.setString(1, value)
                statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
            }
            if (exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(username)=LOWER(?))", username)) throw AppException("El usuario Beneficiario ya está ocupado.")
            if (exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(email)=LOWER(?))", email)) throw AppException("El correo Beneficiario ya está registrado.")

            val groupId = (operational[5] as? UUID) ?: UUID.randomUUID().also { generated ->
                connection.prepareStatement("UPDATE usuarios SET person_group_id=?,updated_at=NOW() WHERE id=?").use { statement ->
                    statement.setObject(1, generated)
                    statement.setLong(2, operationalUserId)
                    statement.executeUpdate()
                }
            }

            val beneficiaryId = connection.prepareStatement(
                """INSERT INTO usuarios(username,email,password_hash,pin_hash,role,account_status,verification_status,email_verified,phone_verified,person_group_id,account_kind)
                   VALUES (?,?,?,?,'BENEFICIARY','ACTIVE','VERIFIED',TRUE,TRUE,?,'BENEFICIARY') RETURNING id"""
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, email)
                statement.setString(3, passwordSecurity.hash(request.password))
                statement.setString(4, passwordSecurity.hash(request.pin))
                statement.setObject(5, groupId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw AppException("No fue posible crear el acceso Beneficiario.")
                    result.getLong(1)
                }
            }

            val profileCopied = connection.prepareStatement(
                """INSERT INTO perfiles_usuario(
                       user_id,full_name,first_name,middle_name,last_name,second_last_name,phone,birth_date,gender,employment_type,
                       state,municipality,parish,community,address,shipping_address,selfie_path,profile_image_path
                   )
                   SELECT ?,full_name,first_name,middle_name,last_name,second_last_name,phone,birth_date,gender,employment_type,
                          state,municipality,parish,community,address,shipping_address,selfie_path,profile_image_path
                   FROM perfiles_usuario WHERE user_id=?"""
            ).use { statement ->
                statement.setLong(1, beneficiaryId)
                statement.setLong(2, operationalUserId)
                statement.executeUpdate()
            }
            if (profileCopied != 1) throw AppException("El acceso operativo no tiene un perfil personal completo para vincular.")

            connection.prepareStatement(
                """INSERT INTO verificaciones_documentos(
                       user_id,document_type,document_number,front_file_path,back_file_path,selfie_file_path,status,rejection_reason,reviewed_by,reviewed_at
                   )
                   SELECT ?,document_type,document_number,front_file_path,back_file_path,selfie_file_path,'APPROVED',NULL,?,NOW()
                   FROM verificaciones_documentos
                   WHERE user_id=?
                   ORDER BY submitted_at DESC
                   LIMIT 1"""
            ).use { statement ->
                statement.setLong(1, beneficiaryId)
                statement.setLong(2, actorId)
                statement.setLong(3, operationalUserId)
                statement.executeUpdate()
            }

            connection.prepareStatement("UPDATE usuarios SET linked_account_user_id=?,updated_at=NOW() WHERE id=?").use { statement ->
                statement.setLong(1, beneficiaryId)
                statement.setLong(2, operationalUserId)
                statement.executeUpdate()
            }
            connection.prepareStatement("UPDATE usuarios SET linked_account_user_id=?,updated_at=NOW() WHERE id=?").use { statement ->
                statement.setLong(1, operationalUserId)
                statement.setLong(2, beneficiaryId)
                statement.executeUpdate()
            }

            connection.prepareStatement("INSERT INTO perfiles_financieros_usuario(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING").use { statement ->
                statement.setLong(1, beneficiaryId); statement.executeUpdate()
            }
            ensureCreditAccount(connection, beneficiaryId)
            connection.prepareStatement("INSERT INTO usuarios_credimpulso(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING").use { statement ->
                statement.setLong(1, beneficiaryId); statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO historial_crediticio_usuarios(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING").use { statement ->
                statement.setLong(1, beneficiaryId); statement.executeUpdate()
            }
            audit(connection, actorId, "LINKED_BENEFICIARY_CREATED", "PERSON", groupId.toString(), "operational=$operationalUserId · beneficiary=$beneficiaryId")
            Triple(groupId, operationalUserId, beneficiaryId)
        }

        val operational = me(created.second)
        val beneficiary = me(created.third)
        notifyUsers(listOf(created.third), "Acceso Beneficiario creado", "El Contador añadió tu acceso Beneficiario vinculado. Tu cartera e historial permanecen separados del acceso operativo.", "BENEFICIARY_CREATED")
        return StaffAccountCreationResultDto(
            personGroupId = created.first.toString(),
            operational = operational,
            beneficiary = beneficiary,
            message = "Acceso Beneficiario creado y vinculado correctamente."
        )
    }

    data class AccountSuspensionState(val reason: String, val suspendedAt: String?)

    fun accountSuspensionState(userId: Long): AccountSuspensionState? = database.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT account_status,suspension_reason,suspended_at FROM usuarios WHERE id=?").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next() || !result.getString("account_status").equals("SUSPENDED", true)) {
                    null
                } else {
                    AccountSuspensionState(
                        reason = result.getString("suspension_reason")?.takeIf { it.isNotBlank() } ?: "Falta de pago",
                        suspendedAt = result.getObject("suspended_at", OffsetDateTime::class.java)?.toString()
                    )
                }
            }
        }
    }

    /**
     * Devuelve referencias históricas que impedirían borrar físicamente un usuario.
     * Se consulta el catálogo de PostgreSQL para no depender de una lista frágil de tablas:
     * cualquier FK RESTRICT/NO ACTION que apunte a usuarios(id) se considera historial
     * protegido, salvo tablas de estado efímero que se limpian de forma explícita.
     */
    private fun protectedUserDeletionReferences(connection: Connection, targetUserId: Long): List<String> {
        val removableStateTables = setOf(
            "cuentas_credito",
            "usuarios_credimpulso",
            "carteras_credimpulso_admin"
        )
        val references = connection.prepareStatement(
            """
            SELECT tc.table_name, kcu.column_name, rc.delete_rule
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name=kcu.constraint_name
             AND tc.constraint_schema=kcu.constraint_schema
            JOIN information_schema.referential_constraints rc
              ON tc.constraint_name=rc.constraint_name
             AND tc.constraint_schema=rc.constraint_schema
            JOIN information_schema.constraint_column_usage ccu
              ON rc.unique_constraint_name=ccu.constraint_name
             AND rc.unique_constraint_schema=ccu.constraint_schema
            WHERE tc.constraint_type='FOREIGN KEY'
              AND tc.table_schema='public'
              AND ccu.table_schema='public'
              AND ccu.table_name='usuarios'
              AND ccu.column_name='id'
              AND UPPER(rc.delete_rule) IN ('RESTRICT','NO ACTION')
            ORDER BY tc.table_name, kcu.column_name
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val table = result.getString("table_name").orEmpty()
                        val column = result.getString("column_name").orEmpty()
                        if (table.isNotBlank() && column.isNotBlank() && table !in removableStateTables) {
                            add(table to column)
                        }
                    }
                }
            }
        }

        fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""
        val blockers = buildList {
            references.distinct().forEach { (table, column) ->
                val sql = "SELECT EXISTS(SELECT 1 FROM ${quoteIdentifier(table)} WHERE ${quoteIdentifier(column)}=? LIMIT 1)"
                val exists = connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, targetUserId)
                    statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
                }
                if (exists) add(table)
            }

            // Esta tabla cuelga de usuarios_credimpulso, no directamente de usuarios.
            // Si tiene movimientos, la cuenta sí posee historial financiero y no puede borrarse.
            val hasCredimpulsoTransactions = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM transacciones_credimpulso WHERE user_id=? LIMIT 1)"
            ).use { statement ->
                statement.setLong(1, targetUserId)
                statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
            }
            if (hasCredimpulsoTransactions) add("transacciones_credimpulso")
        }
        return blockers.distinct()
    }

    /** Limpia únicamente filas de estado recreables; nunca elimina historia financiera. */
    private fun deleteEphemeralUserState(connection: Connection, targetUserId: Long) {
        listOf(
            "DELETE FROM usuarios_credimpulso WHERE user_id=?",
            "DELETE FROM cuentas_credito WHERE user_id=?",
            "DELETE FROM carteras_credimpulso_admin WHERE admin_id=?"
        ).forEach { sql ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, targetUserId)
                statement.executeUpdate()
            }
        }
    }

    fun deleteUserPermanently(actorId: Long, targetUserId: Long) {
        if (currentRole(actorId) !in setOf(Roles.ACCOUNTANT, Roles.ADMIN)) {
            throw ForbiddenException("Solo un Administrador o Contador puede eliminar usuarios.")
        }
        if (actorId == targetUserId) throw AppException("No puedes eliminar la cuenta con la que tienes la sesión iniciada.")
        database.ensureAuthenticationSchema()
        database.transaction { connection ->
            val target=connection.prepareStatement("SELECT username,role,account_status FROM usuarios WHERE id=? FOR UPDATE").use { st->st.setLong(1,targetUserId);st.executeQuery().use { rs->if(!rs.next())throw NotFoundException("El usuario ya no existe.");Triple(rs.getString(1).orEmpty(),Roles.canonical(rs.getString(2)),rs.getString(3).orEmpty()) } }
            if(target.third=="DELETED") throw AppException("La cuenta ya fue eliminada.")
            if(target.second==Roles.ACCOUNTANT){
                val others=connection.prepareStatement("SELECT COUNT(*) FROM usuarios WHERE id<>? AND UPPER(role)='ACCOUNTANT' AND account_status='ACTIVE'").use { st->st.setLong(1,targetUserId);st.executeQuery().use { rs->rs.next();rs.getInt(1) } }
                if(others<1) throw AppException("No puedes eliminar el último Contador activo.")
            }
            // Eliminación lógica: se retira el acceso, pero jamás se rompen compras, pagos,
            // jornadas, QR, cuotas ni auditorías. Toda actividad histórica debe conservarse.
            val deletedUsername="deleted_${targetUserId}_${System.currentTimeMillis()}"
            val deletedEmail="deleted+$targetUserId-${System.currentTimeMillis()}@invalid.krediplus.local"
            connection.prepareStatement("UPDATE usuarios SET username=?,email=?,account_status='DELETED',email_verified=FALSE,phone_verified=FALSE,locked_until=NULL,suspension_reason='Cuenta eliminada',suspended_at=NOW(),suspended_by=?,deleted_at=NOW(),deleted_by=?,updated_at=NOW() WHERE id=?").use { st->st.setString(1,deletedUsername);st.setString(2,deletedEmail);st.setLong(3,actorId);st.setLong(4,actorId);st.setLong(5,targetUserId);st.executeUpdate() }
            connection.prepareStatement("UPDATE sesiones_usuario SET revoked_at=COALESCE(revoked_at,NOW()),ended_reason=COALESCE(ended_reason,'USER_DELETED') WHERE user_id=? AND revoked_at IS NULL").use { st->st.setLong(1,targetUserId);st.executeUpdate() }
            connection.prepareStatement("UPDATE usuarios SET linked_account_user_id=NULL WHERE linked_account_user_id=?").use { st->st.setLong(1,targetUserId);st.executeUpdate() }
            audit(connection,actorId,"USER_SOFT_DELETED","USER",targetUserId.toString(),"${target.first} · ${target.second} · historial conservado")
        }
    }

    fun suspendAccountForNonPayment(actorId: Long, targetUserId: Long, request: AccountSuspensionRequest) {
        requireAdminOrAccountant(actorId)
        if (actorId == targetUserId) throw AppException("No puedes suspender tu propia cuenta.")
        database.ensureAuthenticationSchema()
        val reason = request.reason.trim().take(500).takeIf { it.isNotBlank() } ?: "Falta de pago"
        database.transaction { connection ->
            val target = connection.prepareStatement("SELECT role,account_status,verification_status,username FROM usuarios WHERE id=? FOR UPDATE").use { st ->
                st.setLong(1,targetUserId)
                st.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("La cuenta no existe.")
                    arrayOf(Roles.canonical(rs.getString("role")),rs.getString("account_status"),rs.getString("verification_status"),rs.getString("username").orEmpty())
                }
            }
            if (target[0] != Roles.BENEFICIARY) throw AppException("La suspensión se aplica únicamente a la cuenta Beneficiario. Los accesos operativos permanecen separados.")
            if (target[1] == "SUSPENDED") throw AppException("La cuenta ya está suspendida.")
            if (target[1] != "ACTIVE" || target[2] != "VERIFIED") throw AppException("Solo se puede suspender una cuenta Beneficiario activa y verificada.")
            connection.prepareStatement("UPDATE usuarios SET account_status='SUSPENDED',suspended_at=NOW(),suspension_reason=?,suspended_by=?,updated_at=NOW() WHERE id=?").use { st ->
                st.setString(1,reason); st.setLong(2,actorId); st.setLong(3,targetUserId); st.executeUpdate()
            }
            connection.prepareStatement("UPDATE sesiones_usuario SET revoked_at=COALESCE(revoked_at,NOW()),ended_reason=COALESCE(ended_reason,'PAYMENT_SUSPENSION') WHERE user_id=? AND revoked_at IS NULL").use { st ->
                st.setLong(1,targetUserId); st.executeUpdate()
            }
            audit(connection,actorId,"ACCOUNT_SUSPENDED_NON_PAYMENT","USER",targetUserId.toString(),"${target[3]} · $reason")
        }
        notifyUsers(listOf(targetUserId),"Cuenta suspendida","Tu cuenta Kredi+ fue suspendida. Motivo: $reason","ACCOUNT_SUSPENDED")
    }

    fun reactivateSuspendedAccount(actorId: Long, targetUserId: Long) {
        requireAdminOrAccountant(actorId)
        database.ensureAuthenticationSchema()
        database.transaction { connection ->
            val target = connection.prepareStatement("SELECT role,account_status,verification_status,username FROM usuarios WHERE id=? FOR UPDATE").use { st ->
                st.setLong(1,targetUserId)
                st.executeQuery().use { rs ->
                    if (!rs.next()) throw NotFoundException("La cuenta no existe.")
                    arrayOf(Roles.canonical(rs.getString("role")),rs.getString("account_status"),rs.getString("verification_status"),rs.getString("username").orEmpty())
                }
            }
            if (target[0] != Roles.BENEFICIARY) throw AppException("Solo se reactiva por este flujo la cuenta Beneficiario.")
            if (target[1] != "SUSPENDED") throw AppException("La cuenta seleccionada no está suspendida.")
            if (target[2] != "VERIFIED") throw AppException("La cuenta debe conservar su verificación aprobada para reactivarse.")
            connection.prepareStatement("UPDATE usuarios SET account_status='ACTIVE',suspended_at=NULL,suspension_reason=NULL,suspended_by=NULL,updated_at=NOW() WHERE id=?").use { st ->
                st.setLong(1,targetUserId); st.executeUpdate()
            }
            audit(connection,actorId,"ACCOUNT_REACTIVATED","USER",targetUserId.toString(),target[3])
        }
        notifyUsers(listOf(targetUserId),"Cuenta reactivada","Tu cuenta Kredi+ fue reactivada. Ya puedes iniciar sesión nuevamente.","ACCOUNT_REACTIVATED")
    }


    /**
     * Analiza cualquier libro .xlsx por su contenido. El nombre del archivo y el orden de
     * las columnas son irrelevantes. Solo se admiten cuentas operativas Administrador,
     * Almacenista y Contador; nunca se crean Beneficiarios desde esta importación.
     */
    private data class ValidatedProductExcelRow(
        val sheetName: String,
        val rowNumber: Int,
        val name: String,
        val mainCategory: String,
        val classification: String,
        val brand: String,
        val category: String,
        val unit: String,
        val pricingMode: String,
        val priceUsd: java.math.BigDecimal?,
        val stock: Int?,
        val minimumStock: Int?,
        val status: String,
        val details: String,
        val action: String,
        val existingProductId: Long?,
        val currentPriceUsd: java.math.BigDecimal?,
        val errors: List<String>,
        val warnings: List<String>
    ) {
        fun toPreviewDto() = ProductExcelImportRowDto(
            sheetName = sheetName, rowNumber = rowNumber, name = name,
            mainCategory = mainCategory, classification = classification, brand = brand,
            unit = unit, pricingMode = pricingMode, priceUsd = priceUsd?.toDouble(), stock = stock,
            minimumStock = minimumStock, status = status, details = details,
            action = action, existingProductId = existingProductId, currentPriceUsd = currentPriceUsd?.toDouble(),
            valid = errors.isEmpty(), errors = errors, warnings = warnings
        )
    }

    fun previewProductExcelImport(actorId: Long, bytes: ByteArray): ProductExcelImportPreviewDto {
        requireAnyProductImportPermission(actorId)
        val (validated, ignoredSheets) = validateProductExcelRows(bytes)
        val rows = validated.map { it.toPreviewDto() }
        val invalid = rows.count { !it.valid }
        val create = rows.count { it.valid && it.action == "CREATE" }
        val update = rows.count { it.valid && it.action == "UPDATE_PRICE" }
        val unchanged = rows.count { it.valid && it.action == "UNCHANGED" }
        val actionable = create + update
        return ProductExcelImportPreviewDto(
            totalRows = rows.size,
            validRows = actionable,
            invalidRows = invalid,
            createRows = create,
            updateRows = update,
            unchangedRows = unchanged,
            ignoredSheets = ignoredSheets,
            rows = rows,
            message = when {
                rows.isEmpty() -> "El Excel no contiene filas de productos reconocibles."
                invalid > 0 && actionable == 0 -> "No hay productos listos para aplicar. Corrige las filas marcadas con error."
                invalid == 0 && update > 0 -> "$create producto(s) nuevo(s), $update con cambio de precio y $unchanged sin cambios. Revisa la vista previa antes de confirmar."
                invalid == 0 -> "$create producto(s) nuevo(s) y $unchanged sin cambios. Revisa la vista previa antes de confirmar."
                else -> "$create producto(s) nuevo(s), $update con cambio de precio, $unchanged sin cambios y $invalid con errores."
            }
        )
    }

    fun importProductsExcel(actorId: Long, bytes: ByteArray, updateExisting: Boolean = true): ProductExcelImportResultDto {
        requireAnyProductImportPermission(actorId)
        val (validated, _) = validateProductExcelRows(bytes)
        val invalidCount = validated.count { it.errors.isNotEmpty() }
        val ready = validated.filter { it.errors.isEmpty() }
        if (ready.isEmpty()) throw AppException("No hay productos válidos para procesar. Corrige el Excel y vuelve a intentarlo.")

        val needsBcv = ready.any { it.action == "CREATE" || (it.action == "UPDATE_PRICE" && updateExisting) }
        val rate = if (needsBcv) {
            val rateSnapshot = runCatching { bcvRateService.currentUsdRate() }
                .getOrElse { throw AppException("No se pudo consultar la tasa BCV para calcular los precios en bolívares.") }
            runCatching { MoneyMath.positive(MoneyMath.rate(rateSnapshot.rate), "Tasa BCV") }
                .getOrElse { throw AppException(it.message ?: "La tasa BCV no es válida.") }
        } else java.math.BigDecimal.ONE

        data class ExcelApplyResult(val createdIds: List<Long>, val updatedIds: List<Long>, val unchanged: Int)

        val applyResult = database.transaction { connection ->
            val createdIds = mutableListOf<Long>()
            val updatedIds = mutableListOf<Long>()
            var unchanged = 0

            ready.forEach { row ->
                val keyLock = "product:${row.name.lowercase().trim()}:${row.category.lowercase().trim()}"
                connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { st ->
                    st.setString(1, keyLock)
                    st.execute()
                }

                val existing = connection.prepareStatement(
                    """SELECT id,base_price_usd FROM productos
                       WHERE active=TRUE AND LOWER(BTRIM(name))=LOWER(BTRIM(?)) AND LOWER(BTRIM(category))=LOWER(BTRIM(?))
                       LIMIT 1 FOR UPDATE"""
                ).use { statement ->
                    statement.setString(1, row.name)
                    statement.setString(2, row.category)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getLong(1) to (result.getBigDecimal(2) ?: java.math.BigDecimal.ZERO) else null
                    }
                }

                val priceUsd = row.priceUsd ?: throw AppException("Precio inválido para ${row.name}.")
                if (existing != null) {
                    val (existingId, currentUsd) = existing
                    if (currentUsd.compareTo(priceUsd) != 0 && updateExisting) {
                        val priceBs = MoneyMath.usdToVes(priceUsd, rate)
                        connection.prepareStatement(
                            """UPDATE productos
                               SET base_price=?,base_price_usd=?,bcv_rate=?,price_updated_at=NOW(),updated_at=NOW()
                               WHERE id=? AND active=TRUE"""
                        ).use { statement ->
                            statement.setBigDecimal(1, priceBs)
                            statement.setBigDecimal(2, priceUsd)
                            statement.setBigDecimal(3, rate)
                            statement.setLong(4, existingId)
                            if (statement.executeUpdate() != 1) throw AppException("No se pudo actualizar el precio de ${row.name}.")
                        }
                        audit(
                            connection, actorId, "INVENTORY_PRODUCT_PRICE_UPDATED_FROM_EXCEL", "PRODUCT", existingId.toString(),
                            "${row.name} · USD ${currentUsd.toPlainString()} → ${priceUsd.toPlainString()} · hoja=${row.sheetName} · fila=${row.rowNumber}"
                        )
                        updatedIds += existingId
                    } else {
                        unchanged++
                    }
                    return@forEach
                }

                val priceBs = MoneyMath.usdToVes(priceUsd, rate)
                val normalizedMetadata = ProductMetadataPolicy.normalize(row.category, row.details)
                val id = connection.prepareStatement(
                    """INSERT INTO productos(name,category,unit,technical_details,base_price,base_price_usd,bcv_rate,pricing_mode,stock,minimum_stock,active,created_by,price_updated_at)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW()) RETURNING id"""
                ).use { statement ->
                    statement.setString(1, row.name)
                    statement.setString(2, normalizedMetadata.category)
                    statement.setString(3, if (row.pricingMode == "KG") "kg" else row.unit)
                    statement.setString(4, normalizedMetadata.details)
                    statement.setBigDecimal(5, priceBs)
                    statement.setBigDecimal(6, priceUsd)
                    statement.setBigDecimal(7, rate)
                    statement.setString(8, row.pricingMode)
                    statement.setInt(9, row.stock ?: 0)
                    statement.setInt(10, row.minimumStock ?: 5)
                    statement.setBoolean(11, !row.status.equals("Inactivo", true))
                    statement.setLong(12, actorId)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
                if ((row.stock ?: 0) != 0) {
                    connection.prepareStatement("INSERT INTO movimientos_inventario(product_id,movement_type,quantity_delta,notes,performed_by) VALUES (?,'INITIAL',?,'Carga inicial desde Excel',?)").use { statement ->
                        statement.setLong(1, id); statement.setInt(2, row.stock ?: 0); statement.setLong(3, actorId); statement.executeUpdate()
                    }
                }
                audit(connection, actorId, "INVENTORY_PRODUCT_IMPORTED_FROM_EXCEL", "PRODUCT", id.toString(), "${row.name} · hoja=${row.sheetName} · fila=${row.rowNumber}")
                createdIds += id
            }
            ExcelApplyResult(createdIds.distinct(), updatedIds.distinct(), unchanged)
        }

        val (created, updated) = database.dataSource.connection.use { connection ->
            applyResult.createdIds.map { findProduct(connection, it) } to applyResult.updatedIds.map { findProduct(connection, it) }
        }
        if (applyResult.createdIds.isNotEmpty()) {
            notifyBeneficiaries("Nuevos productos", "Se incorporaron ${applyResult.createdIds.size} producto(s) al catálogo de Kredi+.", "NEW_PRODUCTS_IMPORT", mapOf("count" to applyResult.createdIds.size.toString()))
        }
        return ProductExcelImportResultDto(
            importedCount = created.size,
            updatedCount = updated.size,
            unchangedCount = applyResult.unchanged,
            skippedCount = invalidCount,
            importedProducts = created,
            updatedProducts = updated,
            message = buildString {
                append("Excel procesado: ${created.size} nuevo(s)")
                append(", ${updated.size} precio(s) actualizado(s)")
                append(", ${applyResult.unchanged} sin cambios")
                if (invalidCount > 0) append(" y $invalidCount fila(s) con errores omitidas")
                append(".")
            }
        )
    }

    private fun requireAnyProductImportPermission(actorId: Long) {
        requireAnyPermission(actorId, "CREATE_PRODUCTS", "MANAGE_CATALOG", "MANAGE_INVENTORY")
    }

    private fun validateProductExcelRows(bytes: ByteArray): Pair<List<ValidatedProductExcelRow>, List<String>> {
        val parsed = ProductExcelWorkbookParser.parse(bytes)
        val existing = database.dataSource.connection.use { connection ->
            buildMap<String, Pair<Long, java.math.BigDecimal>> {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id,LOWER(BTRIM(name)),LOWER(BTRIM(category)),base_price_usd FROM productos WHERE active=TRUE").use { rs ->
                        while (rs.next()) {
                            put(
                                rs.getString(2).orEmpty() + "||" + rs.getString(3).orEmpty(),
                                rs.getLong(1) to (rs.getBigDecimal(4) ?: java.math.BigDecimal.ZERO)
                            )
                        }
                    }
                }
            }
        }
        val seen = mutableSetOf<String>()
        fun decimal(value: String): java.math.BigDecimal? = value.trim()
            .replace("$", "").replace("USD", "", true).replace(" ", "")
            .let { raw ->
                val normalized = if (raw.contains(',') && !raw.contains('.')) raw.replace(',', '.') else raw.replace(",", "")
                normalized.toBigDecimalOrNull()
            }
        fun integer(value: String): Int? = value.trim().replace(" ", "").replace(",", "").toIntOrNull()

        val validated = parsed.rows.map { raw ->
            val v = raw.values
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val name = v["name"].orEmpty().trim()
            var main = v["mainCategory"].orEmpty().trim()
            var classification = v["classification"].orEmpty().trim()
            var brand = v["brand"].orEmpty().trim()
            val combined = v["combinedCategory"].orEmpty().trim()
            if (combined.isNotBlank()) {
                val parts = combined.split(" · ").map(String::trim).filter(String::isNotBlank)
                if (main.isBlank()) main = parts.getOrNull(0).orEmpty()
                if (classification.isBlank()) classification = parts.getOrNull(1).orEmpty()
                if (brand.isBlank()) brand = parts.firstOrNull { it.startsWith("Marca ", true) }?.substringAfter("Marca ")?.trim().orEmpty()
            }
            if (main.isBlank() && classification in InventoryProductValidator.foodClassifications) main = "Alimentos"
            if (main.isBlank() && classification in InventoryProductValidator.otherClassifications) main = "Otros productos"
            val unitRaw = v["unit"].orEmpty().trim()
            val modeRaw = v["pricingMode"].orEmpty().trim().uppercase()
            val pricingMode = when {
                modeRaw in setOf("KG", "KILO", "KILOGRAMO", "POR KG", "POR KILOGRAMO") -> "KG"
                modeRaw in setOf("UNIT", "UNIDAD", "POR UNIDAD", "UNITARIO", "") -> "UNIT"
                else -> modeRaw
            }
            val unit = if (pricingMode == "KG") "kg" else unitRaw
            val priceUsd = decimal(v["priceUsd"].orEmpty())
            val stock = integer(v["stock"].orEmpty())
            val minimumStock = integer(v["minimumStock"].orEmpty().ifBlank { "5" })
            val statusRaw = v["status"].orEmpty().trim()
            val status = when {
                statusRaw.isBlank() || statusRaw.equals("activo", true) || statusRaw.equals("active", true) || statusRaw == "1" -> "Activo"
                statusRaw.equals("inactivo", true) || statusRaw.equals("inactive", true) || statusRaw == "0" -> "Inactivo"
                else -> statusRaw
            }
            val details = v["details"].orEmpty().trim()
            if (name.isBlank()) errors += "Producto: nombre obligatorio."
            if (main !in setOf("Alimentos", "Otros productos")) errors += "Categoría principal: usa Alimentos u Otros productos."
            if (classification.isBlank()) errors += "Clasificación obligatoria."
            if (brand.isBlank()) errors += "Marca obligatoria."
            if (pricingMode !in setOf("UNIT", "KG")) errors += "Forma de precio: usa UNIT o KG."
            if (pricingMode == "UNIT" && unit.isBlank()) errors += "Unidad obligatoria para precio por unidad."
            if (priceUsd == null || priceUsd <= java.math.BigDecimal.ZERO) errors += "Precio (USD) debe ser mayor que cero."
            if (status !in setOf("Activo", "Inactivo")) errors += "Estado debe ser Activo o Inactivo."
            val category = listOf(main, classification, "Marca $brand").filter(String::isNotBlank).joinToString(" · ")
            val key = name.lowercase().trim() + "||" + category.lowercase().trim()
            val existingProduct = existing[key]

            // 1.1.26 / WinUI 9.1.20: una fila que coincide con un producto existente puede
            // usarse exclusivamente para actualizar el precio. En ese caso Existencia y
            // Existencia mínima pueden quedar vacías y NUNCA se escriben en la base de datos.
            // Si no existe coincidencia exacta, ambos campos vuelven a ser obligatorios para
            // impedir que un Excel de actualización de precios cree productos con stock 0.
            if (existingProduct == null) {
                if (stock == null || stock < 0) errors += "Existencia debe ser un entero igual o mayor que cero para productos nuevos."
                if (minimumStock == null || minimumStock < 0) errors += "Existencia mínima debe ser un entero igual o mayor que cero para productos nuevos."
            } else {
                if (stock != null && stock < 0) errors += "Existencia no puede ser negativa."
                if (minimumStock != null && minimumStock < 0) errors += "Existencia mínima no puede ser negativa."
            }
            if (errors.isEmpty()) {
                runCatching { InventoryProductValidator.validate(name, category) }.exceptionOrNull()?.message?.let(errors::add)
                val normalized = ProductMetadataPolicy.normalize(category, details)
                if (normalized.category.length > 140) errors += "La categoría supera el máximo permitido."
                if (normalized.details.length > 4000) errors += "Los detalles superan el máximo de 4000 caracteres."
                if (classification in InventoryProductValidator.technologyClassifications && normalized.details.isBlank()) errors += "Los productos tecnológicos requieren especificaciones en Detalles."
                if (classification in InventoryProductValidator.pharmacyClassifications && normalized.details.isBlank()) errors += "Los productos de farmacia requieren ficha en Detalles."
            }
            if (!seen.add(key)) errors += "Producto duplicado dentro del mismo Excel."
            val action = when {
                existingProduct == null -> "CREATE"
                priceUsd != null && existingProduct.second.compareTo(priceUsd) != 0 -> "UPDATE_PRICE"
                else -> "UNCHANGED"
            }
            if (action == "CREATE" && status == "Inactivo") warnings += "Se importará como inactivo y no aparecerá en el catálogo público."
            if (existingProduct != null && errors.isEmpty()) {
                if (action == "UPDATE_PRICE") {
                    warnings += "Producto existente: el precio puede actualizarse de USD ${existingProduct.second.stripTrailingZeros().toPlainString()} a USD ${priceUsd?.stripTrailingZeros()?.toPlainString()}. Se conservarán la existencia, unidad, estado y ficha actual."
                } else {
                    warnings += "Producto existente con el mismo precio: no se realizarán cambios."
                }
            }
            ValidatedProductExcelRow(
                raw.sheetName, raw.rowNumber, name, main, classification, brand, category, unit, pricingMode,
                priceUsd, stock, minimumStock, status, details,
                action, existingProduct?.first, existingProduct?.second,
                errors.distinct(), warnings.distinct()
            )
        }
        return validated to parsed.ignoredSheets
    }

    fun previewStaffExcelImport(actorId: Long, bytes: ByteArray): StaffExcelImportPreviewDto {
        requireAccountant(actorId)
        val (validated, ignoredSheets) = validateStaffExcelRows(bytes)
        val rows = validated.map { it.toPreviewDto() }
        val valid = rows.count { it.valid }
        val invalid = rows.size - valid
        return StaffExcelImportPreviewDto(
            totalRows = rows.size,
            validRows = valid,
            invalidRows = invalid,
            ignoredSheets = ignoredSheets,
            rows = rows,
            message = when {
                valid == 0 -> "El Excel no contiene filas listas para importar. Corrige los errores indicados."
                invalid == 0 -> "Todas las filas están listas. Revisa la vista previa y confirma la importación."
                else -> "$valid fila(s) listas y $invalid con errores. Al confirmar solo se importarán las filas válidas."
            }
        )
    }

    fun importStaffExcel(actorId: Long, bytes: ByteArray): StaffExcelImportResultDto {
        requireAccountant(actorId)
        val (validated, _) = validateStaffExcelRows(bytes)
        val ready = validated.filter { it.errors.isEmpty() }
        val skipped = validated.size - ready.size
        if (ready.isEmpty()) throw AppException("No hay filas válidas para importar. Corrige el Excel y vuelve a intentarlo.")

        val createdIds = database.transaction { connection ->
            fun exists(sql: String, value: String): Boolean = connection.prepareStatement(sql).use { statement ->
                statement.setString(1, value)
                statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
            }

            val ids = mutableListOf<Long>()
            ready.forEach { row ->
                if (exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(username)=LOWER(?))", row.username)) {
                    throw AppException("El usuario ${row.username} fue registrado mientras revisabas el archivo. Actualiza la vista previa.")
                }
                if (exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(email)=LOWER(?))", row.email)) {
                    throw AppException("El correo ${row.email} fue registrado mientras revisabas el archivo. Actualiza la vista previa.")
                }
                if (exists("SELECT EXISTS(SELECT 1 FROM perfiles_usuario WHERE phone=?)", row.phone)) {
                    throw AppException("El teléfono ${row.phone} ya pertenece a otra cuenta. Actualiza la vista previa.")
                }
                if (exists("SELECT EXISTS(SELECT 1 FROM verificaciones_documentos WHERE UPPER(document_number)=UPPER(?))", row.documentNumber)) {
                    throw AppException("El documento ${row.documentNumber} ya pertenece a otra cuenta. Actualiza la vista previa.")
                }

                val personGroupId = UUID.randomUUID()
                val userId = connection.prepareStatement(
                    """INSERT INTO usuarios(username,email,password_hash,pin_hash,role,account_status,verification_status,email_verified,phone_verified,admin_subrole,person_group_id,account_kind)
                       VALUES (?,?,?,?,?,'ACTIVE','VERIFIED',TRUE,TRUE,?,?,?) RETURNING id"""
                ).use { statement ->
                    statement.setString(1, row.username)
                    statement.setString(2, row.email)
                    statement.setString(3, passwordSecurity.hash(row.password))
                    statement.setString(4, passwordSecurity.hash(row.pin))
                    statement.setString(5, row.role)
                    statement.setString(6, row.adminSubRole)
                    statement.setObject(7, personGroupId)
                    statement.setString(8, if (row.role == Roles.ACCOUNTANT) "ACCOUNTANT" else "OPERATIONAL")
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw AppException("No fue posible crear ${row.username}.")
                        result.getLong(1)
                    }
                }

                connection.prepareStatement(
                    """INSERT INTO perfiles_usuario(user_id,full_name,first_name,middle_name,last_name,second_last_name,phone,birth_date,employment_type,state,municipality,parish,community,address)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, row.fullName)
                    statement.setString(3, row.firstName)
                    statement.setString(4, row.middleName.takeIf { it.isNotBlank() })
                    statement.setString(5, row.lastName)
                    statement.setString(6, row.secondLastName.takeIf { it.isNotBlank() })
                    statement.setString(7, row.phone)
                    statement.setObject(8, row.birthDate)
                    statement.setString(9, row.employmentType)
                    statement.setString(10, row.state.takeIf { it.isNotBlank() })
                    statement.setString(11, row.municipality.takeIf { it.isNotBlank() })
                    statement.setString(12, row.parish.takeIf { it.isNotBlank() })
                    statement.setString(13, row.community.takeIf { it.isNotBlank() })
                    statement.setString(14, row.address.takeIf { it.isNotBlank() })
                    statement.executeUpdate()
                }

                // Conserva el documento y su unicidad sin inventar una fotografía. publicUrl()
                // oculta este marcador para que las interfaces muestren "sin archivo".
                connection.prepareStatement(
                    """INSERT INTO verificaciones_documentos(user_id,document_type,document_number,front_file_path,status,reviewed_by,reviewed_at)
                       VALUES (?,?,?,'__EXCEL_IMPORT__','APPROVED',?,NOW())"""
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, row.documentType)
                    statement.setString(3, row.documentNumber)
                    statement.setLong(4, actorId)
                    statement.executeUpdate()
                }

                // Una alta masiva debe dejar la cuenta exactamente igual de operativa
                // que una alta individual desde la interfaz.
                when (row.role) {
                    Roles.ADMIN -> ensureAdminCredimpulsoWallet(connection, userId)
                    Roles.ACCOUNTANT -> {
                        ensureAccountantBootstrapInfrastructure(connection)
                        connection.prepareStatement(
                            """INSERT INTO contadores(user_id,activo,designado_por) VALUES (?,TRUE,?)
                               ON CONFLICT(user_id) DO UPDATE SET activo=TRUE,updated_at=NOW()"""
                        ).use { statement ->
                            statement.setLong(1, userId)
                            statement.setLong(2, actorId)
                            statement.executeUpdate()
                        }
                        ensureAccountantWallet(connection, userId)
                    }
                }

                audit(
                    connection,
                    actorId,
                    "STAFF_IMPORTED_FROM_EXCEL",
                    "USER",
                    userId.toString(),
                    "${row.role} · ${row.username} · hoja=${row.sheetName} · fila=${row.rowNumber}"
                )
                ids += userId
            }
            ids
        }

        val created = createdIds.map(::me)
        return StaffExcelImportResultDto(
            importedCount = created.size,
            skippedCount = skipped,
            importedUsers = created,
            message = buildString {
                append("Se importaron ${created.size} cuenta(s) operativas correctamente.")
                if (skipped > 0) append(" $skipped fila(s) con errores fueron omitidas.")
            }
        )
    }

    fun previewBeneficiaryExcelImport(actorId: Long, bytes: ByteArray): StaffExcelImportPreviewDto {
        if (currentRole(actorId) != Roles.ADMIN) throw ForbiddenException("Solo un Administrador puede cargar Beneficiarios desde Excel.")
        val (validated, ignoredSheets) = validateStaffExcelRows(bytes, beneficiaryOnly = true)
        val rows = validated.map { it.toPreviewDto() }
        val valid = rows.count { it.valid }
        val invalid = rows.size - valid
        return StaffExcelImportPreviewDto(
            totalRows = rows.size,
            validRows = valid,
            invalidRows = invalid,
            ignoredSheets = ignoredSheets,
            rows = rows,
            message = when {
                valid == 0 -> "El Excel no contiene Beneficiarios listos para importar. Corrige los errores indicados."
                invalid == 0 -> "Todos los Beneficiarios están listos. Al confirmar quedarán pendientes de revisión administrativa."
                else -> "$valid Beneficiario(s) listos y $invalid fila(s) con errores. Solo se importarán las filas válidas."
            }
        )
    }

    fun importBeneficiaryExcel(actorId: Long, bytes: ByteArray): StaffExcelImportResultDto {
        if (currentRole(actorId) != Roles.ADMIN) throw ForbiddenException("Solo un Administrador puede cargar Beneficiarios desde Excel.")
        val (validated, _) = validateStaffExcelRows(bytes, beneficiaryOnly = true)
        val ready = validated.filter { it.errors.isEmpty() }
        val skipped = validated.size - ready.size
        if (ready.isEmpty()) throw AppException("No hay Beneficiarios válidos para importar. Corrige el Excel y vuelve a intentarlo.")

        val createdIds = database.transaction { connection ->
            fun exists(sql: String, value: String): Boolean = connection.prepareStatement(sql).use { statement ->
                statement.setString(1, value)
                statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
            }
            val ids = mutableListOf<Long>()
            ready.forEach { row ->
                if (exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(username)=LOWER(?))", row.username)) throw AppException("El usuario ${row.username} ya existe.")
                if (exists("SELECT EXISTS(SELECT 1 FROM usuarios WHERE LOWER(email)=LOWER(?))", row.email)) throw AppException("El correo ${row.email} ya existe.")
                if (exists("SELECT EXISTS(SELECT 1 FROM perfiles_usuario WHERE phone=?)", row.phone)) throw AppException("El teléfono ${row.phone} ya existe.")
                if (exists("SELECT EXISTS(SELECT 1 FROM verificaciones_documentos WHERE UPPER(document_number)=UPPER(?))", row.documentNumber)) throw AppException("El documento ${row.documentNumber} ya existe.")

                val userId = connection.prepareStatement(
                    """INSERT INTO usuarios(username,email,password_hash,pin_hash,role,account_status,verification_status,email_verified,phone_verified,created_by,registration_source,account_kind)
                       VALUES (?,?,?,?,'BENEFICIARY','PENDING_VERIFICATION','PENDING',TRUE,TRUE,?,'ADMIN_EXCEL','BENEFICIARY') RETURNING id"""
                ).use { statement ->
                    statement.setString(1, row.username)
                    statement.setString(2, row.email)
                    statement.setString(3, passwordSecurity.hash(row.password))
                    statement.setString(4, passwordSecurity.hash(row.pin))
                    statement.setLong(5, actorId)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
                connection.prepareStatement(
                    """INSERT INTO perfiles_usuario(user_id,full_name,first_name,middle_name,last_name,second_last_name,phone,birth_date,employment_type,state,municipality,parish,community,address)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, row.fullName)
                    statement.setString(3, row.firstName)
                    statement.setString(4, row.middleName.takeIf { it.isNotBlank() })
                    statement.setString(5, row.lastName)
                    statement.setString(6, row.secondLastName.takeIf { it.isNotBlank() })
                    statement.setString(7, row.phone)
                    statement.setObject(8, row.birthDate)
                    statement.setString(9, row.employmentType)
                    statement.setString(10, row.state.takeIf { it.isNotBlank() })
                    statement.setString(11, row.municipality.takeIf { it.isNotBlank() })
                    statement.setString(12, row.parish.takeIf { it.isNotBlank() })
                    statement.setString(13, row.community.takeIf { it.isNotBlank() })
                    statement.setString(14, row.address.takeIf { it.isNotBlank() })
                    statement.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO perfiles_financieros_usuario(user_id) VALUES (?) ON CONFLICT(user_id) DO NOTHING").use {
                    it.setLong(1, userId); it.executeUpdate()
                }
                // El Excel aporta identidad y credenciales; la revisión final queda pendiente del Contador.
                connection.prepareStatement(
                    """INSERT INTO verificaciones_documentos(user_id,document_type,document_number,front_file_path,status)
                       VALUES (?,?,?,'__EXCEL_IMPORT__','PENDING')"""
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, row.documentType)
                    statement.setString(3, row.documentNumber)
                    statement.executeUpdate()
                }
                audit(connection, actorId, "ADMIN_IMPORTED_BENEFICIARY_FROM_EXCEL", "USER", userId.toString(), "${row.username} · hoja=${row.sheetName} · fila=${row.rowNumber}")
                ids += userId
            }
            ids
        }
        val created = createdIds.map(::me)
        return StaffExcelImportResultDto(
            importedCount = created.size,
            skippedCount = skipped,
            importedUsers = created,
            message = buildString {
                append("Se cargaron ${created.size} Beneficiario(s). Quedaron pendientes de revisión administrativa. El acceso inicial usa el correo, contraseña Credi# + últimos 6 dígitos del documento + Aa1 y PIN con esos 6 dígitos.")
                if (skipped > 0) append(" $skipped fila(s) con errores fueron omitidas.")
            }
        )
    }

    fun adminImportedBeneficiaries(adminId: Long): List<UserDto> {
        if (currentRole(adminId) != Roles.ADMIN) throw ForbiddenException("Solo un Administrador puede consultar sus Beneficiarios cargados.")
        return database.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT id FROM usuarios WHERE role='BENEFICIARY' AND created_by=? ORDER BY created_at DESC").use { statement ->
                statement.setLong(1, adminId)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(userDto(connection, result.getLong(1))) } }
            }
        }
    }

    fun accountantBeneficiaries(accountantId: Long): List<UserDto> {
        requireAccountant(accountantId)
        return database.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT id FROM usuarios WHERE role='BENEFICIARY' ORDER BY created_at DESC").use { statement ->
                statement.executeQuery().use { result -> buildList { while (result.next()) add(userDto(connection, result.getLong(1))) } }
            }
        }
    }

    private data class ValidatedStaffExcelRow(
        val sheetName: String,
        val rowNumber: Int,
        val role: String,
        val firstName: String,
        val middleName: String,
        val lastName: String,
        val secondLastName: String,
        val fullName: String,
        val username: String,
        val email: String,
        val phone: String,
        val birthDate: LocalDate,
        val employmentType: String?,
        val documentType: String,
        val documentNumber: String,
        val state: String,
        val municipality: String,
        val parish: String,
        val community: String,
        val address: String,
        val password: String,
        val pin: String,
        val adminSubRole: String?,
        val errors: List<String>,
        val warnings: List<String>
    ) {
        fun toPreviewDto() = StaffExcelImportRowDto(
            sheetName = sheetName,
            rowNumber = rowNumber,
            role = when (role) { Roles.ADMIN -> "Administrador"; Roles.WAREHOUSE -> "Almacenista"; Roles.ACCOUNTANT -> "Contador"; "BENEFICIARY" -> "Beneficiario"; else -> role },
            fullName = fullName,
            username = username,
            email = email,
            phone = phone,
            birthDate = if (birthDate.year > 1900) birthDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) else "Requiere corrección",
            employmentType = when (employmentType) { "PUBLIC_EMPLOYEE" -> "Empleado público"; "PRIVATE_EMPLOYEE" -> "Empleado privado"; else -> "No registrado" },
            documentType = when (documentType) { "NATIONAL_ID" -> "Cédula"; "PASSPORT" -> "Pasaporte"; else -> documentType.ifBlank { "No registrado" } },
            documentNumber = documentNumber,
            state = state,
            municipality = municipality,
            parish = parish,
            community = community,
            address = address,
            adminSubRole = when (adminSubRole) {
                "GENERAL" -> "General"; "SUPERVISOR" -> "Supervisor"; "ANALYST" -> "Analista";
                "SUPPORT" -> "Soporte"; "AUDITOR" -> "Auditor"; "ANTIFRAUD" -> "Antifraude";
                else -> adminSubRole.orEmpty()
            },
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    private fun validateStaffExcelRows(bytes: ByteArray, beneficiaryOnly: Boolean = false): Pair<List<ValidatedStaffExcelRow>, List<String>> {
        val parsed = StaffExcelWorkbookParser.parse(bytes)
        val existing = database.transaction { connection ->
            val usernames = mutableSetOf<String>()
            val emails = mutableSetOf<String>()
            val phones = mutableSetOf<String>()
            val documents = mutableSetOf<String>()
            connection.createStatement().use { st ->
                st.executeQuery("SELECT LOWER(username),LOWER(email) FROM usuarios").use { rs ->
                    while (rs.next()) { usernames += rs.getString(1).orEmpty(); emails += rs.getString(2).orEmpty() }
                }
                st.executeQuery("SELECT phone FROM perfiles_usuario").use { rs -> while (rs.next()) phones += rs.getString(1).orEmpty() }
                st.executeQuery("SELECT UPPER(document_number) FROM verificaciones_documentos").use { rs -> while (rs.next()) documents += rs.getString(1).orEmpty() }
            }
            listOf(usernames, emails, phones, documents)
        }
        val existingUsernames = existing[0]
        val existingEmails = existing[1]
        val existingPhones = existing[2]
        val existingDocuments = existing[3]

        fun rawKey(row: StaffExcelRawRow, key: String): String = row.values[key].orEmpty().trim()
        fun roleFor(row: StaffExcelRawRow): String {
            val value = rawKey(row, "role").ifBlank { row.inferredRole.orEmpty() }.trim().uppercase(Locale.ROOT)
            return when (value) {
                "ADMIN", "ADMINISTRATOR", "ADMINISTRADOR", "ADMINISTRADORES" -> Roles.ADMIN
                "WAREHOUSE", "ALMACENISTA", "ALMACENISTAS", "ALMACEN", "BODEGA" -> Roles.WAREHOUSE
                "ACCOUNTANT", "CONTADOR", "CONTADORA", "CONTADORES", "CONTABLE" -> Roles.ACCOUNTANT
                "BENEFICIARY", "BENEFICIARIO", "BENEFICIARIOS", "USUARIO" -> "BENEFICIARY"
                else -> value
            }
        }

        val usernameCounts = parsed.rows.groupingBy { rawKey(it, "username").lowercase(Locale.ROOT) }.eachCount()
        val emailCounts = parsed.rows.groupingBy { rawKey(it, "email").lowercase(Locale.ROOT) }.eachCount()
        val documentCounts = parsed.rows.groupingBy { rawKey(it, "documentNumber").uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9-]"), "") }.eachCount()
        val phoneCounts = parsed.rows.groupingBy { rawKey(it, "phone").filter(Char::isDigit).removePrefix("0058").removePrefix("58").removePrefix("0") }.eachCount()

        val validated = parsed.rows.map { raw ->
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val detectedRole = roleFor(raw)
            val role = if (beneficiaryOnly && detectedRole.isBlank()) "BENEFICIARY" else detectedRole
            if (beneficiaryOnly) {
                if (role != "BENEFICIARY") errors += "Esta carga es exclusiva para Beneficiarios; no incluyas personal operativo en este archivo."
            } else if (role !in setOf(Roles.ADMIN, Roles.WAREHOUSE, Roles.ACCOUNTANT)) {
                errors += if (role == "BENEFICIARY") "La importación de personal operativo no admite Beneficiarios." else "Rol no reconocido. Usa Administrador, Almacenista o Contador."
            }

            var firstName = rawKey(raw, "firstName")
            var middleName = rawKey(raw, "middleName")
            var lastName = rawKey(raw, "lastName")
            var secondLastName = rawKey(raw, "secondLastName")
            val suppliedFullName = rawKey(raw, "fullName")
            if ((firstName.isBlank() || lastName.isBlank()) && suppliedFullName.isNotBlank()) {
                val parts = suppliedFullName.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    firstName = parts.first()
                    when (parts.size) {
                        2 -> lastName = parts[1]
                        3 -> { lastName = parts[1]; secondLastName = parts[2] }
                        else -> {
                            middleName = parts.subList(1, parts.size - 2).joinToString(" ")
                            lastName = parts[parts.size - 2]
                            secondLastName = parts.last()
                        }
                    }
                    warnings += "El nombre completo fue separado automáticamente; revisa nombres y apellidos."
                }
            }
            if (firstName.length < 2 || lastName.length < 2) errors += "Faltan nombre y apellido válidos."
            listOf(firstName, middleName, lastName, secondLastName).filter { it.isNotBlank() }.forEach {
                if (!isSafePersonName(it)) errors += "Nombres y apellidos solo pueden contener letras y espacios."
            }
            val fullName = listOf(firstName, middleName, lastName, secondLastName).filter { it.isNotBlank() }.joinToString(" ")

            val email = rawKey(raw, "email").lowercase(Locale.ROOT)
            if (!EMAIL_REGEX.matches(email)) errors += "Correo electrónico inválido."
            val documentSeed = rawKey(raw, "documentNumber").uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
            val generatedUsername = buildString {
                append('b')
                append(email.substringBefore('@').lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.]"), "").take(11))
                append('_')
                append(documentSeed.lowercase(Locale.ROOT).takeLast(8))
            }.take(24)
            val usernameRaw = rawKey(raw, "username").ifBlank { if (beneficiaryOnly) generatedUsername else "" }
            val username = runCatching { validateUsername(usernameRaw) }.getOrElse {
                errors += (it.message ?: "Usuario inválido.")
                usernameRaw.trim()
            }
            val documentDigits = documentSeed.filter(Char::isDigit)
            val generatedTail = documentDigits.takeLast(6).padStart(6, '0')
            val password = rawKey(raw, "password").ifBlank { if (beneficiaryOnly) "Credi#${generatedTail}Aa1" else "" }
            PasswordPolicy.validationError(password, username, email)?.let { errors += it }
            val pin = rawKey(raw, "pin").ifBlank { if (beneficiaryOnly) generatedTail else "" }
            PinPolicy.validationError(pin)?.let(errors::add)
            if (beneficiaryOnly && rawKey(raw, "username").isBlank()) warnings += "Usuario generado automáticamente; también puede iniciar sesión con el correo."
            if (beneficiaryOnly && rawKey(raw, "password").isBlank()) warnings += "Credenciales iniciales generadas a partir de los últimos 6 dígitos del documento."

            val phoneRaw = rawKey(raw, "phone")
            val phone = normalizeVenezuelanPhone(phoneRaw).orEmpty()
            if (phone.isBlank()) errors += "Teléfono venezolano inválido. Ejemplo: +58 412-1234567."

            val birthRaw = rawKey(raw, "birthDate")
            val birthDate = runCatching { LocalDate.parse(birthRaw) }.getOrElse {
                errors += "Fecha de nacimiento inválida. Usa AAAA-MM-DD o DD/MM/AAAA."
                LocalDate.of(1900, 1, 1)
            }
            if (birthDate.year > 1900 && ChronoUnit.YEARS.between(birthDate, LocalDate.now()) < 18) errors += "La persona debe ser mayor de 18 años."

            val documentTypeRaw = rawKey(raw, "documentType")
            val documentType = when (StaffExcelWorkbookParser.normalizeHeader(documentTypeRaw)) {
                "", "cedula", "cedula de identidad", "national id" -> "NATIONAL_ID"
                "pasaporte", "passport" -> "PASSPORT"
                "rif", "registro de informacion fiscal", "tax id" -> "TAX_ID"
                else -> documentTypeRaw.trim().uppercase(Locale.ROOT)
            }
            if (documentType !in setOf("NATIONAL_ID", "PASSPORT")) errors += "Tipo de documento inválido. Usa Cédula o Pasaporte. El RIF es exclusivo de Negocios asociados."
            val documentNumber = rawKey(raw, "documentNumber").uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9-]"), "")
            if (!documentNumber.matches(Regex("^[A-Z0-9-]{5,30}$"))) errors += "Número de documento inválido."

            val employmentTypeRaw = StaffExcelWorkbookParser.normalizeHeader(rawKey(raw, "employmentType"))
            val employmentType = when (employmentTypeRaw) {
                "", "no registrado", "no aplica" -> null
                "empleado publico", "publico", "empleo publico", "public employee" -> "PUBLIC_EMPLOYEE"
                "empleado privado", "privado", "empleo privado", "private employee" -> "PRIVATE_EMPLOYEE"
                else -> { warnings += "Tipo de empleo no reconocido; se guardará como no registrado."; null }
            }

            val adminSubRole = if (role == Roles.ADMIN) {
                when (StaffExcelWorkbookParser.normalizeHeader(rawKey(raw, "adminSubRole"))) {
                    "", "general" -> "GENERAL"
                    "supervisor" -> "SUPERVISOR"
                    "analista" -> "ANALYST"
                    "soporte" -> "SUPPORT"
                    "auditor" -> "AUDITOR"
                    "antifraude", "anti fraude" -> "ANTIFRAUD"
                    else -> { errors += "Perfil administrativo inválido."; "GENERAL" }
                }
            } else null

            if (username.isNotBlank() && username.lowercase(Locale.ROOT) in existingUsernames) errors += "El usuario ya existe en Kredi+."
            if (email.isNotBlank() && email in existingEmails) errors += "El correo ya existe en Kredi+."
            if (phone.isNotBlank() && phone in existingPhones) errors += "El teléfono ya existe en Kredi+."
            if (documentNumber.isNotBlank() && documentNumber in existingDocuments) errors += "El documento ya existe en Kredi+."

            if (!beneficiaryOnly && usernameRaw.isNotBlank() && (usernameCounts[usernameRaw.lowercase(Locale.ROOT)] ?: 0) > 1) errors += "El usuario está repetido dentro del Excel."
            if (email.isNotBlank() && (emailCounts[email] ?: 0) > 1) errors += "El correo está repetido dentro del Excel."
            val phoneKey = phoneRaw.filter(Char::isDigit).removePrefix("0058").removePrefix("58").removePrefix("0")
            if (phoneKey.isNotBlank() && (phoneCounts[phoneKey] ?: 0) > 1) errors += "El teléfono está repetido dentro del Excel."
            if (documentNumber.isNotBlank() && (documentCounts[documentNumber] ?: 0) > 1) errors += "El documento está repetido dentro del Excel."

            ValidatedStaffExcelRow(
                sheetName = raw.sheetName,
                rowNumber = raw.rowNumber,
                role = role,
                firstName = firstName.trim(),
                middleName = middleName.trim(),
                lastName = lastName.trim(),
                secondLastName = secondLastName.trim(),
                fullName = fullName,
                username = username.trim(),
                email = email,
                phone = phone,
                birthDate = birthDate,
                employmentType = employmentType,
                documentType = documentType,
                documentNumber = documentNumber,
                state = rawKey(raw, "state"),
                municipality = rawKey(raw, "municipality"),
                parish = rawKey(raw, "parish"),
                community = rawKey(raw, "community"),
                address = rawKey(raw, "address"),
                password = password,
                pin = pin,
                adminSubRole = adminSubRole,
                errors = errors.distinct(),
                warnings = warnings.distinct()
            )
        }
        return validated to parsed.ignoredSheets
    }

    /**
     * Remueve únicamente el acceso operativo de un Administrador.
     *
     * La fila de usuario se conserva deliberadamente para mantener intactas todas las
     * referencias históricas (auditoría, jornadas, inventario, pagos y demás acciones).
     * Si existe una cuenta Beneficiario vinculada, esa segunda cuenta no se modifica.
     */
    fun removeAdministratorAccess(actorId: Long, targetUserId: Long) {
        requirePermission(actorId, "MANAGE_STAFF_ROLES")
        database.ensureAuthenticationSchema()
        if (actorId == targetUserId) throw AppException("No puedes remover tu propio acceso.")

        database.transaction { connection ->
            val target = connection.prepareStatement(
                "SELECT role,account_status,account_kind,username,linked_account_user_id FROM usuarios WHERE id=? FOR UPDATE"
            ).use { statement ->
                statement.setLong(1, targetUserId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("El Administrador no existe.")
                    StaffAccessTarget(
                        role = Roles.canonical(result.getString("role")),
                        accountStatus = result.getString("account_status").orEmpty(),
                        accountKind = result.getString("account_kind"),
                        username = result.getString("username").orEmpty(),
                        linkedAccountUserId = result.getObject("linked_account_user_id")?.let { (it as Number).toLong() }
                    )
                }
            }

            if (target.role != Roles.ADMIN) throw AppException("Solo se puede remover el acceso de un Administrador.")
            if (!target.accountKind.isNullOrBlank() && !target.accountKind.equals("OPERATIONAL", true)) {
                throw AppException("La cuenta seleccionada no es un acceso operativo de Administrador.")
            }
            if (target.accountStatus.equals("BLOCKED", true) || target.accountStatus.equals("SUSPENDED", true)) {
                throw AppException("El acceso de este Administrador ya fue retirado.")
            }

            connection.prepareStatement(
                "UPDATE usuarios SET account_status='BLOCKED',suspended_at=NULL,suspension_reason=NULL,suspended_by=NULL,updated_at=NOW() WHERE id=?"
            ).use { statement ->
                statement.setLong(1, targetUserId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE sesiones_usuario SET revoked_at=COALESCE(revoked_at,NOW()),ended_reason=COALESCE(ended_reason,'ADMIN_ACCESS_REMOVED') WHERE user_id=? AND revoked_at IS NULL"
            ).use { statement ->
                statement.setLong(1, targetUserId)
                statement.executeUpdate()
            }

            audit(
                connection,
                actorId,
                "ACCOUNTANT_ADMIN_ACCESS_REMOVED",
                "USER_ACCESS",
                targetUserId.toString(),
                "Administrador=${target.username} · registro conservado · beneficiary=${target.linkedAccountUserId ?: "none"}"
            )
        }
    }

    private data class StaffAccessTarget(
        val role: String,
        val accountStatus: String,
        val accountKind: String?,
        val username: String,
        val linkedAccountUserId: Long?
    )

    /**
     * Compatibilidad anterior: se conserva el endpoint de cambio de rol para clientes
     * antiguos, pero las interfaces 7.2.2 ya no lo ofrecen como flujo principal.
     */
    fun updateAdminSubrole(actorId: Long, targetUserId: Long, request: AdminSubroleUpdateRequest): UserDto {
        requirePermission(actorId, "MANAGE_ADMIN_ROLES")
        val subRole = request.subRole.trim().uppercase()
        val allowed = setOf("GENERAL", "SUPERVISOR", "ANALYST", "SUPPORT", "AUDITOR", "ANTIFRAUD")
        if (subRole !in allowed) throw AppException("Perfil administrativo inválido.")
        if (actorId == targetUserId && subRole != "GENERAL") {
            throw AppException("No puedes reducir tus propios permisos administrativos.")
        }
        database.transaction { connection ->
            val role = connection.prepareStatement("SELECT role FROM usuarios WHERE id=? FOR UPDATE").use { statement ->
                statement.setLong(1, targetUserId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("El usuario no existe.")
                    Roles.canonical(result.getString(1))
                }
            }
            if (role != Roles.ADMIN) throw AppException("Solo los administradores pueden recibir un perfil administrativo.")
            connection.prepareStatement("UPDATE usuarios SET admin_subrole=? WHERE id=?").use { statement ->
                statement.setString(1, subRole)
                statement.setLong(2, targetUserId)
                statement.executeUpdate()
            }
            audit(connection, actorId, "ADMIN_SUBROLE_UPDATED", "USER", targetUserId.toString(), subRole)
        }
        return me(targetUserId)
    }

    fun updateWarehouseOrderStatus(actorId: Long, orderId: Long, request: WarehouseOrderStatusRequest): PurchaseDto {
        requirePermission(actorId, "MANAGE_ORDERS")
        val status = request.status.trim().uppercase().replace(' ', '_')
        val allowed = setOf("PREPARING", "READY", "DELIVERED", "STOCK_SHORTAGE", "RETURNED", "CANCELLED")
        if (status !in allowed) throw AppException("Estado de almacén inválido.")
        val userId = database.transaction { connection ->
            val order = connection.prepareStatement("SELECT user_id,status FROM pedidos WHERE id=? FOR UPDATE").use { statement ->
                statement.setLong(1, orderId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw NotFoundException("El pedido no existe.")
                    result.getLong(1) to result.getString(2).orEmpty().trim().uppercase().replace(' ', '_')
                }
            }
            val currentStatus = order.second
            val verifiedForPreparation = setOf(
                "PAGO_VERIFICADO", "CRÉDITO_ACTIVO", "CREDITO_ACTIVO", "APPROVED", "PAID", "VERIFIED"
            )
            val allowedTransitions = when {
                currentStatus in verifiedForPreparation -> setOf("PREPARING", "CANCELLED")
                currentStatus in setOf("PREPARING", "IN_PREPARATION", "STOCK_SHORTAGE") ->
                    setOf("PREPARING", "READY", "STOCK_SHORTAGE", "CANCELLED")
                currentStatus == "READY" -> setOf("DELIVERED", "RETURNED", "CANCELLED")
                currentStatus == "DELIVERED" -> setOf("RETURNED")
                else -> emptySet()
            }
            if (status !in allowedTransitions) {
                throw AppException(
                    when (currentStatus) {
                        "PAGO_REPORTADO", "REPORTED", "PENDING" ->
                            "El pago debe ser verificado por un administrador antes de preparar el pedido."
                        "PAGO_RECHAZADO", "REJECTED" ->
                            "No puedes preparar un pedido con el pago rechazado."
                        "RETURNED", "CANCELLED", "CANCELED" ->
                            "El pedido está finalizado y no admite este cambio."
                        else -> "La transición de ${currentStatus.ifBlank { "estado desconocido" }} a $status no está permitida."
                    }
                )
            }
            connection.prepareStatement("UPDATE pedidos SET status=?,updated_at=NOW() WHERE id=?").use { statement ->
                statement.setString(1, status)
                statement.setLong(2, orderId)
                statement.executeUpdate()
            }
            audit(connection, actorId, "WAREHOUSE_ORDER_STATUS_UPDATED", "ORDER", orderId.toString(), "$currentStatus -> $status · ${request.notes.orEmpty().take(300)}")
            order.first
        }
        notifyUsers(
            listOf(userId),
            "Actualización de tu pedido",
            when (status) {
                "PREPARING" -> "Tu pedido #$orderId está en preparación."
                "READY" -> "Tu pedido #$orderId está listo para entregar."
                "DELIVERED" -> "La entrega del pedido #$orderId fue confirmada."
                "STOCK_SHORTAGE" -> "El pedido #$orderId presenta una existencia incompleta."
                "RETURNED" -> "El pedido #$orderId fue registrado como devuelto."
                else -> "El pedido #$orderId fue cancelado."
            },
            "WAREHOUSE_ORDER_STATUS",
            mapOf("orderId" to orderId.toString(), "status" to status)
        )
        return adminPurchases().first { it.id == orderId }
    }

    fun requirePermission(userId: Long, permission: String) {
        requireAnyPermission(userId, permission)
    }

    fun requireAnyPermission(userId: Long, vararg requiredPermissions: String) {
        val role = currentRole(userId)
        val subRole = currentSubRole(userId, role)
        val permissions = permissionsFor(role, subRole)
        val allowed = "FULL_ADMIN" in permissions || requiredPermissions.any { it in permissions }
        if (!allowed) throw ForbiddenException()
    }

    private fun currentSubRole(userId: Long, role: String = currentRole(userId)): String =
        database.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COALESCE(admin_subrole,CASE WHEN role='ADMIN' THEN 'GENERAL' WHEN role='ACCOUNTANT' THEN 'ACCOUNTING' WHEN role='WAREHOUSE' THEN 'WAREHOUSE' ELSE 'USER' END) FROM usuarios WHERE id=?"
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getString(1)
                    else if (role == Roles.ADMIN) "GENERAL" else if (role == Roles.ACCOUNTANT) "ACCOUNTING" else if (role == Roles.WAREHOUSE) "WAREHOUSE" else "USER"
                }
            }
        }

    private fun permissionsFor(role: String, subRole: String): List<String> = when (role) {
        Roles.BENEFICIARY -> listOf(
            "VIEW_OWN_DATA", "REPORT_PAYMENT", "MANAGE_SECURITY", "VIEW_INVOICES"
        )
        Roles.ACCOUNTANT -> listOf(
            "VIEW_USERS", "REVIEW_USERS",
            "VIEW_PAYMENTS", "REVIEW_PAYMENTS", "RECONCILE_PAYMENTS", "VIEW_AUDIT"
        )
        Roles.WAREHOUSE -> listOf(
            "VIEW_INVENTORY", "MANAGE_INVENTORY", "CREATE_PRODUCTS", "MANAGE_PRODUCTS",
            "VIEW_ORDERS", "MANAGE_ORDERS", "MANAGE_PRODUCT_IMAGES", "VIEW_NOTIFICATIONS"
        )
        Roles.ADMIN -> when (subRole) {
            "SUPPORT" -> listOf(
                "VIEW_USERS", "VIEW_ORDERS", "VIEW_NOTIFICATIONS"
            )
            "AUDITOR" -> listOf(
                "VIEW_USERS", "VIEW_AUDIT", "VIEW_PAYMENTS", "VIEW_FINANCIALS",
                "VIEW_ORDERS", "VIEW_INVENTORY"
            )
            "ANTIFRAUD" -> listOf(
                "VIEW_USERS", "VIEW_PAYMENTS", "REVIEW_PAYMENTS", "VIEW_FRAUD_SIGNALS", "VIEW_AUDIT"
            )
            "ANALYST" -> listOf(
                "VIEW_USERS", "REVIEW_USERS", "VIEW_PAYMENTS", "REVIEW_PAYMENTS",
                "REVIEW_CREDITS", "VIEW_ORDERS", "MANAGE_ORDERS", "VIEW_INVENTORY"
            )
            "SUPERVISOR" -> listOf(
                "VIEW_USERS", "REVIEW_USERS", "VIEW_PAYMENTS", "REVIEW_PAYMENTS",
                "REVIEW_CREDITS", "VIEW_ORDERS", "MANAGE_ORDERS", "VIEW_INVENTORY",
                "MANAGE_CATALOG", "MANAGE_PRICING", "MANAGE_INVENTORY", "APPROVE_SENSITIVE_ACTIONS", "VIEW_AUDIT"
            )
            else -> listOf(
                "FULL_ADMIN", "VIEW_USERS", "REVIEW_USERS", "VIEW_PAYMENTS", "REVIEW_PAYMENTS",
                "REVIEW_CREDITS", "VIEW_ORDERS", "MANAGE_ORDERS", "VIEW_INVENTORY",
                "MANAGE_CATALOG", "MANAGE_PRICING", "MANAGE_INVENTORY",
                "MANAGE_ADMIN_ROLES", "APPROVE_SENSITIVE_ACTIONS", "VIEW_AUDIT", "VIEW_FINANCIALS"
            )
        }
        else -> emptyList()
    }

    fun publicUrl(relativePath: String?): String? = relativePath
        ?.takeIf { it.isNotBlank() && it != "__EXCEL_IMPORT__" }
        ?.let { uploadAccessPolicy.url(config.publicBaseUrl, it) }

    fun canReadUpload(relativePath: String, expires: String?, signature: String?): Boolean =
        uploadAccessPolicy.canRead(relativePath, expires, signature)

    fun isPrivateUpload(relativePath: String): Boolean = uploadAccessPolicy.isPrivate(relativePath)

    private fun userDto(connection: Connection, userId: Long): UserDto {
        // El inicio de sesión debe depender únicamente de la tabla usuarios.
        // Perfil, documentos, Credimpulso e historial son enriquecimientos opcionales.
        val minimalUser = connection.prepareStatement(
            """
            SELECT id,username,email,role,verification_status,account_status,admin_subrole,person_group_id,account_kind,linked_account_user_id,suspension_reason,suspended_at,suspended_by,created_at,last_login_at
            FROM usuarios WHERE id=?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("No se encontró el usuario.")
                val email = result.getString("email") ?: ""
                UserDto(
                    id = result.getLong("id"),
                    username = result.getString("username").orEmpty(),
                    fullName = email,
                    email = email,
                    role = Roles.canonical(result.getString("role") ?: "BENEFICIARY"),
                    verificationStatus = result.getString("verification_status") ?: "NOT_SUBMITTED",
                    accountStatus = result.getString("account_status") ?: "PENDING_VERIFICATION",
                    adminSubrole = result.getString("admin_subrole"),
                    personGroupId = result.getObject("person_group_id", UUID::class.java)?.toString(),
                    accountKind = result.getString("account_kind"),
                    linkedAccountUserId = result.getLong("linked_account_user_id").takeUnless { result.wasNull() },
                    suspensionReason = result.getString("suspension_reason"),
                    suspendedAt = result.getObject("suspended_at", OffsetDateTime::class.java)?.toString(),
                    suspendedBy = result.getLong("suspended_by").takeUnless { result.wasNull() },
                    createdAt = result.getObject("created_at", OffsetDateTime::class.java)?.toString(),
                    lastLoginAt = result.getObject("last_login_at", OffsetDateTime::class.java)?.toString()
                )
            }
        }

        val baseUser = withSavepointFallback(
            connection = connection,
            fallback = minimalUser,
            context = "cargar perfil enriquecido para la sesión"
        ) {
            connection.prepareStatement(
                """
                SELECT u.id,u.username,u.email,u.role,u.verification_status,u.account_status,u.admin_subrole,u.person_group_id,u.account_kind,u.linked_account_user_id,u.suspension_reason,u.suspended_at,u.suspended_by,u.created_at,u.last_login_at,
                       COALESCE(NULLIF(TRIM(up.full_name),''),u.email) AS full_name,
                       up.first_name,up.middle_name,up.last_name,up.second_last_name,up.phone,up.birth_date,up.employment_type,up.state,up.municipality,up.parish,up.community,up.address,
                       dv.document_type,dv.document_number,
                       cc.level AS credit_level,
                       CASE COALESCE(cc.level,1)
                           WHEN 1 THEN 'Santa Ana'
                           WHEN 2 THEN 'El Ávila'
                           WHEN 3 THEN 'Autana'
                           WHEN 4 THEN 'Auyantepuy'
                           WHEN 5 THEN 'Pico Bolívar'
                           WHEN 6 THEN 'Salto Ángel'
                           ELSE 'Santa Ana'
                       END AS credit_level_name
                FROM usuarios u LEFT JOIN perfiles_usuario up ON up.user_id=u.id
                LEFT JOIN cuentas_credito cc ON cc.user_id=u.id
                LEFT JOIN LATERAL (
                    SELECT document_type,document_number FROM verificaciones_documentos WHERE user_id=u.id ORDER BY submitted_at DESC LIMIT 1
                ) dv ON TRUE
                WHERE u.id=?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@withSavepointFallback minimalUser
                    UserDto(
                        id = result.getLong("id"),
                        username = result.getString("username").orEmpty(),
                        fullName = result.getString("full_name") ?: minimalUser.fullName,
                        firstName = result.getString("first_name"),
                        middleName = result.getString("middle_name"),
                        lastName = result.getString("last_name"),
                        secondLastName = result.getString("second_last_name"),
                        email = result.getString("email") ?: minimalUser.email,
                        phone = result.getString("phone"),
                        birthDate = result.getObject("birth_date", LocalDate::class.java)?.toString(),
                        role = Roles.canonical(result.getString("role") ?: minimalUser.role),
                        verificationStatus = result.getString("verification_status") ?: minimalUser.verificationStatus,
                        accountStatus = result.getString("account_status") ?: minimalUser.accountStatus,
                        documentType = result.getString("document_type"),
                        documentNumber = result.getString("document_number"),
                        documentNumberMasked = result.getString("document_number")?.let(::maskDocument),
                        state = result.getString("state"),
                        municipality = result.getString("municipality"),
                        parish = result.getString("parish"),
                        community = result.getString("community"),
                        address = result.getString("address"),
                        employmentType = result.getString("employment_type"),
                        creditLevel = result.getInt("credit_level").takeUnless { result.wasNull() },
                        creditLevelName = result.getString("credit_level_name"),
                        adminSubrole = result.getString("admin_subrole"),
                        personGroupId = result.getObject("person_group_id", UUID::class.java)?.toString(),
                        accountKind = result.getString("account_kind"),
                        linkedAccountUserId = result.getLong("linked_account_user_id").takeUnless { result.wasNull() },
                        suspensionReason = result.getString("suspension_reason"),
                        suspendedAt = result.getObject("suspended_at", OffsetDateTime::class.java)?.toString(),
                        suspendedBy = result.getLong("suspended_by").takeUnless { result.wasNull() },
                        createdAt = result.getObject("created_at", OffsetDateTime::class.java)?.toString(),
                        lastLoginAt = result.getObject("last_login_at", OffsetDateTime::class.java)?.toString()
                    )
                }
            }
        }

        val provenance = withSavepointFallback(
            connection = connection,
            fallback = UserProvenance(null, null, null, null),
            context = "cargar trazabilidad de alta de usuario"
        ) {
            connection.prepareStatement(
                """
                SELECT u.created_by,u.registration_source,c.username,
                       COALESCE(NULLIF(TRIM(cp.full_name),''),c.email) AS creator_name
                FROM usuarios u
                LEFT JOIN usuarios c ON c.id=u.created_by
                LEFT JOIN perfiles_usuario cp ON cp.user_id=c.id
                WHERE u.id=?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { result ->
                    if (!result.next()) UserProvenance(null, null, null, null)
                    else UserProvenance(
                        createdByUserId = result.getLong("created_by").takeUnless { result.wasNull() },
                        createdByUsername = result.getString("username"),
                        createdByName = result.getString("creator_name"),
                        registrationSource = result.getString("registration_source")
                    )
                }
            }
        }
        val sourcedUser = baseUser.copy(
            createdByUserId = provenance.createdByUserId,
            createdByUsername = provenance.createdByUsername,
            createdByName = provenance.createdByName,
            registrationSource = provenance.registrationSource
        )

        if (sourcedUser.role != "BENEFICIARY") return sourcedUser

        val history = withSavepointFallback(
            connection = connection,
            fallback = CreditHistorySnapshot(100, 0, 0, "ACTIVE"),
            context = "cargar historial crediticio para la sesión"
        ) {
            creditHistorySnapshot(connection, userId)
        }

        return sourcedUser.copy(
            creditScorePercentage = history.scorePercentage.coerceIn(0, 100),
            creditHistoryStatus = history.status
        )
    }

    private fun verificationDto(connection: Connection, result: ResultSet): VerificationDto {
        val frontPath = result.getString("front_file_path")
        val excelImported = frontPath == "__EXCEL_IMPORT__"
        return VerificationDto(
            id = result.getLong("id"), user = userDto(connection, result.getLong("user_id")), documentType = result.getString("document_type"), documentNumber = result.getString("document_number"),
            documentUrl = if (excelImported) "" else publicUrl(frontPath) ?: "",
            backDocumentUrl = if (excelImported) null else publicUrl(result.getString("back_file_path")),
            selfieUrl = if (excelImported) null else publicUrl(result.getString("selfie_file_path")),
            status = result.getString("status"), rejectionReason = result.getString("rejection_reason"), submittedAt = result.getObject("submitted_at", OffsetDateTime::class.java).toString()
        )
    }

    private fun productDto(result: ResultSet): ProductDto {
        val priceBs = result.getBigDecimal("base_price") ?: java.math.BigDecimal.ZERO
        val priceUsd = result.getBigDecimal("base_price_usd") ?: java.math.BigDecimal.ZERO
        val bcvRate = result.getBigDecimal("bcv_rate") ?: java.math.BigDecimal.ZERO
        return ProductDto(
            id = result.getLong("id"),
            name = result.getString("name"),
            category = result.getString("category"),
            unit = result.getString("unit"),
            price = priceBs.toDouble(),
            stock = result.getInt("stock"),
            priceUsd = priceUsd.toDouble(),
            priceBs = priceBs.toDouble(),
            bcvRate = bcvRate.toDouble(),
            pricingMode = result.getString("pricing_mode") ?: "UNIT",
            details = result.getString("technical_details").orEmpty(),
            imageUrl = runCatching { publicUrl(result.getString("image_path")) }.getOrNull()
        )
    }

    private fun findProduct(connection: Connection, id: Long): ProductDto = connection.prepareStatement(
        """SELECT p.id,p.name,p.category,p.unit,p.technical_details,p.base_price,p.stock,p.base_price_usd,p.bcv_rate,p.pricing_mode,
                  (SELECT pj.image_path FROM productos_jornada pj
                   WHERE pj.product_id=p.id AND pj.image_path IS NOT NULL AND BTRIM(pj.image_path)<>''
                   ORDER BY pj.fair_id DESC LIMIT 1) AS image_path
           FROM productos p WHERE p.id=?""".trimIndent()
    ).use { statement ->
        statement.setLong(1, id); statement.executeQuery().use { result -> if (result.next()) productDto(result) else throw NotFoundException("El producto no existe.") }
    }

    /**
     * Jornada técnica no publicada usada únicamente como ancla contable para compras del catálogo
     * permanente. Los productos siguen siendo comprables aunque no exista ninguna jornada pública.
     */
    private fun ensurePermanentCatalogFair(connection: Connection, saleType: String): Long {
        data class CatalogBusiness(
            val id: Long, val paymentMode: String,
            val mobileBank: String?, val mobilePhone: String?, val mobileIdentity: String?, val mobileHolder: String?,
            val bankName: String?, val bankType: String?, val bankAccount: String?, val bankIdentity: String?, val bankHolder: String?
        )
        val normalizedType = saleType.trim().uppercase(Locale.ROOT)
        val businessColumn = when (normalizedType) {
            "PRODUCT" -> "product_business_id"
            "COMBO" -> "combo_business_id"
            else -> throw AppException("Tipo de venta de catálogo inválido.")
        }
        val configuredBusinessId = connection.prepareStatement(
            "SELECT $businessColumn FROM configuracion_ventas_catalogo WHERE id=1"
        ).use { st -> st.executeQuery().use { r ->
            if (!r.next()) null else r.getLong(1).let { if (r.wasNull()) null else it }
        } } ?: throw AppException(
            if (normalizedType == "PRODUCT") "Las ventas individuales no tienen un negocio de pago asociado."
            else "Los combos no tienen un negocio de pago asociado."
        )
        val business = connection.prepareStatement(
            """SELECT id,payment_mode,mobile_bank,mobile_phone,mobile_identity_number,mobile_holder_name,
                      bank_name,bank_account_type,bank_account_number,bank_identity_number,bank_holder_name
               FROM negocios_asociados WHERE id=? AND active=TRUE"""
        ).use { st ->
            st.setLong(1, configuredBusinessId)
            st.executeQuery().use { r ->
                if (!r.next()) throw AppException("El negocio asociado al catálogo está inactivo o ya no existe.")
                CatalogBusiness(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11))
            }
        }
        val marker = if (normalizedType == "PRODUCT") "KREDI_CATALOGO_PRODUCTOS" else "KREDI_CATALOGO_COMBOS"
        val fairName = if (normalizedType == "PRODUCT") "Catálogo Kredi+" else "Combos Kredi+"
        val fairId = connection.prepareStatement("SELECT id FROM jornadas WHERE place=? ORDER BY id LIMIT 1 FOR UPDATE").use { st ->
            st.setString(1, marker)
            st.executeQuery().use { r -> if (r.next()) r.getLong(1) else 0L }
        }.let { existing ->
            if (existing > 0L) {
                connection.prepareStatement(
                    "UPDATE jornadas SET name=?,schedule_text='Disponible siempre',description='Venta permanente independiente de jornadas',published=FALSE,active=TRUE,finalized=FALSE,payment_mode=?,business_id=?,updated_at=NOW() WHERE id=?"
                ).use { st -> st.setString(1,fairName); st.setString(2,business.paymentMode); st.setLong(3,business.id); st.setLong(4,existing); st.executeUpdate() }
                existing
            } else {
                connection.prepareStatement(
                    "INSERT INTO jornadas(name,place,schedule_text,description,published,active,payment_mode,business_id) VALUES (?,?,'Disponible siempre','Catálogo permanente independiente de jornadas',FALSE,TRUE,?,?) RETURNING id"
                ).use { st -> st.setString(1,fairName); st.setString(2,marker); st.setString(3,business.paymentMode); st.setLong(4,business.id); st.executeQuery().use { r -> r.next(); r.getLong(1) } }
            }
        }
        connection.prepareStatement(
            """INSERT INTO detalles_pago_jornada(fair_id,mobile_bank,mobile_phone,mobile_identity_number,mobile_holder_name,bank_name,bank_account_type,bank_account_number,bank_identity_number,bank_holder_name,updated_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,NOW())
               ON CONFLICT(fair_id) DO UPDATE SET mobile_bank=EXCLUDED.mobile_bank,mobile_phone=EXCLUDED.mobile_phone,mobile_identity_number=EXCLUDED.mobile_identity_number,mobile_holder_name=EXCLUDED.mobile_holder_name,bank_name=EXCLUDED.bank_name,bank_account_type=EXCLUDED.bank_account_type,bank_account_number=EXCLUDED.bank_account_number,bank_identity_number=EXCLUDED.bank_identity_number,bank_holder_name=EXCLUDED.bank_holder_name,updated_at=NOW()"""
        ).use { st ->
            st.setLong(1,fairId); st.setString(2,business.mobileBank); st.setString(3,business.mobilePhone); st.setString(4,business.mobileIdentity); st.setString(5,business.mobileHolder)
            st.setString(6,business.bankName); st.setString(7,business.bankType); st.setString(8,business.bankAccount); st.setString(9,business.bankIdentity); st.setString(10,business.bankHolder); st.executeUpdate()
        }
        return fairId
    }

    private fun paymentDestinationDto(result: ResultSet): PaymentDestinationDto? {
        val fairId = result.getLong("payment_fair_id")
        if (result.wasNull()) return null
        val paymentMode = result.getString("payment_mode").orEmpty().ifBlank { "MOBILE_PAYMENT" }
        val mobileBank = result.getString("mobile_bank")
        val bankName = result.getString("bank_name")
        val businessId = result.getLong("business_id").let { if (result.wasNull()) null else it }
        return PaymentDestinationDto(
            fairId = fairId,
            fairName = result.getString("payment_fair_name").orEmpty(),
            paymentMode = paymentMode,
            business = businessId?.let {
                AssociatedBusinessSummaryDto(
                    id = it,
                    commercialName = result.getString("business_commercial_name").orEmpty(),
                    legalName = result.getString("business_legal_name").orEmpty(),
                    rif = result.getString("business_rif").orEmpty(),
                    logoUrl = publicUrl(result.getString("business_logo_path"))
                )
            },
            mobilePayment = mobileBank?.let {
                MobilePaymentDto(
                    bank = it,
                    phone = result.getString("mobile_phone").orEmpty(),
                    identityNumber = result.getString("mobile_identity_number").orEmpty(),
                    holderName = result.getString("mobile_holder_name").orEmpty()
                )
            },
            bankTransfer = bankName?.let {
                BankTransferDto(
                    bank = it,
                    accountType = result.getString("bank_account_type").orEmpty(),
                    accountNumber = result.getString("bank_account_number").orEmpty(),
                    identityNumber = result.getString("bank_identity_number").orEmpty(),
                    holderName = result.getString("bank_holder_name").orEmpty()
                )
            }
        )
    }

    private fun paymentDestinationDto(connection: Connection, fairId: Long): PaymentDestinationDto? =
        connection.prepareStatement(
            """SELECT f.id AS payment_fair_id,f.name AS payment_fair_name,f.payment_mode AS payment_mode,
                      f.business_id,b.commercial_name AS business_commercial_name,b.legal_name AS business_legal_name,
                      b.rif AS business_rif,b.logo_path AS business_logo_path,
                      pd.mobile_bank,pd.mobile_phone,pd.mobile_identity_number,pd.mobile_holder_name,
                      pd.bank_name,pd.bank_account_type,pd.bank_account_number,pd.bank_identity_number,pd.bank_holder_name
               FROM jornadas f
               LEFT JOIN negocios_asociados b ON b.id=f.business_id
               LEFT JOIN detalles_pago_jornada pd ON pd.fair_id=f.id WHERE f.id=?"""
        ).use { statement ->
            statement.setLong(1, fairId)
            statement.executeQuery().use { result -> if (result.next()) paymentDestinationDto(result) else null }
        }

    private fun parsePromotionDate(value: String?, fieldLabel: String): OffsetDateTime? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { OffsetDateTime.parse(raw) }.getOrElse {
            throw AppException("La fecha de $fieldLabel de la promoción no tiene un formato válido.")
        }
    }

    private fun ensurePromotionTargetExists(connection: Connection, targetType: String, targetId: Long) {
        val table = when (targetType) { "PRODUCT" -> "productos"; "COMBO" -> "combos"; else -> "jornadas" }
        val exists = connection.prepareStatement("SELECT 1 FROM $table WHERE id=?").use { st ->
            st.setLong(1,targetId); st.executeQuery().use { it.next() }
        }
        if (!exists) throw AppException("El elemento seleccionado para la promoción ya no existe.")
    }

    private fun promotionTargetName(connection: Connection, type: String, id: Long): String {
        val table = when (type) { "PRODUCT" -> "productos"; "COMBO" -> "combos"; else -> "jornadas" }
        return connection.prepareStatement("SELECT name FROM $table WHERE id=?").use { st ->
            st.setLong(1,id); st.executeQuery().use { r -> if (r.next()) r.getString(1).orEmpty() else "Elemento no disponible" }
        }
    }

    private fun promotionDto(connection: Connection, promotionId: Long): PromotionDto =
        connection.prepareStatement(
            """SELECT p.id,p.name,p.target_type,p.target_id,p.discount_type,p.discount_value,p.starts_at,p.ends_at,p.max_uses,p.active,p.created_at,p.updated_at,
                      (SELECT COUNT(*) FROM pedidos o WHERE o.promotion_id=p.id) uses
               FROM promociones_descuentos p WHERE p.id=?""".trimIndent()
        ).use { st ->
            st.setLong(1,promotionId); st.executeQuery().use { r ->
                if (!r.next()) throw NotFoundException("La promoción no existe.")
                val starts=r.getObject(7,OffsetDateTime::class.java); val ends=r.getObject(8,OffsetDateTime::class.java)
                val maxUses=r.getInt(9).let { if(r.wasNull()) null else it }; val uses=r.getInt(13); val active=r.getBoolean(10)
                val now=OffsetDateTime.now(ZoneOffset.UTC)
                val status=when {
                    !active -> "INACTIVE"
                    starts!=null && starts.isAfter(now) -> "SCHEDULED"
                    ends!=null && ends.isBefore(now) -> "FINISHED"
                    maxUses!=null && uses>=maxUses -> "FINISHED"
                    else -> "ACTIVE"
                }
                PromotionDto(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),promotionTargetName(connection,r.getString(3),r.getLong(4)),r.getString(5),r.getBigDecimal(6).toDouble(),starts?.toString(),ends?.toString(),maxUses,uses,active,status,r.getObject(11,OffsetDateTime::class.java)?.toString().orEmpty(),r.getObject(12,OffsetDateTime::class.java)?.toString().orEmpty())
            }
        }

    private fun activePromotions(connection: Connection): List<PromotionDto> =
        connection.prepareStatement(
            """SELECT p.id FROM promociones_descuentos p
               WHERE p.active=TRUE AND (p.starts_at IS NULL OR p.starts_at<=NOW()) AND (p.ends_at IS NULL OR p.ends_at>=NOW())
                 AND (p.max_uses IS NULL OR (SELECT COUNT(*) FROM pedidos o WHERE o.promotion_id=p.id) < p.max_uses)
               ORDER BY p.id""".trimIndent()
        ).use { st -> st.executeQuery().use { r -> buildList { while(r.next()) add(promotionDto(connection,r.getLong(1))) } } }

    private fun promotionDiscountBs(promotion: PromotionDto, eligibleSubtotal: BigDecimal, bcvRate: BigDecimal): BigDecimal {
        if (eligibleSubtotal.signum() <= 0) return BigDecimal.ZERO.setScale(MoneyMath.VES_SCALE)
        val raw = when (promotion.discountType) {
            "PERCENT" -> eligibleSubtotal.multiply(BigDecimal.valueOf(promotion.discountValue)).divide(BigDecimal("100"), MoneyMath.VES_SCALE, RoundingMode.HALF_EVEN)
            "FIXED_USD" -> BigDecimal.valueOf(promotion.discountValue).multiply(bcvRate).setScale(MoneyMath.VES_SCALE,RoundingMode.HALF_EVEN)
            else -> BigDecimal.ZERO
        }
        return raw.min(eligibleSubtotal).max(BigDecimal.ZERO).setScale(MoneyMath.VES_SCALE,RoundingMode.HALF_EVEN)
    }

    private fun bestPromotionForPurchase(
        connection: Connection,
        fairId: Long,
        directTotalsByProduct: Map<Long, BigDecimal>,
        comboTotalsByCombo: Map<Long, BigDecimal>,
        total: BigDecimal,
        bcvRate: BigDecimal
    ): PromotionApplication? {
        return activePromotions(connection).mapNotNull { promo ->
            val eligible = when(promo.targetType) {
                "FAIR" -> if(promo.targetId==fairId) total else BigDecimal.ZERO
                "PRODUCT" -> directTotalsByProduct[promo.targetId] ?: BigDecimal.ZERO
                "COMBO" -> comboTotalsByCombo[promo.targetId] ?: BigDecimal.ZERO
                else -> BigDecimal.ZERO
            }
            val amount=promotionDiscountBs(promo,eligible,bcvRate)
            if(amount.signum()<=0) null else PromotionApplication(promo.id,promo.name,promo.discountType,BigDecimal.valueOf(promo.discountValue),promo.targetType,promo.targetId,amount)
        }.maxByOrNull { it.discountAmount }
    }

    private fun parseBannerDate(value: String?, fieldLabel: String): OffsetDateTime? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { OffsetDateTime.parse(raw) }.getOrElse {
            throw AppException("La fecha de $fieldLabel del banner no tiene un formato válido.")
        }
    }

    private fun bannerDto(connection: Connection, bannerId: Long): BannerDto =
        connection.prepareStatement(
            """SELECT b.id,b.title,b.image_path,b.fair_id,j.name,b.active,b.sort_order,b.starts_at,b.ends_at
               FROM banners_inicio b
               LEFT JOIN jornadas j ON j.id=b.fair_id
               WHERE b.id=?""".trimIndent()
        ).use { statement ->
            statement.setLong(1, bannerId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("El banner no existe.")
                val fairId = result.getLong(4).let { if (result.wasNull()) null else it }
                BannerDto(
                    id = result.getLong(1),
                    title = result.getString(2).orEmpty(),
                    imageUrl = publicUrl(result.getString(3)),
                    fairId = fairId,
                    fairName = result.getString(5),
                    active = result.getBoolean(6),
                    sortOrder = result.getInt(7),
                    startsAt = result.getObject(8, OffsetDateTime::class.java)?.toString(),
                    endsAt = result.getObject(9, OffsetDateTime::class.java)?.toString()
                )
            }
        }

    private fun fairDto(connection: Connection, fairId: Long): FairDto {
        val base = connection.prepareStatement(
            """
            SELECT f.id,f.name,f.place,f.schedule_text,f.description,f.published,f.finalized,f.payment_mode,
                   f.business_id,b.commercial_name,b.legal_name,b.rif,b.logo_path,
                   pd.mobile_bank,pd.mobile_phone,pd.mobile_identity_number,pd.mobile_holder_name,
                   pd.bank_name,pd.bank_account_type,pd.bank_account_number,pd.bank_identity_number,pd.bank_holder_name,
                   f.cover_path
            FROM jornadas f
            LEFT JOIN negocios_asociados b ON b.id=f.business_id
            LEFT JOIN detalles_pago_jornada pd ON pd.fair_id=f.id WHERE f.id=?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, fairId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("La jornada no existe.")
                val businessId = result.getLong(9).let { if (result.wasNull()) null else it }
                FairBase(
                    result.getLong(1), result.getString(2), result.getString(3), result.getString(4), result.getString(5), result.getBoolean(6), result.getBoolean(7), result.getString(8),
                    businessId, result.getString(10), result.getString(11), result.getString(12), result.getString(13),
                    result.getString(14), result.getString(15), result.getString(16), result.getString(17), result.getString(18), result.getString(19), result.getString(20), result.getString(21), result.getString(22)
                )
            }
        }
        val catalogPromotions = activePromotions(connection)
        val offers = connection.prepareStatement(
            """SELECT fp.product_id,p.base_price,fp.image_path,p.bcv_rate,COALESCE(fp.quantity,1)
               FROM productos_jornada fp
               JOIN productos p ON p.id=fp.product_id AND p.active=TRUE
               WHERE fp.fair_id=? ORDER BY fp.product_id"""
        ).use { statement ->
            statement.setLong(1, fairId); statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val productId=result.getLong(1)
                    val original=result.getBigDecimal(2).setScale(MoneyMath.VES_SCALE,RoundingMode.HALF_EVEN)
                    val rate=(result.getBigDecimal(4) ?: BigDecimal.ZERO).takeIf { it.signum()>0 } ?: BigDecimal.ONE
                    val applicable=catalogPromotions.filter { (it.targetType=="PRODUCT" && it.targetId==productId) || (it.targetType=="FAIR" && it.targetId==fairId) }
                    val best=applicable.map { it to promotionDiscountBs(it,original,rate) }.filter { it.second.signum()>0 }.maxByOrNull { it.second }
                    val discount=best?.second ?: BigDecimal.ZERO
                    val finalPrice=original.subtract(discount).max(BigDecimal.ZERO)
                    val label=best?.first?.let { promo -> if(promo.discountType=="PERCENT") "-${String.format(Locale.US,"%.0f",promo.discountValue)}%" else "US$ ${String.format(Locale.US,"%.2f",promo.discountValue)} OFF" }
                    add(FairProductOfferDto(productId, finalPrice.toDouble(), publicUrl(result.getString(3)), original.toDouble(), discount.toDouble(), best?.first?.name, label, result.getInt(5)))
                }
            } }
        }
        val mobile = base.mobileBank?.let { MobilePaymentDto(it, base.mobilePhone.orEmpty(), base.mobileIdentity.orEmpty(), base.mobileHolder.orEmpty()) }
        val bank = base.bankName?.let { BankTransferDto(it, base.bankType.orEmpty(), base.bankAccount.orEmpty(), base.bankIdentity.orEmpty(), base.bankHolder.orEmpty()) }
        val business = base.businessId?.let {
            AssociatedBusinessSummaryDto(it, base.businessCommercialName.orEmpty(), base.businessLegalName.orEmpty(), base.businessRif.orEmpty(), publicUrl(base.businessLogoPath))
        }
        val coverPath = connection.prepareStatement("SELECT cover_path FROM jornadas WHERE id=?").use { statement ->
            statement.setLong(1, fairId)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        return FairDto(
            id = base.id,
            name = base.name,
            place = base.place,
            schedule = base.schedule,
            description = base.description,
            published = base.published,
            finalized = base.finalized,
            paymentMode = base.paymentMode,
            business = business,
            mobilePayment = mobile,
            bankTransfer = bank,
            coverUrl = publicUrl(coverPath),
            productOffers = offers
        )
    }

    private fun associatedBusinessDto(result: ResultSet): AssociatedBusinessDto {
        val mobileBank = result.getString("mobile_bank")
        val bankName = result.getString("bank_name")
        return AssociatedBusinessDto(
            id = result.getLong("id"),
            commercialName = result.getString("commercial_name"),
            legalName = result.getString("legal_name"),
            rif = result.getString("rif"),
            logoUrl = publicUrl(result.getString("logo_path")),
            phone = result.getString("phone"),
            email = result.getString("email"),
            address = result.getString("address"),
            active = result.getBoolean("active"),
            paymentMode = result.getString("payment_mode"),
            mobilePayment = mobileBank?.let {
                MobilePaymentDto(it, result.getString("mobile_phone").orEmpty(), result.getString("mobile_identity_number").orEmpty(), result.getString("mobile_holder_name").orEmpty())
            },
            bankTransfer = bankName?.let {
                BankTransferDto(it, result.getString("bank_account_type").orEmpty(), result.getString("bank_account_number").orEmpty(), result.getString("bank_identity_number").orEmpty(), result.getString("bank_holder_name").orEmpty())
            },
            createdAt = result.getObject("created_at", OffsetDateTime::class.java).toString(),
            updatedAt = result.getObject("updated_at", OffsetDateTime::class.java).toString()
        )
    }

    private fun findAssociatedBusiness(connection: Connection, businessId: Long): AssociatedBusinessDto =
        connection.prepareStatement("SELECT * FROM negocios_asociados WHERE id=?").use { statement ->
            statement.setLong(1, businessId)
            statement.executeQuery().use { result -> if (result.next()) associatedBusinessDto(result) else throw NotFoundException("El negocio asociado no existe.") }
        }

    private fun resolveFairBusiness(
        connection: Connection,
        request: SaveFairRequest,
        allowInactive: Boolean = false
    ): SaveFairRequest {
        val businessId = request.businessId ?: return request
        val business = connection.prepareStatement("SELECT * FROM negocios_asociados WHERE id=? FOR SHARE").use { statement ->
            statement.setLong(1, businessId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw AppException("El negocio asociado seleccionado no existe.")
                val business = associatedBusinessDto(result)
                if (!business.active && !allowInactive) throw AppException("Selecciona un negocio asociado activo.")
                business
            }
        }
        return request.copy(
            paymentMode = business.paymentMode,
            mobilePayment = business.mobilePayment,
            bankTransfer = business.bankTransfer
        )
    }

    private fun validateAssociatedBusinessRequest(request: SaveAssociatedBusinessRequest) {
        if (request.commercialName.trim().length !in 2..180) throw AppException("Ingresa el nombre comercial del negocio.")
        if (request.legalName.trim().length !in 2..220) throw AppException("Ingresa la razón social del negocio.")
        val rif = normalizeBusinessRif(request.rif)
        if (!rif.matches(Regex("^[JGVEP]-?[0-9]{8,9}-?[0-9]$"))) throw AppException("El RIF debe tener formato venezolano, por ejemplo J-12345678-9.")
        request.phone?.takeIf(String::isNotBlank)?.let { phone ->
            if (phone.filter(Char::isDigit).length !in 10..12) throw AppException("El teléfono del negocio no es válido.")
        }
        request.email?.takeIf(String::isNotBlank)?.let { email ->
            if (!email.matches(Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))) throw AppException("El correo del negocio no es válido.")
        }
        if (request.paymentMode !in setOf("MOBILE_PAYMENT", "BANK_TRANSFER", "BOTH")) throw AppException("Método de pago inválido.")
        if (request.paymentMode in setOf("MOBILE_PAYMENT", "BOTH")) {
            val payment = request.mobilePayment ?: throw AppException("Completa los datos de pago móvil del negocio.")
            if (payment.bank.isBlank() || !isValidVenezuelanMobile(payment.phone) || !isValidVenezuelanIdentity(payment.identityNumber) || !isValidHolderName(payment.holderName)) {
                throw AppException("Revisa los datos de pago móvil del negocio.")
            }
        }
        if (request.paymentMode in setOf("BANK_TRANSFER", "BOTH")) {
            val payment = request.bankTransfer ?: throw AppException("Completa la cuenta bancaria del negocio.")
            if (payment.bank.isBlank() || payment.accountType.isBlank() || payment.accountNumber.isBlank() || !isValidVenezuelanIdentity(payment.identityNumber) || !isValidHolderName(payment.holderName)) {
                throw AppException("Revisa los datos bancarios del negocio.")
            }
            if (!payment.accountNumber.all(Char::isDigit) || payment.accountNumber.length !in 10..30) throw AppException("El número de cuenta debe contener entre 10 y 30 dígitos.")
        }
    }

    private fun normalizeBusinessRif(value: String): String {
        val clean = value.trim().uppercase().replace(" ", "")
        val compact = clean.replace("-", "")
        if (compact.length < 10) return clean
        return "${compact.first()}-${compact.substring(1, compact.length - 1)}-${compact.last()}"
    }

    private fun bindAssociatedBusiness(
        statement: java.sql.PreparedStatement,
        request: SaveAssociatedBusinessRequest,
        rif: String,
        accountantId: Long?
    ) {
        statement.setString(1, request.commercialName.trim())
        statement.setString(2, request.legalName.trim())
        statement.setString(3, rif)
        statement.setString(4, request.phone?.trim()?.takeIf(String::isNotBlank))
        statement.setString(5, request.email?.trim()?.lowercase()?.takeIf(String::isNotBlank))
        statement.setString(6, request.address?.trim()?.takeIf(String::isNotBlank))
        statement.setString(7, request.paymentMode)
        statement.setString(8, request.mobilePayment?.bank?.trim())
        statement.setString(9, request.mobilePayment?.phone?.trim())
        statement.setString(10, request.mobilePayment?.identityNumber?.trim())
        statement.setString(11, request.mobilePayment?.holderName?.trim())
        statement.setString(12, request.bankTransfer?.bank?.trim())
        statement.setString(13, request.bankTransfer?.accountType?.trim())
        statement.setString(14, request.bankTransfer?.accountNumber?.trim())
        statement.setString(15, request.bankTransfer?.identityNumber?.trim())
        statement.setString(16, request.bankTransfer?.holderName?.trim())
        accountantId?.let { statement.setLong(17, it) }
    }

    private fun validateFairRequest(request: SaveFairRequest) {
        if (request.name.isBlank() || request.place.isBlank() || request.schedule.isBlank()) throw AppException("Completa nombre, lugar y horario de la jornada.")
        if (request.paymentMode !in setOf("MOBILE_PAYMENT", "BANK_TRANSFER", "BOTH")) throw AppException("Método de pago inválido.")
        if (request.productOffers.map { it.productId }.distinct().size != request.productOffers.size) {
            throw AppException("No repitas el mismo producto dentro de la jornada.")
        }
        if (request.published && request.productOffers.isEmpty()) throw AppException("Agrega al menos un producto antes de publicar.")
        if (request.published && request.businessId == null) throw AppException("Selecciona un negocio asociado antes de publicar la jornada.")
        if (request.published && request.paymentMode in setOf("MOBILE_PAYMENT", "BOTH")) {
            val p = request.mobilePayment ?: throw AppException("Completa los datos de pago móvil.")
            if (p.bank.isBlank() || p.phone.isBlank() || p.identityNumber.isBlank() || p.holderName.isBlank()) throw AppException("Completa todos los datos de pago móvil.")
            if (!isValidVenezuelanMobile(p.phone)) throw AppException("El celular de pago móvil debe tener 12 dígitos en formato +58 y un prefijo móvil válido.")
            if (!isValidVenezuelanIdentity(p.identityNumber)) throw AppException("La cédula o RIF del titular no es válido.")
            if (!isValidHolderName(p.holderName)) throw AppException("Coloca el nombre y apellido completos del titular, sin apodos.")
        }
        if (request.published && request.paymentMode in setOf("BANK_TRANSFER", "BOTH")) {
            val p = request.bankTransfer ?: throw AppException("Completa los datos bancarios.")
            if (p.bank.isBlank() || p.accountType.isBlank() || p.accountNumber.isBlank() || p.identityNumber.isBlank() || p.holderName.isBlank()) throw AppException("Completa todos los datos bancarios.")
            if (!p.accountNumber.all(Char::isDigit)) throw AppException("La cuenta debe contener solo números.")
            if (!isValidVenezuelanIdentity(p.identityNumber)) throw AppException("La cédula o RIF del titular no es válido.")
            if (!isValidHolderName(p.holderName)) throw AppException("Coloca el nombre y apellido completos del titular, sin apodos.")
        }
    }

    private fun isValidVenezuelanIdentity(value: String): Boolean {
        val normalized = value.trim().uppercase().replace(" ", "")
        val digits = normalized.filter(Char::isDigit)
        if (normalized.all { it.isDigit() } && digits.length in 6..10) return true
        return normalizeBusinessRif(normalized).matches(Regex("^[JGVEP]-?[0-9]{8,9}-?[0-9]$"))
    }

    private fun isValidVenezuelanMobile(value: String): Boolean {
        val digits = value.filter(Char::isDigit)
        if (digits.length != 12 || !digits.startsWith("58")) return false
        val local = digits.drop(2)
        return local.take(3) in setOf("412", "414", "416", "424", "426")
    }

    private fun isValidHolderName(value: String): Boolean {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        if (!normalized.matches(Regex("^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ ]{5,100}$"))) return false
        val parts = normalized.split(" ").filter { it.length >= 2 }
        if (parts.size < 2) return false
        val nicknames = setOf("papi","mami","nene","nena","gordo","gorda","flaco","flaca","chino","china","negro","negra","baby","bebe","bro","amigo","amiga")
        return parts.none { it.lowercase() in nicknames }
    }

    private fun validateFoodProduct(name: String, category: String) {
        InventoryProductValidator.validate(name, category)
    }

    private fun bindFair(statement: java.sql.PreparedStatement, request: SaveFairRequest, adminId: Long) {
        statement.setString(1, request.name.trim()); statement.setString(2, request.place.trim()); statement.setString(3, request.schedule.trim()); statement.setString(4, request.description.trim())
        statement.setBoolean(5, request.published); statement.setString(6, request.paymentMode)
        if (request.businessId == null) statement.setNull(7, java.sql.Types.BIGINT) else statement.setLong(7, request.businessId)
        statement.setLong(8, adminId); statement.setBoolean(9, request.published)
    }

    private fun upsertPaymentDetails(connection: Connection, fairId: Long, request: SaveFairRequest) {
        connection.prepareStatement(
            """
            INSERT INTO detalles_pago_jornada(fair_id,mobile_bank,mobile_phone,mobile_identity_number,mobile_holder_name,bank_name,bank_account_type,bank_account_number,bank_identity_number,bank_holder_name)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(fair_id) DO UPDATE SET mobile_bank=EXCLUDED.mobile_bank,mobile_phone=EXCLUDED.mobile_phone,mobile_identity_number=EXCLUDED.mobile_identity_number,
              mobile_holder_name=EXCLUDED.mobile_holder_name,bank_name=EXCLUDED.bank_name,bank_account_type=EXCLUDED.bank_account_type,bank_account_number=EXCLUDED.bank_account_number,
              bank_identity_number=EXCLUDED.bank_identity_number,bank_holder_name=EXCLUDED.bank_holder_name,updated_at=NOW()
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, fairId); statement.setString(2, request.mobilePayment?.bank); statement.setString(3, request.mobilePayment?.phone); statement.setString(4, request.mobilePayment?.identityNumber); statement.setString(5, request.mobilePayment?.holderName)
            statement.setString(6, request.bankTransfer?.bank); statement.setString(7, request.bankTransfer?.accountType); statement.setString(8, request.bankTransfer?.accountNumber); statement.setString(9, request.bankTransfer?.identityNumber); statement.setString(10, request.bankTransfer?.holderName); statement.executeUpdate()
        }
    }

    private fun comboIndividualTotalUsd(connection: Connection, lines: List<ComboLineDto>): BigDecimal {
        if (lines.isEmpty()) return BigDecimal.ZERO.setScale(MoneyMath.USD_SCALE)
        val ids = lines.map { it.productId }.distinct()
        val prices = connection.prepareStatement(
            "SELECT id,COALESCE(base_price_usd,0) FROM productos WHERE active=TRUE AND id = ANY(?)"
        ).use { statement ->
            statement.setArray(1, connection.createArrayOf("BIGINT", ids.toTypedArray()))
            statement.executeQuery().use { result -> buildMap<Long, BigDecimal> {
                while (result.next()) put(result.getLong(1), result.getBigDecimal(2) ?: BigDecimal.ZERO)
            } }
        }
        return MoneyMath.sum(
            lines.map { line ->
                (prices[line.productId] ?: BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(line.quantity.toLong()), MoneyMath.CONTEXT)
            },
            MoneyMath.USD_SCALE
        )
    }

    private fun comboIndividualTotals(connection: Connection, comboId: Long): Pair<BigDecimal, BigDecimal> =
        connection.prepareStatement(
            """SELECT COALESCE(SUM(pc.quantity * p.base_price_usd),0),COALESCE(SUM(pc.quantity * p.base_price),0)
               FROM productos_combo pc JOIN productos p ON p.id=pc.product_id
               WHERE pc.combo_id=?"""
        ).use { statement ->
            statement.setLong(1, comboId)
            statement.executeQuery().use { result ->
                result.next()
                (result.getBigDecimal(1) ?: BigDecimal.ZERO).setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN) to
                    (result.getBigDecimal(2) ?: BigDecimal.ZERO).setScale(MoneyMath.VES_SCALE, RoundingMode.HALF_EVEN)
            }
        }

    private fun comboDto(connection: Connection, comboId: Long): ComboDto =
        connection.prepareStatement(
            "SELECT id,name,description,active,cover_path,combo_price_usd,combo_price_bs,bcv_rate FROM combos WHERE id=?"
        ).use { statement ->
            statement.setLong(1, comboId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw NotFoundException("El combo no existe.")
                val totals = comboIndividualTotals(connection, comboId)
                val storedUsd = result.getBigDecimal(6) ?: BigDecimal.ZERO
                val storedBs = result.getBigDecimal(7) ?: BigDecimal.ZERO
                val priceUsd = if (storedUsd.signum() > 0) storedUsd else totals.first
                val priceBs = if (storedBs.signum() > 0) storedBs else totals.second
                val savingsUsd = totals.first.subtract(priceUsd).max(BigDecimal.ZERO).setScale(MoneyMath.USD_SCALE, RoundingMode.HALF_EVEN)
                val savingsBs = totals.second.subtract(priceBs).max(BigDecimal.ZERO).setScale(MoneyMath.VES_SCALE, RoundingMode.HALF_EVEN)
                ComboDto(
                    id = result.getLong(1),
                    name = result.getString(2),
                    description = result.getString(3),
                    lines = comboLines(connection, comboId),
                    active = result.getBoolean(4),
                    coverUrl = publicUrl(result.getString(5)),
                    priceUsd = priceUsd.toDouble(),
                    priceBs = priceBs.toDouble(),
                    bcvRate = (result.getBigDecimal(8) ?: BigDecimal.ZERO).toDouble(),
                    individualTotalUsd = totals.first.toDouble(),
                    individualTotalBs = totals.second.toDouble(),
                    savingsUsd = savingsUsd.toDouble(),
                    savingsBs = savingsBs.toDouble()
                )
            }
        }

    private fun comboLines(connection: Connection, comboId: Long): List<ComboLineDto> = connection.prepareStatement("SELECT product_id,quantity,extra FROM productos_combo WHERE combo_id=? ORDER BY product_id").use { statement ->
        statement.setLong(1, comboId); statement.executeQuery().use { result -> buildList { while (result.next()) add(ComboLineDto(result.getLong(1), result.getInt(2), result.getBoolean(3))) } }
    }

    private fun requestQuantities(connection: Connection, requestId: Long): Map<Long, Int> = connection.prepareStatement("SELECT combo_id,quantity FROM items_solicitud_comunidad WHERE request_id=?").use { statement ->
        statement.setLong(1, requestId); statement.executeQuery().use { result -> buildMap { while (result.next()) put(result.getLong(1), result.getInt(2)) } }
    }

    private fun paymentInstructions(connection: Connection, orderId: Long): String {
        val financingType = connection.prepareStatement("SELECT financing_type FROM pedidos WHERE id=?").use { statement ->
            statement.setLong(1, orderId)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else "DIRECT_PAYMENT" }
        }
        if (financingType == "CREDIMPULSO") {
            val installments = connection.prepareStatement(
                """SELECT ci.installment_number,ci.amount_usd,ci.due_date,ci.status
                   FROM cuotas_credito ci JOIN prestamos_credito cl ON cl.id=ci.loan_id
                   WHERE cl.order_id=? ORDER BY ci.installment_number"""
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) {
                        add("Cuota ${result.getInt(1)}: US$ ${"%.2f".format(result.getBigDecimal(2).toDouble())} · ${result.getObject(3, LocalDate::class.java)} · ${result.getString(4)}")
                    }
                } }
            }
            return buildString {
                append("Crédito Kredi+ · cuotas flexibles")
                if (installments.isNotEmpty()) append("\n").append(installments.joinToString("\n"))
            }
        }
        return connection.prepareStatement(
            """
            SELECT p.method,pd.mobile_bank,pd.mobile_phone,pd.mobile_identity_number,pd.mobile_holder_name,
                   pd.bank_name,pd.bank_account_type,pd.bank_account_number,pd.bank_identity_number,pd.bank_holder_name
            FROM pedidos o JOIN jornadas f ON f.id=o.fair_id LEFT JOIN detalles_pago_jornada pd ON pd.fair_id=f.id
            LEFT JOIN LATERAL (SELECT method FROM pagos WHERE order_id=o.id ORDER BY created_at DESC LIMIT 1) p ON TRUE
            WHERE o.id=?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, orderId); statement.executeQuery().use { result ->
                if (!result.next()) return ""
                if (result.getString(1) == "MOBILE_PAYMENT") "Banco: ${result.getString(2).orEmpty()}\nTeléfono: ${result.getString(3).orEmpty()}\nCédula/RIF: ${result.getString(4).orEmpty()}\nTitular: ${result.getString(5).orEmpty()}"
                else "Banco: ${result.getString(6).orEmpty()}\nTipo: ${result.getString(7).orEmpty()}\nCuenta: ${result.getString(8).orEmpty()}\nCédula/RIF: ${result.getString(9).orEmpty()}\nTitular: ${result.getString(10).orEmpty()}"
            }
        }
    }

    private fun upsertDeviceToken(connection: Connection, userId: Long, request: DeviceTokenRequest) {
        val token = request.token.trim()
        if (token.length < 20) throw AppException("El token de notificaciones no es válido.")
        connection.prepareStatement(
            """
            INSERT INTO tokens_dispositivo(token,user_id,platform,device_name,token_kind,last_error,failure_count) VALUES (?,?,?,?,?,NULL,0)
            ON CONFLICT(token) DO UPDATE SET user_id=EXCLUDED.user_id,platform=EXCLUDED.platform,device_name=EXCLUDED.device_name,token_kind=EXCLUDED.token_kind,last_error=NULL,failure_count=0,updated_at=NOW()
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, token)
            statement.setLong(2, userId)
            statement.setString(3, request.platform.trim().ifBlank { "ANDROID" }.take(30))
            statement.setString(4, request.deviceName?.trim()?.takeIf { it.isNotEmpty() }?.take(255))
            statement.setString(5, request.tokenKind.trim().ifBlank { "FCM_REGISTRATION_TOKEN" }.take(50))
            statement.executeUpdate()
        }
    }

    private fun notifyVerificationResult(userId: Long, approved: Boolean, reason: String?) {
        val title = if (approved) "Registro aprobado" else "Solicitud no aprobada"
        val body = if (approved) {
            "Tu registro fue aprobado. Ya puedes iniciar sesión y acceder a tu cupo Crédito Kredi+ según tu nivel actual."
        } else {
            reason?.trim()?.takeIf { it.isNotEmpty() } ?: "Tu solicitud requiere una nueva revisión."
        }
        notifyUsers(listOf(userId), title, body, if (approved) "REGISTRATION_APPROVED" else "REGISTRATION_REJECTED")
    }

    private fun notifyRegistrationAccountants(
        title: String,
        body: String,
        type: String,
        data: Map<String, String> = emptyMap()
    ) {
        runCatching {
            database.dataSource.connection.use { connection ->
                // 7.2.6: toda alta de Beneficiario queda visible para revisión administrativa y para el Contador.
                connection.prepareStatement(
                    "SELECT id FROM usuarios WHERE UPPER(role) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS') AND account_status='ACTIVE'"
                ).use { statement ->
                    statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
                }
            }
        }.onSuccess { ids -> notifyUsers(ids, title, body, type, data) }
            .onFailure { error -> logger.error("No fue posible obtener Contadores destinatarios para la notificación de registro '{}'.", title, error) }
    }

    private fun notifyAdmins(title: String, body: String, type: String, data: Map<String, String> = emptyMap()) {
        runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id FROM usuarios WHERE UPPER(role) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA') AND account_status='ACTIVE'"
                ).use { statement ->
                    statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
                }
            }
        }.onSuccess { ids -> notifyUsers(ids, title, body, type, data) }
            .onFailure { error -> logger.error("No fue posible obtener los destinatarios administrativos de la notificación '{}'.", title, error) }
    }

    private fun notifyBeneficiaries(title: String, body: String, type: String, data: Map<String, String> = emptyMap()) {
        runCatching {
            database.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id FROM usuarios WHERE UPPER(role) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS','WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA') AND account_status='ACTIVE'"
                ).use { statement ->
                    statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getLong(1)) } }
                }
            }
        }.onSuccess { ids -> notifyUsers(ids, title, body, type, data) }
            .onFailure { error -> logger.error("No fue posible obtener los usuarios destinatarios de la notificación '{}'.", title, error) }
    }

    private fun notifyUsers(
        userIds: Collection<Long>,
        title: String,
        body: String,
        type: String,
        data: Map<String, String> = emptyMap()
    ) {
        val ids = userIds.distinct().filter { it > 0 }
        if (ids.isEmpty()) return

        val targets = runCatching {
            database.transaction { connection ->
                val payloadJson = notificationPayloadJson(data)
                buildList {
                    ids.forEach { userId ->
                        val notificationId = connection.prepareStatement(
                            """INSERT INTO notificaciones(user_id,title,body,type,payload)
                               VALUES (?,?,?,?,?::jsonb)
                               RETURNING id"""
                        ).use { statement ->
                            statement.setLong(1, userId)
                            statement.setString(2, title.take(220))
                            statement.setString(3, body)
                            statement.setString(4, type.take(80))
                            statement.setString(5, payloadJson)
                            statement.executeQuery().use { result ->
                                if (!result.next()) throw IllegalStateException("No se creó la notificación interna.")
                                result.getLong(1)
                            }
                        }

                        val userTokens = connection.prepareStatement(
                            """SELECT token
                               FROM tokens_dispositivo
                               WHERE user_id=? AND token_kind='FCM_REGISTRATION_TOKEN'
                               ORDER BY updated_at DESC"""
                        ).use { statement ->
                            statement.setLong(1, userId)
                            statement.executeQuery().use { result ->
                                buildList {
                                    while (result.next()) {
                                        result.getString(1)?.trim()?.takeIf(String::isNotBlank)?.let(::add)
                                    }
                                }
                            }
                        }

                        userTokens.forEach { token ->
                            connection.prepareStatement(
                                """INSERT INTO entregas_notificaciones(
                                       notification_id,user_id,token,status,created_at,updated_at
                                   ) VALUES (?,?,?,'QUEUED',NOW(),NOW())"""
                            ).use { delivery ->
                                delivery.setLong(1, notificationId)
                                delivery.setLong(2, userId)
                                delivery.setString(3, token)
                                delivery.executeUpdate()
                            }
                            add(NotificationDeliveryTarget(notificationId, userId, token))
                        }
                    }
                }
            }
        }.onFailure { error ->
            logger.error("No fue posible registrar la notificación '{}' de tipo '{}'.", title, type, error)
        }.getOrDefault(emptyList())

        if (targets.isEmpty()) return

        notificationExecutor.execute {
            runCatching {
                val reportsByUser = targets.groupBy { it.userId }.mapValues { (recipientUserId, userTargets) ->
                    pushNotifications.send(
                        userTargets.map { it.token },
                        title,
                        body,
                        data + mapOf(
                            "type" to type,
                            "recipientUserId" to recipientUserId.toString()
                        )
                    )
                }

                database.transaction { connection ->
                    targets.forEach { target ->
                        val report = reportsByUser[target.userId] ?: PushNotificationService.PushSendReport()
                        val status = when (target.token) {
                            in report.sentTokens -> "SENT"
                            in report.invalidTokens -> "INVALID_TOKEN"
                            else -> "FAILED"
                        }
                        val errorMessage = when {
                            status == "SENT" -> null
                            status == "INVALID_TOKEN" -> "Firebase indicó que el token ya no está registrado."
                            report.configurationMissing -> "El servicio de notificaciones no está disponible."
                            else -> "Firebase no confirmó la entrega. Se reintentará con un token actualizado."
                        }
                        connection.prepareStatement(
                            """UPDATE entregas_notificaciones
                               SET status=?,error_message=?,updated_at=NOW()
                               WHERE notification_id=? AND token=?"""
                        ).use { statement ->
                            statement.setString(1, status)
                            statement.setString(2, errorMessage)
                            statement.setLong(3, target.notificationId)
                            statement.setString(4, target.token)
                            statement.executeUpdate()
                        }

                        when (status) {
                            "SENT" -> connection.prepareStatement(
                                """UPDATE tokens_dispositivo
                                   SET last_success_at=NOW(),last_error=NULL,failure_count=0,updated_at=NOW()
                                   WHERE token=?"""
                            ).use { statement ->
                                statement.setString(1, target.token)
                                statement.executeUpdate()
                            }
                            "FAILED" -> connection.prepareStatement(
                                """UPDATE tokens_dispositivo
                                   SET last_error=?,failure_count=COALESCE(failure_count,0)+1,updated_at=NOW()
                                   WHERE token=?"""
                            ).use { statement ->
                                statement.setString(1, errorMessage)
                                statement.setString(2, target.token)
                                statement.executeUpdate()
                            }
                        }
                    }

                    val invalidTokens = reportsByUser.values.flatMap { it.invalidTokens }.distinct()
                    if (invalidTokens.isNotEmpty()) {
                        val placeholders = invalidTokens.joinToString(",") { "?" }
                        connection.prepareStatement("DELETE FROM tokens_dispositivo WHERE token IN ($placeholders)").use { statement ->
                            invalidTokens.forEachIndexed { index, token -> statement.setString(index + 1, token) }
                            statement.executeUpdate()
                        }
                    }
                }
            }.onFailure { error ->
                // El envío push ocurre fuera de la operación principal y nunca puede bloquearla.
                logger.error("No fue posible enviar la notificación '{}' de tipo '{}'.", title, type, error)
            }
        }
    }


    private fun notificationPayloadJson(data: Map<String, String>): String {
        val payload = JsonObject()
        data.forEach { (key, value) -> payload.addProperty(key, value) }
        return payload.toString()
    }

    private fun verifyRecaptcha(token: String?, expectedAction: String) {
        val assessment = try {
            recaptchaService.assess(token, expectedAction)
        } catch (error: Throwable) {
            logger.warn("No fue posible validar reCAPTCHA para la acción '{}': {}", expectedAction, error.message)
            throw AppException("No fue posible completar la verificación de seguridad. Inténtalo nuevamente.")
        }
        if (!assessment.valid) {
            logger.warn(
                "reCAPTCHA rechazado. Acción esperada={}, acción recibida={}, score={}, motivo={}",
                expectedAction,
                assessment.action,
                assessment.score,
                assessment.reason
            )
            throw AppException("La verificación de seguridad no fue válida. Inténtalo nuevamente.")
        }
    }

    private fun createChallenge(connection: Connection, userId: Long, purpose: String, minutes: Long): UUID {
        return connection.prepareStatement("INSERT INTO desafios_autenticacion(user_id,purpose,expires_at) VALUES (?,?,NOW() + (? * INTERVAL '1 minute')) RETURNING token").use { statement ->
            statement.setLong(1, userId); statement.setString(2, purpose); statement.setLong(3, minutes)
            statement.executeQuery().use { result -> result.next(); result.getObject(1, UUID::class.java) }
        }
    }

    private fun verifyChallenge(connection: Connection, tokenText: String, userId: Long, purpose: String, consume: Boolean) {
        val token = runCatching { UUID.fromString(tokenText) }.getOrElse { throw AppException("La autorización temporal no es válida.") }
        val valid = connection.prepareStatement(
            "SELECT 1 FROM desafios_autenticacion WHERE token=? AND user_id=? AND purpose=? AND used_at IS NULL AND expires_at>NOW() AND (purpose<>'LOGIN_PIN' OR attempts<?) FOR UPDATE"
        ).use { statement ->
            statement.setObject(1, token)
            statement.setLong(2, userId)
            statement.setString(3, purpose)
            statement.setInt(4, MAX_PIN_ATTEMPTS)
            statement.executeQuery().use { it.next() }
        }
        if (!valid) throw AppException("La autorización temporal expiró. Inicia el proceso nuevamente.")
        if (consume) connection.prepareStatement("UPDATE desafios_autenticacion SET used_at=NOW() WHERE token=?").use { it.setObject(1, token); it.executeUpdate() }
    }

    private fun recordCredimpulsoTransaction(
        connection: Connection,
        userId: Long,
        type: String,
        amountUsd: Double,
        amountBs: Double,
        bcvRate: Double,
        balanceBeforeUsd: Double,
        balanceAfterUsd: Double,
        description: String,
        loanId: Long? = null,
        installmentId: Long? = null,
        orderId: Long? = null,
        performedBy: Long? = null
    ) {
        connection.prepareStatement(
            """INSERT INTO usuarios_credimpulso(user_id,total_financed_usd,total_paid_usd,last_transaction_at)
               VALUES (?,?,0,NOW())
               ON CONFLICT(user_id) DO UPDATE SET
                 total_financed_usd=usuarios_credimpulso.total_financed_usd + EXCLUDED.total_financed_usd,
                 last_transaction_at=NOW()"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setBigDecimal(2, if (type == "PURCHASE") MoneyMath.usd(amountUsd) else java.math.BigDecimal.ZERO)
            statement.executeUpdate()
        }
        if (type == "INSTALLMENT_PAYMENT") {
            connection.prepareStatement(
                "UPDATE usuarios_credimpulso SET total_paid_usd=total_paid_usd+?,last_transaction_at=NOW() WHERE user_id=?"
            ).use { statement ->
                statement.setBigDecimal(1, MoneyMath.usd(amountUsd))
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """INSERT INTO transacciones_credimpulso(
                user_id,loan_id,installment_id,order_id,transaction_type,amount_usd,amount_bs,bcv_rate,
                balance_before_usd,balance_after_usd,description,performed_by
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { statement ->
            statement.setLong(1, userId)
            if (loanId == null) statement.setNull(2, java.sql.Types.BIGINT) else statement.setLong(2, loanId)
            if (installmentId == null) statement.setNull(3, java.sql.Types.BIGINT) else statement.setLong(3, installmentId)
            if (orderId == null) statement.setNull(4, java.sql.Types.BIGINT) else statement.setLong(4, orderId)
            statement.setString(5, type)
            statement.setBigDecimal(6, MoneyMath.usd(amountUsd))
            statement.setBigDecimal(7, MoneyMath.ves(amountBs))
            statement.setBigDecimal(8, MoneyMath.rate(bcvRate))
            statement.setBigDecimal(9, MoneyMath.usd(balanceBeforeUsd, "Saldo anterior"))
            statement.setBigDecimal(10, MoneyMath.usd(balanceAfterUsd, "Saldo posterior"))
            statement.setString(11, description)
            if (performedBy == null) statement.setNull(12, java.sql.Types.BIGINT) else statement.setLong(12, performedBy)
            statement.executeUpdate()
        }
    }

    /**
     * Garantiza el esquema mínimo requerido por el desbloqueo biométrico aunque una
     * migración histórica anterior haya fallado. No almacena huellas: solo la clave
     * pública y sus hashes. La operación es idempotente y segura para PostgreSQL.
     */
    private fun ensureBiometricCredentialSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS credenciales_biometricas_dispositivo (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
                    device_id_hash CHAR(64) NOT NULL,
                    public_key_hash CHAR(64) NOT NULL,
                    public_key_base64 TEXT NOT NULL,
                    key_algorithm VARCHAR(20) NOT NULL DEFAULT 'EC'
                        CHECK(key_algorithm IN ('EC','RSA')),
                    platform VARCHAR(30) NOT NULL DEFAULT 'ANDROID',
                    device_name VARCHAR(255),
                    app_version VARCHAR(80),
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    last_used_at TIMESTAMPTZ,
                    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE(user_id, device_id_hash)
                )
                """.trimIndent()
            )
            // La clave pública identifica una credencial, pero no debe bloquear una
            // recuperación legítima por un índice global innecesariamente único.
            statement.execute("DROP INDEX IF EXISTS uq_biometric_public_key_hash")
            statement.execute(
                """CREATE INDEX IF NOT EXISTS idx_biometric_public_key_hash
                   ON credenciales_biometricas_dispositivo(public_key_hash)"""
            )
            statement.execute(
                """CREATE INDEX IF NOT EXISTS idx_biometric_user_enabled
                   ON credenciales_biometricas_dispositivo(user_id,enabled,updated_at DESC)"""
            )
        }
    }

    private fun audit(connection: Connection, userId: Long?, action: String, entityType: String?, entityId: String?, description: String?) {
        connection.prepareStatement("INSERT INTO registros_auditoria(user_id,action,entity_type,entity_id,description) VALUES (?,?,?,?,?)").use { statement ->
            if (userId == null) statement.setNull(1, java.sql.Types.BIGINT) else statement.setLong(1, userId)
            statement.setString(2, action); statement.setString(3, entityType); statement.setString(4, entityId); statement.setString(5, description); statement.executeUpdate()
        }
    }

    private fun isSafePersonName(value: String): Boolean = value.isNotBlank() &&
        value.all { it.isLetter() || it.isWhitespace() }

    private fun isSafePlainText(value: String): Boolean = value.all {
        it.isLetterOrDigit() || it.isWhitespace()
    }

    private fun isSafeLocationText(value: String): Boolean = value.all {
        it.isLetterOrDigit() || it.isWhitespace() || it in setOf('-', '\'', '.', '/', '(', ')')
    }

    private fun isSafeAddressText(value: String): Boolean = value.all {
        !it.isISOControl() || it == '\n' || it == '\r' || it == '\t'
    }

    private fun normalizeVenezuelanPhone(value: String): String? {
        var digits = value.filter(Char::isDigit)
        digits = when {
            digits.startsWith("0058") -> digits.drop(4)
            digits.startsWith("58") -> digits.drop(2)
            digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
        if (digits.length != 10 || digits.take(3) !in setOf("412", "414", "416", "424", "426")) return null
        return "+58 ${digits.take(3)}-${digits.drop(3)}"
    }

    private fun parseBirthDate(value: String): LocalDate = runCatching { LocalDate.parse(value.trim()) }
        .getOrElse { throw AppException("La fecha de nacimiento debe tener el formato AAAA-MM-DD.") }

    private fun maskDocument(value: String): String = if (value.length <= 4) value else "••••${value.takeLast(4)}"
    private fun paymentLabel(method: String?): String = when (method) {
        "BANK_TRANSFER" -> "Transferencia bancaria"
        "CREDIMPULSO" -> "Crédito Kredi+ · cuotas flexibles"
        else -> "Pago móvil"
    }

    private data class OverdueInstallmentRow(
        val installmentId: Long,
        val loanId: Long,
        val orderId: Long?,
        val dueDate: LocalDate,
        val invoiceNumber: String
    )

    private data class CreditRequestDecisionRow(
        val userId: Long,
        val amountUsd: Double,
        val requestedInstallments: Int,
        val status: String,
        val approvalBcvRate: Double?,
        val approvedAmountBs: Double?,
        val walletReference: String?,
        val walletTransactionId: String?,
        val sourceWalletAddress: String?,
        val destinationWalletAddress: String?,
        val loanExists: Boolean
    )

    private data class LoanBusinessSnapshot(
        val id: Long,
        val commercialName: String,
        val legalName: String,
        val rif: String,
        val logoPath: String?,
        val active: Boolean,
        val paymentMode: String,
        val mobileBank: String?,
        val mobilePhone: String?,
        val mobileIdentityNumber: String?,
        val mobileHolderName: String?,
        val bankName: String?,
        val bankAccountType: String?,
        val bankAccountNumber: String?,
        val bankIdentityNumber: String?,
        val bankHolderName: String?
    )

    private data class PaymentReportTarget(
        val orderId: Long?,
        val installmentId: Long?,
        val invoiceNumber: String,
        val installmentNumber: Int?,
        val expectedAmountBs: BigDecimal
    )

    private data class VisualProofAnalysis(
        val visualHash: String?,
        val readable: Boolean
    )

    private data class CreditInstallmentPaymentRow(
        val userId: Long,
        val loanId: Long,
        val installmentNumber: Int,
        val status: String,
        val amountUsd: Double,
        val amountBs: Double,
        val bcvRate: Double,
        val dueDate: LocalDate,
        val orderId: Long?,
        val invoiceNumber: String
    )

    private data class CreditInstallmentPaymentNotice(
        val userId: Long,
        val loanId: Long,
        val installmentId: Long,
        val installmentNumber: Int
    )

    private data class PaymentReportDecisionRow(
        val status: String,
        val userId: Long,
        val targetType: String,
        val orderId: Long?,
        val installmentId: Long?,
        val invoiceNumber: String,
        val riskScore: Int,
        val method: String,
        val originBankCode: String,
        val originBankName: String,
        val originPhone: String,
        val referenceNumber: String,
        val amountReportedBs: BigDecimal,
        val expectedAmountBs: BigDecimal,
        val paidFromDifferentPhone: Boolean,
        val proofFilePath: String
    )

    private data class SessionRefreshRow(val userId: Long, val role: String, val accountStatus: String, val verificationStatus: String)
private data class LoginRow(
    val id: Long,
    val email: String,
    val passwordHash: String,
    val verificationStatus: String,
    val accountStatus: String,
    val accountVerified: Boolean,
    val failedLoginAttempts: Int,
    val lockedUntil: OffsetDateTime?,
        val suspensionReason: String?,
        val suspendedAt: OffsetDateTime?
)
    private data class PinRow(val pinHash: String, val role: String, val verificationStatus: String, val accountStatus: String)



    fun creditDisbursementBank(userId: Long): CreditDisbursementBankDto? =
        database.dataSource.connection.use { connection -> creditDisbursementBank(connection, userId) }

    private fun creditDisbursementBank(connection: Connection, userId: Long): CreditDisbursementBankDto? =
        connection.prepareStatement(
            """SELECT bank_code,bank_name,account_type,account_number,holder_name,identity_number
               FROM cuentas_desembolso_credito WHERE user_id=?"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else CreditDisbursementBankDto(
                    bankCode = result.getString(1),
                    bankName = result.getString(2),
                    accountType = result.getString(3),
                    accountNumber = result.getString(4),
                    holderName = result.getString(5),
                    identityNumber = result.getString(6)
                )
            }
        }

    fun saveCreditDisbursementBank(
        userId: Long,
        request: SaveCreditDisbursementBankRequest
    ): CreditDisbursementBankDto = database.transaction { connection ->
        val bank = connection.prepareStatement(
            "SELECT code,name FROM directorio_bancos WHERE code=? AND active=TRUE"
        ).use { statement ->
            statement.setString(1, request.bankCode.trim())
            statement.executeQuery().use { result ->
                if (!result.next()) throw AppException("Selecciona un banco válido.")
                result.getString(1) to result.getString(2)
            }
        }
        val accountType = request.accountType.trim().uppercase()
        require(accountType in setOf("CORRIENTE", "AHORRO")) {
            "Selecciona un tipo de cuenta válido."
        }
        val accountNumber = request.accountNumber.filter(Char::isDigit)
        require(accountNumber.length in 10..30) {
            "Ingresa un número de cuenta válido."
        }
        require(request.holderName.trim().length >= 5) {
            "Ingresa el nombre completo del titular."
        }
        require(request.identityNumber.trim().length >= 5) {
            "Ingresa la identificación del titular."
        }

        connection.prepareStatement(
            """INSERT INTO cuentas_desembolso_credito(
                   user_id,bank_code,bank_name,account_type,account_number,holder_name,identity_number
               ) VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(user_id) DO UPDATE SET
                   bank_code=EXCLUDED.bank_code,
                   bank_name=EXCLUDED.bank_name,
                   account_type=EXCLUDED.account_type,
                   account_number=EXCLUDED.account_number,
                   holder_name=EXCLUDED.holder_name,
                   identity_number=EXCLUDED.identity_number,
                   updated_at=NOW()"""
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, bank.first)
            statement.setString(3, bank.second)
            statement.setString(4, accountType)
            statement.setString(5, accountNumber)
            statement.setString(6, request.holderName.trim())
            statement.setString(7, request.identityNumber.trim())
            statement.executeUpdate()
        }

        CreditDisbursementBankDto(
            bankCode = bank.first,
            bankName = bank.second,
            accountType = accountType,
            accountNumber = accountNumber,
            holderName = request.holderName.trim(),
            identityNumber = request.identityNumber.trim()
        )
    }

    private fun loanBusinessSnapshot(
        connection: Connection,
        businessId: Long,
        requireActive: Boolean
    ): LoanBusinessSnapshot = connection.prepareStatement(
        """SELECT id,commercial_name,legal_name,rif,logo_path,active,payment_mode,
                  mobile_bank,mobile_phone,mobile_identity_number,mobile_holder_name,
                  bank_name,bank_account_type,bank_account_number,bank_identity_number,bank_holder_name
           FROM negocios_asociados WHERE id=? FOR SHARE"""
    ).use { statement ->
        statement.setLong(1, businessId)
        statement.executeQuery().use { result ->
            if (!result.next()) throw AppException("La empresa seleccionada no existe.")
            val active = result.getBoolean("active")
            if (requireActive && !active) throw AppException("Selecciona una empresa asociada activa.")
            LoanBusinessSnapshot(
                id = result.getLong("id"),
                commercialName = result.getString("commercial_name"),
                legalName = result.getString("legal_name"),
                rif = result.getString("rif"),
                logoPath = result.getString("logo_path"),
                active = active,
                paymentMode = result.getString("payment_mode"),
                mobileBank = result.getString("mobile_bank"),
                mobilePhone = result.getString("mobile_phone"),
                mobileIdentityNumber = result.getString("mobile_identity_number"),
                mobileHolderName = result.getString("mobile_holder_name"),
                bankName = result.getString("bank_name"),
                bankAccountType = result.getString("bank_account_type"),
                bankAccountNumber = result.getString("bank_account_number"),
                bankIdentityNumber = result.getString("bank_identity_number"),
                bankHolderName = result.getString("bank_holder_name")
            )
        }
    }

    private fun validateLoanRepaymentBusiness(business: LoanBusinessSnapshot) {
        when (business.paymentMode) {
            "MOBILE_PAYMENT" -> if (business.mobileBank.isNullOrBlank()) throw AppException("La empresa no tiene un pago móvil configurado para recibir cuotas.")
            "BANK_TRANSFER" -> if (business.bankName.isNullOrBlank()) throw AppException("La empresa no tiene una cuenta bancaria configurada para recibir cuotas.")
            "BOTH" -> if (business.mobileBank.isNullOrBlank() || business.bankName.isNullOrBlank()) {
                throw AppException("Completa las cuentas de cobro de la empresa antes de aprobar el préstamo.")
            }
            else -> throw AppException("La empresa tiene un método de cobro inválido.")
        }
    }

    fun createCreditRequest(userId: Long, request: CreditRequestCreateRequest): CreditRequestDto = database.transaction { connection ->
        ensureCreditAccount(connection, userId)
        ensureCreditHistory(connection, userId)
        refreshCreditStatuses(connection, userId)
        val progress = refreshCredimpulsoLevel(connection, userId)
        val history = creditHistorySnapshot(connection, userId)
        val account = creditAccountSnapshot(connection, userId)
        if (history.status != "ACTIVE" || account.status != "ACTIVE") {
            throw ForbiddenException("Tu crédito no está disponible por el estado actual de tu historial crediticio.")
        }
        if (progress.current.level < 3) {
            throw ForbiddenException("Los préstamos Crédito Kredi+ están disponibles a partir del Nivel 3 · Autana.")
        }
        val levelLimit = progress.current.baseAmountUsd * progress.current.creditMultiplier
        val maximumLoan = levelLimit * 2.0
        require(request.amountUsd >= 5.0 && request.amountUsd <= maximumLoan) {
            "Según tu nivel puedes solicitar entre US$5 y US$${String.format(java.util.Locale.US, "%.2f", maximumLoan)}."
        }
        require(request.installments in 2..progress.current.maxInstallments) {
            "Tu Nivel ${progress.current.level} permite seleccionar entre 2 y ${progress.current.maxInstallments} cuotas."
        }
        connection.prepareStatement("SELECT 1 FROM solicitudes_credito WHERE user_id=? AND status='PENDING'").use { st ->
            st.setLong(1, userId)
            if (st.executeQuery().use { it.next() }) throw AppException("Ya tienes una solicitud de crédito pendiente.")
        }
        val id = connection.prepareStatement(
            "INSERT INTO solicitudes_credito(user_id,requested_amount_usd,requested_installments,purpose) VALUES (?,?,?,?) RETURNING id",
        ).use { st ->
            st.setLong(1,userId)
            st.setDouble(2,request.amountUsd)
            st.setInt(3,request.installments)
            st.setString(4,request.purpose.trim().take(250))
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        creditRequestById(connection, id)
    }

    fun creditRequests(userId: Long): List<CreditRequestDto> = database.transaction { connection ->
        creditRequestsQuery(connection, "WHERE cr.user_id=?", listOf(userId))
    }

    fun adminCreditRequests(): List<CreditRequestDto> = database.transaction { connection ->
        creditRequestsQuery(connection, "", emptyList())
    }

    fun decideCreditRequest(adminId: Long, requestId: Long, request: CreditRequestDecisionRequest): CreditRequestDto {
        val currentApprovalRate = if (request.approved) runCatching { safeCurrentBcvRate().rate }.getOrNull() else null

        data class DecisionResult(
            val dto: CreditRequestDto,
            val userId: Long,
            val amountUsd: Double,
            val amountBs: Double,
            val availableBalanceUsd: Double,
            val reference: String?,
            val invoiceNumber: String?,
            val installmentCount: Int,
            val destinationLabel: String,
            val lenderLabel: String,
            val regularizedLegacy: Boolean
        )

        val result = database.transaction { connection ->
            val row = connection.prepareStatement(
                """SELECT cr.user_id,cr.requested_amount_usd,cr.requested_installments,cr.status,
                          cr.approval_bcv_rate,cr.approved_amount_bs,cr.wallet_reference,cr.wallet_transaction_id,
                          cr.source_wallet_address,cr.destination_wallet_address,
                          EXISTS(SELECT 1 FROM prestamos_credito cl WHERE cl.credit_request_id=cr.id) AS loan_exists
                   FROM solicitudes_credito cr WHERE cr.id=? FOR UPDATE"""
            ).use { statement ->
                statement.setLong(1, requestId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw AppException("Solicitud de crédito no encontrada.")
                    CreditRequestDecisionRow(
                        userId = result.getLong(1),
                        amountUsd = result.getDouble(2),
                        requestedInstallments = result.getInt(3),
                        status = result.getString(4),
                        approvalBcvRate = result.getBigDecimal(5)?.toDouble(),
                        approvedAmountBs = result.getBigDecimal(6)?.toDouble(),
                        walletReference = result.getString(7),
                        walletTransactionId = result.getString(8),
                        sourceWalletAddress = result.getString(9),
                        destinationWalletAddress = result.getString(10),
                        loanExists = result.getBoolean(11)
                    )
                }
            }
            val regularizedLegacy = request.approved && row.status == "APPROVED" && !row.loanExists
            when {
                row.status == "PENDING" -> Unit
                regularizedLegacy -> Unit
                else -> throw AppException("Esta solicitud ya fue procesada.")
            }
            if (!request.approved && row.status != "PENDING") {
                throw AppException("Un préstamo aprobado anteriormente no puede rechazarse; complétalo desde Configurar préstamo.")
            }

            val userId = row.userId
            val amountUsd = row.amountUsd
            val status = if (request.approved) "APPROVED" else "REJECTED"
            var amountBs = if (regularizedLegacy) row.approvedAmountBs ?: 0.0 else 0.0
            var availableBalanceUsd = 0.0
            var reference: String? = if (regularizedLegacy) row.walletReference else null
            var transactionId: String? = if (regularizedLegacy) row.walletTransactionId else null
            var sourceWalletAddress: String? = if (regularizedLegacy) row.sourceWalletAddress else null
            var destinationWalletAddress: String? = if (regularizedLegacy) row.destinationWalletAddress else null
            var invoiceNumber: String? = null
            var effectiveApprovalBcvRate: Double? = row.approvalBcvRate
            var installmentCount = row.requestedInstallments.coerceIn(2, 6)
            var destinationLabel = "No aplica"
            var lenderLabel = "No aplica"

            if (request.approved) {
                val approvedRate = row.approvalBcvRate?.takeIf { it > 0.0 && it.isFinite() }
                    ?: currentApprovalRate?.takeIf { it > 0.0 && it.isFinite() }
                    ?: throw AppException("La tasa BCV no está disponible. No se pudo crear el préstamo.")
                effectiveApprovalBcvRate = approvedRate
                ensureCreditAccount(connection, userId)
                ensureCreditHistory(connection, userId)
                refreshCreditStatuses(connection, userId)
                val history = creditHistorySnapshot(connection, userId)
                if (history.status == "SUSPENDED") throw ForbiddenException("El crédito del usuario está suspendido por atrasos.")

                val progress = refreshCredimpulsoLevel(connection, userId)
                installmentCount = row.requestedInstallments.coerceAtMost(progress.current.maxInstallments)
                val lenderType = request.lenderType.orEmpty().trim().uppercase().ifBlank { "ADMIN_WALLET" }
                if (lenderType !in setOf("ADMIN_WALLET", "ASSOCIATED_BUSINESS")) {
                    throw AppException("Selecciona un prestamista válido.")
                }
                val disbursementType = request.disbursementDestinationType.orEmpty().trim().uppercase()
                    .ifBlank { "CREDICASH_WALLET" }
                if (disbursementType !in setOf("CREDICASH_WALLET", "BANK_ACCOUNT")) {
                    throw AppException("Selecciona una cuenta de desembolso válida.")
                }

                val lenderBusiness = if (lenderType == "ASSOCIATED_BUSINESS") {
                    val businessId = request.lenderBusinessId
                        ?: throw AppException("Selecciona la empresa que otorgará el préstamo.")
                    loanBusinessSnapshot(connection, businessId, requireActive = true)
                } else null
                val repaymentBusinessId = if (lenderType == "ASSOCIATED_BUSINESS") {
                    lenderBusiness!!.id
                } else {
                    request.repaymentBusinessId
                        ?: throw AppException("Selecciona la cuenta empresarial que recibirá las cuotas.")
                }
                val repaymentBusiness = if (lenderBusiness?.id == repaymentBusinessId) {
                    lenderBusiness
                } else {
                    loanBusinessSnapshot(connection, repaymentBusinessId, requireActive = true)
                }
                validateLoanRepaymentBusiness(repaymentBusiness)

                val disbursementBank = if (disbursementType == "BANK_ACCOUNT") {
                    creditDisbursementBank(connection, userId)
                        ?: throw AppException("El ciudadano no ha registrado una cuenta bancaria para recibir el préstamo.")
                } else null

                val before = creditAccountSnapshot(connection, userId)
                val afterLimit = if (regularizedLegacy) before.creditLimitUsd
                    else MoneyMath.addUsd(before.creditLimitUsd, amountUsd).toDouble()
                if (amountBs <= 0.0 || !amountBs.isFinite()) {
                    amountBs = MoneyMath.usdToVes(amountUsd, approvedRate).toDouble()
                }
                reference = reference?.takeIf { it.isNotBlank() } ?: "APR-${System.currentTimeMillis()}-$requestId"
                transactionId = transactionId?.takeIf { it.isNotBlank() }
                    ?: "TX-${UUID.randomUUID().toString().replace("-", "").uppercase()}"
                invoiceNumber = "PRE-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}-${requestId.toString().padStart(6, '0')}"

                if (lenderType == "ADMIN_WALLET") {
                    ensureAdminCredimpulsoWallet(connection, adminId)
                    sourceWalletAddress = sourceWalletAddress?.takeIf { it.isNotBlank() } ?: connection.prepareStatement(
                        "SELECT wallet_address FROM carteras_credimpulso_admin WHERE admin_id=?"
                    ).use { statement ->
                        statement.setLong(1, adminId)
                        statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
                    }
                    if (!regularizedLegacy) {
                        val compliance = refreshAdminWalletCompliance(connection, adminId)
                        if (compliance.blocked) throw AppException(compliance.reason ?: "El saldo del administrador está retenido.")
                        val adminBalanceBefore = connection.prepareStatement(
                            "SELECT saldo_disponible_usd FROM carteras_credimpulso_admin WHERE admin_id=? FOR UPDATE"
                        ).use { statement ->
                            statement.setLong(1, adminId)
                            statement.executeQuery().use { result ->
                                if (!result.next()) throw AppException("No se encontró la cartera del administrador.")
                                result.getBigDecimal(1).toDouble()
                            }
                        }
                        if (MoneyMath.greaterThanUsd(amountUsd, adminBalanceBefore)) {
                            throw AppException("La cartera no tiene saldo suficiente para aprobar este préstamo.")
                        }
                        val adminBalanceAfter = MoneyMath.subtractUsd(adminBalanceBefore, amountUsd).toDouble()
                        connection.prepareStatement(
                            """UPDATE carteras_credimpulso_admin
                               SET saldo_disponible_usd=?,total_transferido_usd=total_transferido_usd+?,updated_at=NOW()
                               WHERE admin_id=?"""
                        ).use { statement ->
                            statement.setBigDecimal(1, MoneyMath.usd(adminBalanceAfter, "Saldo administrativo"))
                            statement.setBigDecimal(2, MoneyMath.usd(amountUsd))
                            statement.setLong(3, adminId)
                            statement.executeUpdate()
                        }
                        connection.prepareStatement(
                            """INSERT INTO movimientos_cartera_credimpulso(
                                   admin_id,user_id,tipo,monto_usd,saldo_antes_usd,saldo_despues_usd,referencia,descripcion
                               ) VALUES (?,?,'TRANSFERENCIA',?,?,?,?,?)"""
                        ).use { statement ->
                            statement.setLong(1, adminId)
                            statement.setLong(2, userId)
                            statement.setBigDecimal(3, MoneyMath.usd(amountUsd))
                            statement.setBigDecimal(4, MoneyMath.usd(adminBalanceBefore, "Saldo administrativo anterior"))
                            statement.setBigDecimal(5, MoneyMath.usd(adminBalanceAfter, "Saldo administrativo"))
                            statement.setString(6, reference)
                            statement.setString(7, "Préstamo $invoiceNumber en $installmentCount cuotas")
                            statement.executeUpdate()
                        }
                    }
                    lenderLabel = if (regularizedLegacy) "Cartera Kredi+ del administrador · préstamo anterior"
                        else "Cartera Kredi+ del administrador"
                } else {
                    sourceWalletAddress = "EMP-${lenderBusiness!!.id}-${lenderBusiness.rif.replace("-", "")}".take(80)
                    lenderLabel = lenderBusiness.commercialName
                }

                lockCreditAccount(connection, userId)
                if (regularizedLegacy) {
                    connection.prepareStatement(
                        "UPDATE cuentas_credito SET preferred_installments=?,status='ACTIVE',updated_at=NOW() WHERE user_id=?"
                    ).use { statement ->
                        statement.setInt(1, installmentCount)
                        statement.setLong(2, userId)
                        statement.executeUpdate()
                    }
                } else {
                    connection.prepareStatement(
                        "UPDATE cuentas_credito SET credit_limit_usd=?,preferred_installments=?,status='ACTIVE',updated_at=NOW() WHERE user_id=?"
                    ).use { statement ->
                        statement.setBigDecimal(1, MoneyMath.usd(afterLimit, "Límite posterior"))
                        statement.setInt(2, installmentCount)
                        statement.setLong(3, userId)
                        statement.executeUpdate()
                    }
                }

                destinationWalletAddress = if (disbursementType == "BANK_ACCOUNT") {
                    val bank = disbursementBank!!
                    destinationLabel = "${bank.bankName} · ${bank.accountType} · ••••${bank.accountNumber.takeLast(4)}"
                    "BANCO-${bank.bankCode}-${bank.accountNumber.takeLast(4)}"
                } else {
                    val wallet = creditWalletAddress(connection, userId)
                    destinationLabel = "Cartera Kredi+ $wallet"
                    wallet
                }

                val loanId = connection.prepareStatement(
                    """INSERT INTO prestamos_credito(
                           user_id,order_id,level,principal_usd,principal_bs,bcv_rate,installment_count,status,
                           invoice_number,credit_request_id,lender_type,lender_business_id,repayment_business_id,
                           disbursement_destination_type,disbursement_bank_code,disbursement_bank_name,
                           disbursement_account_type,disbursement_account_number,disbursement_holder_name,
                           disbursement_identity_number,repayment_payment_mode,repayment_business_commercial_name,
                           repayment_business_legal_name,repayment_business_rif,repayment_business_logo_path,
                           repayment_mobile_bank,repayment_mobile_phone,repayment_mobile_identity_number,
                           repayment_mobile_holder_name,repayment_bank_name,repayment_bank_account_type,
                           repayment_bank_account_number,repayment_bank_identity_number,repayment_bank_holder_name
                       ) VALUES (?,NULL,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                       RETURNING id"""
                ).use { statement ->
                    var index = 1
                    statement.setLong(index++, userId)
                    statement.setInt(index++, progress.current.level)
                    statement.setBigDecimal(index++, MoneyMath.usd(amountUsd))
                    statement.setBigDecimal(index++, MoneyMath.ves(amountBs))
                    statement.setBigDecimal(index++, MoneyMath.rate(approvedRate))
                    statement.setInt(index++, installmentCount)
                    statement.setString(index++, invoiceNumber)
                    statement.setLong(index++, requestId)
                    statement.setString(index++, lenderType)
                    if (lenderBusiness == null) statement.setNull(index++, java.sql.Types.BIGINT) else statement.setLong(index++, lenderBusiness.id)
                    statement.setLong(index++, repaymentBusiness.id)
                    statement.setString(index++, disbursementType)
                    statement.setString(index++, disbursementBank?.bankCode)
                    statement.setString(index++, disbursementBank?.bankName)
                    statement.setString(index++, disbursementBank?.accountType)
                    statement.setString(index++, disbursementBank?.accountNumber)
                    statement.setString(index++, disbursementBank?.holderName)
                    statement.setString(index++, disbursementBank?.identityNumber)
                    statement.setString(index++, repaymentBusiness.paymentMode)
                    statement.setString(index++, repaymentBusiness.commercialName)
                    statement.setString(index++, repaymentBusiness.legalName)
                    statement.setString(index++, repaymentBusiness.rif)
                    statement.setString(index++, repaymentBusiness.logoPath)
                    statement.setString(index++, repaymentBusiness.mobileBank)
                    statement.setString(index++, repaymentBusiness.mobilePhone)
                    statement.setString(index++, repaymentBusiness.mobileIdentityNumber)
                    statement.setString(index++, repaymentBusiness.mobileHolderName)
                    statement.setString(index++, repaymentBusiness.bankName)
                    statement.setString(index++, repaymentBusiness.bankAccountType)
                    statement.setString(index++, repaymentBusiness.bankAccountNumber)
                    statement.setString(index++, repaymentBusiness.bankIdentityNumber)
                    statement.setString(index++, repaymentBusiness.bankHolderName)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }

                val usdInstallments = MoneyMath.splitExact(MoneyMath.usd(amountUsd), installmentCount, MoneyMath.USD_SCALE)
                val bsInstallments = MoneyMath.splitExact(MoneyMath.ves(amountBs), installmentCount, MoneyMath.VES_SCALE)
                for (number in 1..installmentCount) {
                    connection.prepareStatement(
                        "INSERT INTO cuotas_credito(loan_id,installment_number,amount_usd,original_amount_bs,due_date,status) VALUES (?,?,?,?,?,'PENDING')"
                    ).use { statement ->
                        statement.setLong(1, loanId)
                        statement.setInt(2, number)
                        statement.setBigDecimal(3, usdInstallments[number - 1])
                        statement.setBigDecimal(4, bsInstallments[number - 1])
                        statement.setObject(5, LocalDate.now().plusDays(30L * number))
                        statement.executeUpdate()
                    }
                }

                availableBalanceUsd = creditAccountSnapshot(connection, userId).availableUsd
                recordCredimpulsoTransaction(
                    connection = connection,
                    userId = userId,
                    type = if (regularizedLegacy) "CREDIT_REQUEST_REGULARIZATION" else "CREDIT_REQUEST_APPROVAL",
                    amountUsd = amountUsd,
                    amountBs = amountBs,
                    bcvRate = approvedRate,
                    balanceBeforeUsd = before.availableUsd,
                    balanceAfterUsd = availableBalanceUsd,
                    description = if (regularizedLegacy) {
                        "Préstamo anterior $invoiceNumber incorporado a Pagos por $lenderLabel en $installmentCount cuotas · $reference"
                    } else {
                        "Préstamo $invoiceNumber desembolsado por $lenderLabel en $installmentCount cuotas · $reference"
                    },
                    loanId = loanId,
                    performedBy = adminId
                )
                audit(
                    connection,
                    adminId,
                    if (regularizedLegacy) "ADMIN_REGULARIZED_CREDIT_REQUEST" else "ADMIN_APPROVED_CREDIT_REQUEST",
                    "CREDIT_REQUEST",
                    requestId.toString(),
                    "US$ $amountUsd · $invoiceNumber · $lenderLabel · $destinationLabel"
                )
            }

            connection.prepareStatement(
                """UPDATE solicitudes_credito
                   SET status=?,reviewed_by=?,reviewed_at=NOW(),approval_bcv_rate=?,approved_amount_bs=?,wallet_reference=?,
                       wallet_transaction_id=?,source_wallet_address=?,destination_wallet_address=?,
                       disbursed_at=CASE WHEN ? THEN COALESCE(disbursed_at,NOW()) ELSE NULL END
                   WHERE id=?"""
            ).use { statement ->
                statement.setString(1, status)
                statement.setLong(2, adminId)
                effectiveApprovalBcvRate?.let { statement.setBigDecimal(3, MoneyMath.rate(it)) }
                    ?: statement.setNull(3, java.sql.Types.NUMERIC)
                if (!request.approved) statement.setNull(4, java.sql.Types.NUMERIC)
                else statement.setBigDecimal(4, MoneyMath.ves(amountBs))
                statement.setString(5, reference)
                statement.setString(6, transactionId)
                statement.setString(7, sourceWalletAddress)
                statement.setString(8, destinationWalletAddress)
                statement.setBoolean(9, request.approved)
                statement.setLong(10, requestId)
                statement.executeUpdate()
            }
            if (!request.approved) {
                audit(connection, adminId, "ADMIN_REJECTED_CREDIT_REQUEST", "CREDIT_REQUEST", requestId.toString(), "Solicitud rechazada")
            }
            DecisionResult(
                dto = creditRequestById(connection, requestId),
                userId = userId,
                amountUsd = amountUsd,
                amountBs = amountBs,
                availableBalanceUsd = availableBalanceUsd,
                reference = reference,
                invoiceNumber = invoiceNumber,
                installmentCount = installmentCount,
                destinationLabel = destinationLabel,
                lenderLabel = lenderLabel,
                regularizedLegacy = regularizedLegacy
            )
        }

        if (request.approved) {
            notifyUsers(
                listOf(result.userId),
                if (result.regularizedLegacy) "Préstamo incorporado a Pagos" else "Préstamo Kredi+ desembolsado",
                if (result.regularizedLegacy) {
                    "Tu préstamo anterior de US$ ${String.format(Locale.US, "%,.2f", result.amountUsd)} ya tiene ${result.installmentCount} cuotas visibles en Pagos. Cuenta asignada: ${result.destinationLabel}."
                } else {
                    "Se aprobaron US$ ${String.format(Locale.US, "%,.2f", result.amountUsd)} en ${result.installmentCount} cuotas. Destino: ${result.destinationLabel}. Referencia ${result.reference.orEmpty()}."
                },
                if (result.regularizedLegacy) "CREDIT_REQUEST_REGULARIZED" else "CREDIT_REQUEST_APPROVED",
                mapOf(
                    "invoiceNumber" to result.invoiceNumber.orEmpty(),
                    "reference" to result.reference.orEmpty(),
                    "lender" to result.lenderLabel,
                    "amountUsd" to String.format(Locale.US, "%.2f", result.amountUsd),
                    "amountBs" to String.format(Locale.US, "%.2f", result.amountBs)
                )
            )
        } else {
            notifyUsers(
                listOf(result.userId),
                "Solicitud Crédito Kredi+ rechazada",
                "Tu solicitud de préstamo no fue aprobada.",
                "CREDIT_REQUEST_REJECTED"
            )
        }
        return result.dto
    }

    private fun creditRequestById(connection: Connection, id: Long): CreditRequestDto =
        creditRequestsQuery(connection, "WHERE cr.id=?", listOf(id)).firstOrNull()
            ?: throw AppException("Solicitud de crédito no encontrada.")

    private fun creditRequestsQuery(connection: Connection, where: String, params: List<Long>): List<CreditRequestDto> =
        connection.prepareStatement(
            """SELECT cr.id,cr.user_id,
                      COALESCE(NULLIF(TRIM(CONCAT_WS(' ',up.first_name,up.middle_name,up.last_name,up.second_last_name)),''),u.email) AS customer_name,
                      cr.requested_amount_usd,cr.requested_installments,cr.purpose,cr.status,cr.created_at,
                      cr.wallet_transaction_id,cr.source_wallet_address,
                      COALESCE(cr.destination_wallet_address,cc.wallet_address) AS destination_wallet_address,
                      cr.wallet_reference,cr.approved_amount_bs,cr.approval_bcv_rate,
                      COALESCE(cl.disbursement_bank_code,cdb.bank_code) AS disbursement_bank_code,
                      COALESCE(cl.disbursement_bank_name,cdb.bank_name) AS disbursement_bank_name,
                      COALESCE(cl.disbursement_account_type,cdb.account_type) AS disbursement_account_type,
                      COALESCE(cl.disbursement_account_number,cdb.account_number) AS disbursement_account_number,
                      COALESCE(cl.disbursement_holder_name,cdb.holder_name) AS disbursement_holder_name,
                      COALESCE(cl.disbursement_identity_number,cdb.identity_number) AS disbursement_identity_number,
                      cl.lender_type,cl.lender_business_id,
                      lb.commercial_name AS lender_commercial_name,lb.legal_name AS lender_legal_name,
                      lb.rif AS lender_rif,lb.logo_path AS lender_logo_path,
                      cl.id AS loan_id,cl.invoice_number,cl.disbursement_destination_type,
                      CASE WHEN cl.id IS NULL THEN NULL ELSE -cl.id END AS payment_fair_id,
                      CASE WHEN cl.id IS NULL THEN NULL ELSE 'Préstamo ' || COALESCE(cl.invoice_number,'CRED-' || cl.id::text) END AS payment_fair_name,
                      cl.repayment_payment_mode AS payment_mode,cl.repayment_business_id AS business_id,
                      cl.repayment_business_commercial_name AS business_commercial_name,
                      cl.repayment_business_legal_name AS business_legal_name,
                      cl.repayment_business_rif AS business_rif,
                      cl.repayment_business_logo_path AS business_logo_path,
                      cl.repayment_mobile_bank AS mobile_bank,cl.repayment_mobile_phone AS mobile_phone,
                      cl.repayment_mobile_identity_number AS mobile_identity_number,
                      cl.repayment_mobile_holder_name AS mobile_holder_name,
                      cl.repayment_bank_name AS bank_name,cl.repayment_bank_account_type AS bank_account_type,
                      cl.repayment_bank_account_number AS bank_account_number,
                      cl.repayment_bank_identity_number AS bank_identity_number,
                      cl.repayment_bank_holder_name AS bank_holder_name
               FROM solicitudes_credito cr
               JOIN usuarios u ON u.id=cr.user_id
               LEFT JOIN perfiles_usuario up ON up.user_id=u.id
               LEFT JOIN cuentas_credito cc ON cc.user_id=cr.user_id
               LEFT JOIN cuentas_desembolso_credito cdb ON cdb.user_id=cr.user_id
               LEFT JOIN prestamos_credito cl ON cl.credit_request_id=cr.id
               LEFT JOIN negocios_asociados lb ON lb.id=cl.lender_business_id
               $where ORDER BY cr.created_at DESC"""
        ).use { statement ->
            params.forEachIndexed { index, value -> statement.setLong(index + 1, value) }
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val bankCode = result.getString("disbursement_bank_code")
                        val lenderBusinessId = result.getLong("lender_business_id").let { if (result.wasNull()) null else it }
                        val loanId = result.getLong("loan_id").let { if (result.wasNull()) null else it }
                        add(
                            CreditRequestDto(
                                id = result.getLong("id"),
                                userId = result.getLong("user_id"),
                                customerName = result.getString("customer_name"),
                                amountUsd = result.getBigDecimal("requested_amount_usd").toDouble(),
                                installments = result.getInt("requested_installments"),
                                purpose = result.getString("purpose"),
                                status = result.getString("status"),
                                createdAt = result.getObject("created_at", OffsetDateTime::class.java).toString(),
                                transactionId = result.getString("wallet_transaction_id"),
                                sourceWalletAddress = result.getString("source_wallet_address"),
                                destinationWalletAddress = result.getString("destination_wallet_address"),
                                walletReference = result.getString("wallet_reference"),
                                approvedAmountBs = result.getBigDecimal("approved_amount_bs")?.toDouble(),
                                approvalBcvRate = result.getBigDecimal("approval_bcv_rate")?.toDouble(),
                                disbursementBank = bankCode?.let {
                                    CreditDisbursementBankDto(
                                        bankCode = it,
                                        bankName = result.getString("disbursement_bank_name").orEmpty(),
                                        accountType = result.getString("disbursement_account_type").orEmpty(),
                                        accountNumber = result.getString("disbursement_account_number").orEmpty(),
                                        holderName = result.getString("disbursement_holder_name").orEmpty(),
                                        identityNumber = result.getString("disbursement_identity_number").orEmpty()
                                    )
                                },
                                lenderType = result.getString("lender_type"),
                                lenderBusiness = lenderBusinessId?.let {
                                    AssociatedBusinessSummaryDto(
                                        id = it,
                                        commercialName = result.getString("lender_commercial_name").orEmpty(),
                                        legalName = result.getString("lender_legal_name").orEmpty(),
                                        rif = result.getString("lender_rif").orEmpty(),
                                        logoUrl = publicUrl(result.getString("lender_logo_path"))
                                    )
                                },
                                loanId = loanId,
                                invoiceNumber = result.getString("invoice_number"),
                                disbursementDestinationType = result.getString("disbursement_destination_type"),
                                paymentDestination = paymentDestinationDto(result)
                            )
                        )
                    }
                }
            }
        }

    private fun creditWalletAddress(connection: Connection, userId: Long): String =
        connection.prepareStatement("SELECT COALESCE(wallet_address,'') FROM cuentas_credito WHERE user_id=?").use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1).orEmpty() else "" }
        }



    // -------------------------------------------------------------------------
    // Kredi+ QR Commerce: compra presencial estilo BNPL, sin custodia de fondos.
    // El cliente escanea una caja, el comercio fija el total y Kredi+ calcula el
    // plan según nivel + línea disponible. La inicial se confirma como recibida
    // por el comercio y nunca entra en una cartera Kredi+.
    fun qrTerminals(): List<QrTerminalDto> = database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT t.id,t.business_id,b.commercial_name,t.branch_name,t.terminal_name,t.public_code,t.active,t.created_at
               FROM terminales_qr_negocio t JOIN negocios_asociados b ON b.id=t.business_id
               ORDER BY b.commercial_name,t.branch_name,t.terminal_name"""
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(qrTerminalDto(rs)) } } }
    }

    fun createQrTerminal(actorId: Long, request: QrTerminalCreateRequest): QrTerminalDto = database.transaction { connection ->
        if (currentRole(actorId) != "ADMIN") throw ForbiddenException("Solo Administración puede crear cajas QR.")
        val business = findAssociatedBusiness(connection, request.businessId)
        if (!business.active) throw AppException("Activa el negocio antes de crear una caja QR.")
        val branch = request.branchName.trim().takeIf { it.isNotEmpty() } ?: throw AppException("Indica la sucursal.")
        val terminal = request.terminalName.trim().takeIf { it.isNotEmpty() } ?: throw AppException("Indica la caja.")
        val code = "KQ-" + UUID.randomUUID().toString().replace("-", "").uppercase()
        val id = connection.prepareStatement(
            """INSERT INTO terminales_qr_negocio(business_id,branch_name,terminal_name,public_code,created_by)
               VALUES (?,?,?,?,?) RETURNING id"""
        ).use { st ->
            st.setLong(1, request.businessId); st.setString(2, branch); st.setString(3, terminal); st.setString(4, code); st.setLong(5, actorId)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        audit(connection, actorId, "BUSINESS_QR_CREATED", "BUSINESS", request.businessId.toString(), "$branch · $terminal")
        findQrTerminal(connection, id)
    }

    fun setQrTerminalActive(actorId: Long, terminalId: Long, active: Boolean): QrTerminalDto = database.transaction { connection ->
        if (currentRole(actorId) != "ADMIN") throw ForbiddenException("Solo Administración puede administrar cajas QR.")
        connection.prepareStatement("UPDATE terminales_qr_negocio SET active=?,updated_at=NOW() WHERE id=?").use { st ->
            st.setBoolean(1, active); st.setLong(2, terminalId); if (st.executeUpdate()!=1) throw NotFoundException("Caja QR no encontrada.")
        }
        audit(connection, actorId, if(active) "BUSINESS_QR_ENABLED" else "BUSINESS_QR_DISABLED", "QR_TERMINAL", terminalId.toString(), null)
        findQrTerminal(connection, terminalId)
    }

    fun publicQrPayload(rawCode: String): String = database.dataSource.connection.use { connection ->
        val code = normalizeQrCode(rawCode)
        if (code.startsWith("KQO-")) {
            val exists = connection.prepareStatement(
                """SELECT EXISTS(SELECT 1 FROM ofertas_qr_comerciales o JOIN negocios_asociados b ON b.id=o.business_id
                   WHERE o.public_code=? AND o.status IN ('ACTIVE','SCHEDULED') AND b.active=TRUE)"""
            ).use { st -> st.setString(1, code); st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
            if (!exists) throw NotFoundException("QR no encontrado o inactivo.")
            "krediplus://offer?code=$code"
        } else {
            val exists = connection.prepareStatement(
                """SELECT EXISTS(SELECT 1 FROM terminales_qr_negocio t JOIN negocios_asociados b ON b.id=t.business_id
                   WHERE t.public_code=? AND t.active=TRUE AND b.active=TRUE)"""
            ).use { st -> st.setString(1, code); st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
            if (!exists) throw NotFoundException("QR no encontrado o inactivo.")
            "krediplus://buy?terminal=$code"
        }
    }


    // -------------------------------------------------------------------------
    // Kredi+ QR Commerce 2.0: el QR representa una oferta con precio definido.
    // -------------------------------------------------------------------------
    fun commercialQrOffers(includeInactive: Boolean = false): List<CommercialQrOfferDto> = database.transaction { connection ->
        refreshCommercialOfferStates(connection)
        val statusFilter = if (includeInactive) "" else " AND o.status IN ('ACTIVE','SCHEDULED')"
        val sql = """SELECT o.id FROM ofertas_qr_comerciales o
            WHERE (o.offer_type='OFFER' OR o.source_id IS NULL OR o.id=(SELECT MAX(o2.id) FROM ofertas_qr_comerciales o2 WHERE o2.offer_type=o.offer_type AND o2.source_id=o.source_id))
            $statusFilter ORDER BY o.created_at DESC"""
        connection.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(commercialQrOfferDto(connection, rs.getLong(1))) } }
        }
    }

    /**
     * Sincroniza automáticamente las Jornadas y Combos ya existentes con su QR comercial.
     * La fuente (jornada/combo) sigue siendo la verdad para nombre, precio, negocio y productos;
     * el operador no vuelve a escribir IDs ni datos que ya existen en la base.
     */
    fun synchronizeCommercialQrOffers(actorId: Long): List<CommercialQrOfferDto> = database.transaction { connection ->
        refreshCommercialOfferStates(connection)

        // Si una jornada deja de estar publicada/activa o se finaliza, su QR deja de vender.
        connection.prepareStatement(
            """UPDATE ofertas_qr_comerciales o SET status='DISABLED',updated_at=NOW()
               WHERE o.offer_type='JOURNEY' AND o.source_id IS NOT NULL AND o.status IN ('ACTIVE','SCHEDULED')
                 AND NOT EXISTS (SELECT 1 FROM jornadas f WHERE f.id=o.source_id AND f.active=TRUE AND f.published=TRUE AND f.finalized=FALSE)"""
        ).use { it.executeUpdate() }
        connection.prepareStatement(
            """UPDATE ofertas_qr_comerciales o SET status='DISABLED',updated_at=NOW()
               WHERE o.offer_type='COMBO' AND o.source_id IS NOT NULL AND o.status IN ('ACTIVE','SCHEDULED')
                 AND NOT EXISTS (SELECT 1 FROM combos c WHERE c.id=o.source_id AND c.active=TRUE)"""
        ).use { it.executeUpdate() }

        val comboBusiness = catalogBusinessDestination(connection, "combo_business_id")
            ?: catalogBusinessDestination(connection, "product_business_id")
            ?: singleActiveBusinessId(connection)
        if (comboBusiness != null) {
            val comboIds = connection.prepareStatement(
                """SELECT c.id FROM combos c
                   WHERE c.active=TRUE
                     AND EXISTS (SELECT 1 FROM productos_combo pc JOIN productos p ON p.id=pc.product_id WHERE pc.combo_id=c.id AND p.active=TRUE)
                   ORDER BY c.id"""
            ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } } }
            comboIds.forEach { id -> syncCommercialSourceOffer(connection, actorId, "COMBO", id, comboBusiness) }
        }

        val journeys = connection.prepareStatement(
            """SELECT f.id,f.business_id FROM jornadas f
               JOIN negocios_asociados b ON b.id=f.business_id AND b.active=TRUE
               WHERE f.active=TRUE AND f.published=TRUE AND f.finalized=FALSE
                 AND EXISTS (SELECT 1 FROM productos_jornada pj JOIN productos p ON p.id=pj.product_id WHERE pj.fair_id=f.id AND p.active=TRUE)
               ORDER BY f.id"""
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1) to rs.getLong(2)) } } }
        journeys.forEach { (id,businessId) -> syncCommercialSourceOffer(connection, actorId, "JOURNEY", id, businessId) }

        refreshCommercialOfferStates(connection)
        connection.prepareStatement("""SELECT o.id FROM ofertas_qr_comerciales o
            WHERE (o.offer_type='OFFER' OR o.source_id IS NULL OR o.id=(SELECT MAX(o2.id) FROM ofertas_qr_comerciales o2 WHERE o2.offer_type=o.offer_type AND o2.source_id=o.source_id))
            ORDER BY o.created_at DESC""").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(commercialQrOfferDto(connection, rs.getLong(1))) } }
        }
    }

    private fun catalogBusinessDestination(connection: Connection, column: String): Long? {
        if (column !in setOf("combo_business_id","product_business_id")) return null
        return connection.prepareStatement("SELECT $column FROM configuracion_ventas_catalogo WHERE id=1").use { st ->
            st.executeQuery().use { rs ->
                if (!rs.next()) null else rs.getLong(1).takeUnless { rs.wasNull() }
            }
        }?.takeIf { id ->
            connection.prepareStatement("SELECT active FROM negocios_asociados WHERE id=?").use { st ->
                st.setLong(1,id); st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }
    }

    private fun singleActiveBusinessId(connection: Connection): Long? {
        val ids = connection.prepareStatement("SELECT id FROM negocios_asociados WHERE active=TRUE ORDER BY id LIMIT 2").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
        return ids.singleOrNull()
    }

    private fun syncCommercialSourceOffer(connection: Connection, actorId: Long, type: String, sourceId: Long, businessId: Long): Long? {
        var name = ""
        var description = ""
        var coverPath: String? = null
        var price = BigDecimal.ZERO
        val lines = mutableListOf<Triple<Long,Int,BigDecimal>>()

        if (type == "COMBO") {
            val row = connection.prepareStatement("SELECT name,description,cover_path,combo_price_usd FROM combos WHERE id=? AND active=TRUE").use { st ->
                st.setLong(1,sourceId); st.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    arrayOf<Any?>(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBigDecimal(4))
                }
            }
            name = row[0] as String
            description = (row[1] as String?) ?: ""
            coverPath = row[2] as String?
            price = ((row[3] as BigDecimal?) ?: BigDecimal.ZERO).setScale(2,RoundingMode.HALF_EVEN)
            connection.prepareStatement("SELECT pc.product_id,pc.quantity,p.base_price_usd FROM productos_combo pc JOIN productos p ON p.id=pc.product_id AND p.active=TRUE WHERE pc.combo_id=? ORDER BY pc.product_id").use { st ->
                st.setLong(1,sourceId); st.executeQuery().use { rs -> while(rs.next()) lines += Triple(rs.getLong(1),rs.getInt(2),rs.getBigDecimal(3) ?: BigDecimal.ZERO) }
            }
            if (price <= BigDecimal.ZERO) price = lines.fold(BigDecimal.ZERO) { total,line -> total + line.third.multiply(line.second.toBigDecimal()) }.setScale(2,RoundingMode.HALF_EVEN)
        } else {
            val row = connection.prepareStatement("SELECT name,description,cover_path FROM jornadas WHERE id=? AND active=TRUE AND published=TRUE AND finalized=FALSE").use { st ->
                st.setLong(1,sourceId); st.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    arrayOf<Any?>(rs.getString(1),rs.getString(2),rs.getString(3))
                }
            }
            name = row[0] as String
            description = (row[1] as String?) ?: ""
            coverPath = row[2] as String?
            connection.prepareStatement("SELECT pj.product_id,COALESCE(pj.quantity,1),p.base_price_usd FROM productos_jornada pj JOIN productos p ON p.id=pj.product_id AND p.active=TRUE WHERE pj.fair_id=? ORDER BY pj.product_id").use { st ->
                st.setLong(1,sourceId); st.executeQuery().use { rs -> while(rs.next()) lines += Triple(rs.getLong(1),rs.getInt(2),rs.getBigDecimal(3) ?: BigDecimal.ZERO) }
            }
            price = lines.fold(BigDecimal.ZERO) { total,line -> total + line.third.multiply(line.second.toBigDecimal()) }.setScale(2,RoundingMode.HALF_EVEN)
        }
        if (lines.isEmpty() || price <= BigDecimal.ZERO) return null
        val businessActive = connection.prepareStatement("SELECT active FROM negocios_asociados WHERE id=?").use { st ->
            st.setLong(1,businessId); st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
        if (!businessActive) return null

        val existing = connection.prepareStatement("SELECT id,status FROM ofertas_qr_comerciales WHERE offer_type=? AND source_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE").use { st ->
            st.setString(1,type); st.setLong(2,sourceId); st.executeQuery().use { rs -> if(rs.next()) rs.getLong(1) to rs.getString(2) else null }
        }
        val offerId: Long
        if (existing == null) {
            val code = "KQO-" + UUID.randomUUID().toString().replace("-","").take(20).uppercase()
            offerId = connection.prepareStatement("""INSERT INTO ofertas_qr_comerciales(offer_type,source_id,business_id,name,description,price_usd,cover_path,status,stock_limit,one_purchase_per_user,public_code,created_by)
                VALUES (?,?,?,?,?,?,?,'ACTIVE',NULL,TRUE,?,?) RETURNING id""").use { st ->
                st.setString(1,type); st.setLong(2,sourceId); st.setLong(3,businessId); st.setString(4,name); st.setString(5,description); st.setBigDecimal(6,price); st.setString(7,coverPath); st.setString(8,code); st.setLong(9,actorId); st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            audit(connection,actorId,"COMMERCIAL_QR_AUTO_CREATED","COMMERCIAL_QR_OFFER",offerId.toString(),"$type · $name")
        } else {
            offerId = existing.first
            val nextStatus = when (existing.second) {
                "DISABLED","SOLD_OUT","FINISHED" -> existing.second
                else -> "ACTIVE"
            }
            connection.prepareStatement("UPDATE ofertas_qr_comerciales SET business_id=?,name=?,description=?,price_usd=?,cover_path=?,status=?,updated_at=NOW() WHERE id=?").use { st ->
                st.setLong(1,businessId); st.setString(2,name); st.setString(3,description); st.setBigDecimal(4,price); st.setString(5,coverPath); st.setString(6,nextStatus); st.setLong(7,offerId); st.executeUpdate()
            }
        }

        val pending = connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM compras_qr_comerciales WHERE offer_id=? AND status IN ('AWAITING_CONFIRMATION','AWAITING_INITIAL') AND expires_at>NOW())").use { st ->
            st.setLong(1,offerId); st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
        if (!pending) {
            connection.prepareStatement("DELETE FROM items_oferta_qr WHERE offer_id=?").use { st -> st.setLong(1,offerId); st.executeUpdate() }
            connection.prepareStatement("INSERT INTO items_oferta_qr(offer_id,product_id,quantity,unit_price_usd) VALUES (?,?,?,?)").use { st ->
                lines.forEach { (productId,quantity,unitPrice) ->
                    st.setLong(1,offerId); st.setLong(2,productId); st.setInt(3,quantity); st.setBigDecimal(4,unitPrice.setScale(2,RoundingMode.HALF_EVEN)); st.addBatch()
                }
                st.executeBatch()
            }
        }
        return offerId
    }

    fun saveCommercialQrOffer(actorId: Long, offerId: Long?, request: SaveCommercialQrOfferRequest): CommercialQrOfferDto = database.transaction { connection ->
        val type = request.offerType.trim().uppercase()
        if (type !in setOf("JOURNEY","COMBO","OFFER")) throw AppException("Tipo de oferta QR inválido.")
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val starts = request.startsAt?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrElse { throw AppException("Fecha de inicio inválida.") } }
        val ends = request.endsAt?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrElse { throw AppException("Fecha de fin inválida.") } }
        if (starts != null && ends != null && ends.isBefore(starts)) throw AppException("La fecha de fin no puede ser anterior al inicio.")
        if (request.stockLimit != null && request.stockLimit <= 0) throw AppException("El cupo debe ser mayor que cero.")

        var businessId = request.businessId
        var name = request.name.trim()
        var description = request.description.trim()
        var coverPath: String? = null
        var price = request.priceUsd?.toBigDecimal()?.setScale(2, RoundingMode.HALF_EVEN)
        val lines = mutableListOf<Triple<Long,Int,BigDecimal>>()

        when (type) {
            "COMBO" -> {
                val id=request.sourceId ?: throw AppException("Selecciona el combo que generará el QR.")
                val row=connection.prepareStatement("SELECT name,description,cover_path,combo_price_usd FROM combos WHERE id=? AND active=TRUE").use { st ->
                    st.setLong(1,id); st.executeQuery().use { rs -> if(!rs.next()) throw AppException("El combo ya no está disponible."); arrayOf<Any?>(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBigDecimal(4)) }
                }
                if(name.isBlank()) name=row[0] as String
                if(description.isBlank()) description=(row[1] as String?) ?: ""
                coverPath=row[2] as String?
                if(price==null || price<=BigDecimal.ZERO) price=(row[3] as BigDecimal?)?.setScale(2,RoundingMode.HALF_EVEN)
                connection.prepareStatement("SELECT pc.product_id,pc.quantity,p.base_price_usd FROM productos_combo pc JOIN productos p ON p.id=pc.product_id AND p.active=TRUE WHERE pc.combo_id=? ORDER BY pc.product_id").use { st ->
                    st.setLong(1,id); st.executeQuery().use { rs -> while(rs.next()) lines += Triple(rs.getLong(1),rs.getInt(2),rs.getBigDecimal(3) ?: BigDecimal.ZERO) }
                }
                if (businessId == null) businessId = catalogBusinessDestination(connection, "combo_business_id") ?: catalogBusinessDestination(connection, "product_business_id") ?: singleActiveBusinessId(connection)
            }
            "JOURNEY" -> {
                val id=request.sourceId ?: throw AppException("Selecciona la jornada que generará el QR.")
                val row=connection.prepareStatement("SELECT name,description,cover_path,business_id FROM jornadas WHERE id=? AND active=TRUE").use { st ->
                    st.setLong(1,id); st.executeQuery().use { rs -> if(!rs.next()) throw AppException("La jornada ya no está disponible."); arrayOf<Any?>(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4).takeUnless { rs.wasNull() }) }
                }
                if(name.isBlank()) name=row[0] as String
                if(description.isBlank()) description=(row[1] as String?) ?: ""
                coverPath=row[2] as String?
                if(businessId==null) businessId=row[3] as Long?
                connection.prepareStatement("SELECT pj.product_id,COALESCE(pj.quantity,1),p.base_price_usd FROM productos_jornada pj JOIN productos p ON p.id=pj.product_id AND p.active=TRUE WHERE pj.fair_id=? ORDER BY pj.product_id").use { st ->
                    st.setLong(1,id); st.executeQuery().use { rs -> while(rs.next()) lines += Triple(rs.getLong(1),rs.getInt(2),rs.getBigDecimal(3) ?: BigDecimal.ZERO) }
                }
                if(price==null || price<=BigDecimal.ZERO) price=lines.fold(BigDecimal.ZERO){a,l->a+l.third.multiply(l.second.toBigDecimal())}.setScale(2,RoundingMode.HALF_EVEN)
            }
            else -> {
                if(name.isBlank()) throw AppException("Escribe un nombre para la oferta.")
                request.items.filter { it.quantity>0 }.groupBy { it.productId }.forEach { (pid, rows) ->
                    val qty=rows.sumOf { it.quantity }
                    val dbPrice=connection.prepareStatement("SELECT base_price_usd FROM productos WHERE id=? AND active=TRUE").use { st -> st.setLong(1,pid); st.executeQuery().use { rs -> if(!rs.next()) throw AppException("Uno de los productos ya no está disponible."); rs.getBigDecimal(1) ?: BigDecimal.ZERO } }
                    val unit=rows.firstNotNullOfOrNull { it.unitPriceUsd }?.toBigDecimal()?.setScale(2,RoundingMode.HALF_EVEN) ?: dbPrice
                    lines += Triple(pid,qty,unit)
                }
                if(lines.isEmpty()) throw AppException("Selecciona al menos un producto para la oferta.")
                if(price==null || price<=BigDecimal.ZERO) price=lines.fold(BigDecimal.ZERO){a,l->a+l.third.multiply(l.second.toBigDecimal())}.setScale(2,RoundingMode.HALF_EVEN)
                if (businessId == null) businessId = catalogBusinessDestination(connection, "product_business_id") ?: singleActiveBusinessId(connection)
            }
        }
        if (lines.isEmpty()) throw AppException("La oferta no tiene productos disponibles.")
        val resolvedBusinessId=businessId ?: throw AppException("Selecciona el negocio que recibirá la compra.")
        val businessActive=connection.prepareStatement("SELECT active FROM negocios_asociados WHERE id=? FOR SHARE").use { st -> st.setLong(1,resolvedBusinessId); st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
        if(!businessActive) throw AppException("El negocio seleccionado no está activo.")
        val resolvedPrice=price?.takeIf { it>BigDecimal.ZERO } ?: throw AppException("El precio de la oferta debe ser mayor que cero.")
        val desiredStatus = if (request.published) {
            when { ends!=null && ends.isBefore(now) -> "FINISHED"; starts!=null && starts.isAfter(now) -> "SCHEDULED"; else -> "ACTIVE" }
        } else "DRAFT"
        val code = if(offerId==null) "KQO-" + UUID.randomUUID().toString().replace("-","").take(20).uppercase() else null
        val resolvedId = if(offerId==null) {
            connection.prepareStatement("""INSERT INTO ofertas_qr_comerciales(offer_type,source_id,business_id,name,description,price_usd,cover_path,status,stock_limit,one_purchase_per_user,starts_at,ends_at,public_code,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id""").use { st ->
                st.setString(1,type); if(request.sourceId==null) st.setNull(2,java.sql.Types.BIGINT) else st.setLong(2,request.sourceId); st.setLong(3,resolvedBusinessId); st.setString(4,name); st.setString(5,description); st.setBigDecimal(6,resolvedPrice); st.setString(7,coverPath); st.setString(8,desiredStatus); if(request.stockLimit==null) st.setNull(9,java.sql.Types.INTEGER) else st.setInt(9,request.stockLimit); st.setBoolean(10,request.onePurchasePerUser); if(starts==null) st.setNull(11,java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else st.setObject(11,starts); if(ends==null) st.setNull(12,java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else st.setObject(12,ends); st.setString(13,code); st.setLong(14,actorId); st.executeQuery().use { rs->rs.next();rs.getLong(1) }
            }
        } else {
            val changed=connection.prepareStatement("""UPDATE ofertas_qr_comerciales SET offer_type=?,source_id=?,business_id=?,name=?,description=?,price_usd=?,cover_path=COALESCE(?,cover_path),status=?,stock_limit=?,one_purchase_per_user=?,starts_at=?,ends_at=?,updated_at=NOW() WHERE id=?""").use { st ->
                st.setString(1,type); if(request.sourceId==null) st.setNull(2,java.sql.Types.BIGINT) else st.setLong(2,request.sourceId); st.setLong(3,resolvedBusinessId); st.setString(4,name); st.setString(5,description); st.setBigDecimal(6,resolvedPrice); st.setString(7,coverPath); st.setString(8,desiredStatus); if(request.stockLimit==null) st.setNull(9,java.sql.Types.INTEGER) else st.setInt(9,request.stockLimit); st.setBoolean(10,request.onePurchasePerUser); if(starts==null) st.setNull(11,java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else st.setObject(11,starts); if(ends==null) st.setNull(12,java.sql.Types.TIMESTAMP_WITH_TIMEZONE) else st.setObject(12,ends); st.setLong(13,offerId); st.executeUpdate()
            }; if(changed!=1) throw NotFoundException("Oferta QR no encontrada."); offerId
        }
        connection.prepareStatement("DELETE FROM items_oferta_qr WHERE offer_id=?").use { st->st.setLong(1,resolvedId);st.executeUpdate() }
        connection.prepareStatement("INSERT INTO items_oferta_qr(offer_id,product_id,quantity,unit_price_usd) VALUES (?,?,?,?)").use { st ->
            lines.forEach { (pid,qty,unit) -> st.setLong(1,resolvedId);st.setLong(2,pid);st.setInt(3,qty);st.setBigDecimal(4,unit.setScale(2,RoundingMode.HALF_EVEN));st.addBatch() }; st.executeBatch()
        }
        audit(connection,actorId,if(offerId==null) "COMMERCIAL_QR_OFFER_CREATED" else "COMMERCIAL_QR_OFFER_UPDATED","COMMERCIAL_QR_OFFER",resolvedId.toString(),"$type · $name · US$ $resolvedPrice")
        commercialQrOfferDto(connection,resolvedId)
    }

    fun setCommercialQrOfferStatus(actorId: Long, offerId: Long, statusRaw: String): CommercialQrOfferDto = database.transaction { connection ->
        val status=statusRaw.trim().uppercase()
        if(status !in setOf("DRAFT","SCHEDULED","ACTIVE","SOLD_OUT","FINISHED","DISABLED")) throw AppException("Estado de oferta inválido.")
        val changed=connection.prepareStatement("UPDATE ofertas_qr_comerciales SET status=?,updated_at=NOW() WHERE id=?").use { st->st.setString(1,status);st.setLong(2,offerId);st.executeUpdate() }
        if(changed!=1) throw NotFoundException("Oferta QR no encontrada.")
        audit(connection,actorId,"COMMERCIAL_QR_OFFER_STATUS","COMMERCIAL_QR_OFFER",offerId.toString(),status)
        commercialQrOfferDto(connection,offerId)
    }

    fun startCommercialQrPurchase(userId: Long, request: QrScanRequest): QrSaleSessionDto = database.transaction { connection ->
        expireCommercialQrPurchases(connection); refreshCommercialOfferStates(connection)
        val code=normalizeQrCode(request.code)
        val offerId=connection.prepareStatement("""SELECT o.id FROM ofertas_qr_comerciales o JOIN negocios_asociados b ON b.id=o.business_id
            WHERE o.public_code=? AND o.status='ACTIVE' AND b.active=TRUE AND (o.starts_at IS NULL OR o.starts_at<=NOW()) AND (o.ends_at IS NULL OR o.ends_at>=NOW()) FOR SHARE""").use { st -> st.setString(1,code);st.executeQuery().use { rs->if(rs.next())rs.getLong(1) else throw NotFoundException("Esta oferta ya no está disponible.") } }
        // Serializa escaneos de la misma oferta para que cupos y compra única no tengan carreras.
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use { st -> st.setString(1,"commerce-offer:$offerId");st.execute() }
        val offer=commercialQrOfferDto(connection,offerId)
        val pendingReservations=connection.prepareStatement("SELECT COUNT(*) FROM compras_qr_comerciales WHERE offer_id=? AND status IN ('AWAITING_CONFIRMATION','AWAITING_INITIAL') AND expires_at>NOW()").use { st->st.setLong(1,offerId);st.executeQuery().use { rs->rs.next();rs.getInt(1) } }
        if(offer.stockLimit!=null && offer.soldCount+pendingReservations>=offer.stockLimit) throw AppException("Esta oferta está agotada o todos sus cupos están reservados temporalmente.")
        if(offer.onePurchasePerUser){
            val used=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM compras_qr_comerciales WHERE offer_id=? AND user_id=? AND status IN ('AWAITING_CONFIRMATION','AWAITING_INITIAL','COMPLETED') AND (status='COMPLETED' OR expires_at>NOW()))").use { st->st.setLong(1,offerId);st.setLong(2,userId);st.executeQuery().use { rs->rs.next()&&rs.getBoolean(1) } }
            if(used) throw AppException("Ya utilizaste esta oferta. Esta jornada permite una sola compra por usuario.")
        }
        val productCapacity=connection.prepareStatement("SELECT MIN(FLOOR(p.stock::numeric / i.quantity))::int FROM items_oferta_qr i JOIN productos p ON p.id=i.product_id WHERE i.offer_id=?").use { st->st.setLong(1,offerId);st.executeQuery().use { rs->if(rs.next())rs.getInt(1).takeUnless { rs.wasNull() } else null } }
        if(productCapacity!=null && productCapacity<=0) throw AppException("Uno de los productos de esta oferta está agotado.")
        ensureCreditAccount(connection,userId); lockCreditAccount(connection,userId); refreshCreditStatuses(connection,userId)
        val progress=refreshCredimpulsoLevel(connection,userId); val account=creditAccountSnapshot(connection,userId)
        if(!account.status.equals("ACTIVE",true)) throw ForbiddenException("Tu línea Kredi+ no está activa para comprar.")
        val total=offer.priceUsd.toBigDecimal().setScale(2,RoundingMode.HALF_EVEN)
        val ruleInitial=total.multiply(progress.current.downPaymentPercent.toBigDecimal()).divide(BigDecimal("100"),2,RoundingMode.HALF_EVEN)
        val available=account.availableUsd.toBigDecimal().setScale(2,RoundingMode.HALF_EVEN)
        val lineRequiredInitial=(total-available).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_EVEN)
        val initial=ruleInitial.max(lineRequiredInitial).min(total).setScale(2,RoundingMode.HALF_EVEN)
        val financed=(total-initial).setScale(2,RoundingMode.HALF_EVEN)
        if(financed>available) throw AppException("Tu línea disponible no alcanza para esta oferta.")
        if(financed<=BigDecimal.ZERO && initial<total) throw AppException("No fue posible calcular el plan de compra.")
        val installments=if(financed<=BigDecimal.ZERO) 0 else account.preferredInstallments.coerceIn(2,progress.current.maxInstallments)
        val regular=if(installments==0) BigDecimal.ZERO else MoneyMath.splitExact(financed,installments,MoneyMath.USD_SCALE).first()
        val pct=initial.multiply(BigDecimal("100")).divide(total,2,RoundingMode.HALF_EVEN)
        val itemsSnapshot=offer.items.joinToString(" | "){ "${it.quantity}x ${it.name}" }
        val identity=connection.prepareStatement("""SELECT COALESCE(NULLIF(up.full_name,''),u.username),vd.document_number
            FROM usuarios u
            LEFT JOIN perfiles_usuario up ON up.user_id=u.id
            LEFT JOIN verificaciones_documentos vd ON vd.user_id=u.id
            WHERE u.id=? ORDER BY vd.updated_at DESC NULLS LAST LIMIT 1""").use { st ->
            st.setLong(1,userId)
            st.executeQuery().use { rs ->
                if(rs.next()) Pair(rs.getString(1).orEmpty().ifBlank { "Usuario Kredi+" },rs.getString(2))
                else Pair("Usuario Kredi+",null)
            }
        }
        val id=connection.prepareStatement("""INSERT INTO compras_qr_comerciales(offer_id,business_id,user_id,total_usd,level_snapshot,level_name_snapshot,available_line_snapshot,initial_percent,initial_usd,financed_usd,installment_count,installment_usd,offer_name_snapshot,business_name_snapshot,user_name_snapshot,user_document_snapshot,items_snapshot,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW()+INTERVAL '15 minutes') RETURNING id""").use { st ->
            st.setLong(1,offer.id);st.setLong(2,offer.businessId);st.setLong(3,userId);st.setBigDecimal(4,total);st.setInt(5,progress.current.level);st.setString(6,progress.current.name);st.setBigDecimal(7,available);st.setBigDecimal(8,pct);st.setBigDecimal(9,initial);st.setBigDecimal(10,financed);st.setInt(11,installments);st.setBigDecimal(12,regular);st.setString(13,offer.name);st.setString(14,offer.businessName);st.setString(15,identity.first);st.setString(16,identity.second);st.setString(17,itemsSnapshot);st.executeQuery().use { rs->rs.next();rs.getLong(1) }
        }
        audit(connection,userId,"COMMERCIAL_QR_SCANNED","COMMERCIAL_QR_PURCHASE",id.toString(),"${offer.offerType} · ${offer.name}")
        commercialQrPurchaseDto(connection,id,userId,false)
    }

    fun commercialQrPurchaseSession(userId: Long, id: Long, admin: Boolean): QrSaleSessionDto = database.transaction { connection ->
        expireCommercialQrPurchases(connection); commercialQrPurchaseDto(connection,id,userId,admin)
    }

    fun confirmCommercialQrPurchase(userId: Long, id: Long): QrSaleSessionDto {
        val dto=database.transaction { connection ->
            expireCommercialQrPurchases(connection)
            val initial=connection.prepareStatement("SELECT initial_usd FROM compras_qr_comerciales WHERE id=? AND user_id=? AND status='AWAITING_CONFIRMATION' FOR UPDATE").use { st->st.setLong(1,id);st.setLong(2,userId);st.executeQuery().use { rs->if(rs.next())rs.getBigDecimal(1) else throw AppException("La compra ya cambió de estado. Actualiza la pantalla.") } }
            connection.prepareStatement("UPDATE compras_qr_comerciales SET status='AWAITING_INITIAL',confirmed_at=NOW(),expires_at=NOW()+INTERVAL '20 minutes',updated_at=NOW() WHERE id=?").use { st->st.setLong(1,id);st.executeUpdate() }
            if(initial.compareTo(BigDecimal.ZERO)==0){ finalizeCommercialQrPurchase(connection,userId,id,true) }
            commercialQrPurchaseDto(connection,id,userId,false)
        }
        if(dto.status=="AWAITING_INITIAL") notifyAdmins("Inicial por confirmar","${dto.customerName} confirmó ${dto.offerName ?: "una compra QR"} en ${dto.businessName}. El comercio debe confirmar la inicial de US$ ${"%.2f".format(dto.initialUsd ?: 0.0)}.","COMMERCIAL_QR_AWAITING_INITIAL",mapOf("purchaseId" to id.toString()))
        else if(dto.status=="COMPLETED") notifyUsers(listOf(userId),"Compra Kredi+ aprobada","${dto.offerName ?: "Tu compra"} fue aprobada y tu plan quedó registrado.","COMMERCIAL_QR_PURCHASE_COMPLETED",mapOf("purchaseId" to id.toString()))
        return dto
    }

    fun completeCommercialQrPurchase(actorId: Long, id: Long): QrSaleSessionDto {
        var notifyUserId = 0L
        var businessName = ""
        var initialText = "0.00"
        val dto = database.transaction { connection ->
            connection.prepareStatement("""SELECT p.user_id,b.commercial_name,p.initial_usd
                FROM compras_qr_comerciales p JOIN negocios_asociados b ON b.id=p.business_id WHERE p.id=?""").use { st ->
                st.setLong(1,id)
                st.executeQuery().use { rs ->
                    if(!rs.next()) throw NotFoundException("Compra QR no encontrada.")
                    notifyUserId=rs.getLong(1);businessName=rs.getString(2).orEmpty();initialText=(rs.getBigDecimal(3)?:BigDecimal.ZERO).setScale(2,RoundingMode.HALF_EVEN).toPlainString()
                }
            }
            finalizeCommercialQrPurchase(connection,actorId,id,false)
            commercialQrPurchaseDto(connection,id,actorId,true)
        }
        if(notifyUserId>0) notifyUsers(listOf(notifyUserId),"Compra Kredi+ aprobada","$businessName confirmó tu inicial de US$ $initialText. Tu plan de cuotas ya está activo.","COMMERCIAL_QR_PURCHASE_COMPLETED",mapOf("purchaseId" to id.toString()))
        return dto
    }

    fun myCommercialQrPurchases(userId: Long): List<QrSaleSessionDto> = database.transaction { connection ->
        expireCommercialQrPurchases(connection)
        connection.prepareStatement("SELECT id FROM compras_qr_comerciales WHERE user_id=? AND status IN ('AWAITING_CONFIRMATION','AWAITING_INITIAL','COMPLETED') ORDER BY created_at DESC LIMIT 100").use { st->st.setLong(1,userId);st.executeQuery().use { rs->buildList { while(rs.next())add(commercialQrPurchaseDto(connection,rs.getLong(1),userId,false)) } } }
    }

    fun adminCommercialQrPurchases(statusRaw: String?): List<QrSaleSessionDto> = database.transaction { connection ->
        expireCommercialQrPurchases(connection)
        val status=statusRaw?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it!="ALL" }
        val sql=if(status==null) "SELECT id FROM compras_qr_comerciales ORDER BY created_at DESC LIMIT 300" else "SELECT id FROM compras_qr_comerciales WHERE status=? ORDER BY created_at DESC LIMIT 300"
        connection.prepareStatement(sql).use { st->if(status!=null)st.setString(1,status);st.executeQuery().use { rs->buildList { while(rs.next())add(commercialQrPurchaseDto(connection,rs.getLong(1),0,true)) } } }
    }

    private fun finalizeCommercialQrPurchase(connection: Connection, actorId: Long, id: Long, allowUserZeroInitial: Boolean) {
        val role=runCatching { currentRole(actorId) }.getOrDefault("")
        val row=connection.prepareStatement("""SELECT p.user_id,p.offer_id,p.business_id,p.total_usd,p.level_snapshot,p.level_name_snapshot,p.initial_usd,p.financed_usd,p.installment_count,p.status,o.stock_limit,o.sold_count,o.one_purchase_per_user,o.name,b.commercial_name
            FROM compras_qr_comerciales p JOIN ofertas_qr_comerciales o ON o.id=p.offer_id JOIN negocios_asociados b ON b.id=p.business_id WHERE p.id=? FOR UPDATE""").use { st->st.setLong(1,id);st.executeQuery().use { rs->if(!rs.next())throw NotFoundException("Compra QR no encontrada.");
            arrayOf<Any?>(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getBigDecimal(4),rs.getInt(5),rs.getString(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getInt(9),rs.getString(10),rs.getObject(11),rs.getInt(12),rs.getBoolean(13),rs.getString(14),rs.getString(15)) } }
        val userId=row[0] as Long; val offerId=row[1] as Long; val businessId=row[2] as Long; val total=row[3] as BigDecimal; val level=row[4] as Int; val levelName=row[5] as String; val initial=row[6] as BigDecimal; val financed=row[7] as BigDecimal; val installments=row[8] as Int; val status=row[9] as String; val stockLimit=(row[10] as? Number)?.toInt(); val sold=row[11] as Int; val offerName=row[13] as String; val businessName=row[14] as String
        if(status!="AWAITING_INITIAL") throw AppException("La compra no está esperando la confirmación de la inicial.")
        if(!allowUserZeroInitial && role!="ADMIN" && role!="ACCOUNTANT") throw ForbiddenException("Solo Administración puede confirmar la inicial recibida por el comercio.")
        if(allowUserZeroInitial && (actorId!=userId || initial>BigDecimal.ZERO)) throw ForbiddenException()
        if(stockLimit!=null && sold>=stockLimit) throw AppException("La oferta se agotó antes de completar la compra.")
        ensureCreditAccount(connection,userId);lockCreditAccount(connection,userId);refreshCreditStatuses(connection,userId)
        val account=creditAccountSnapshot(connection,userId); if(financed>account.availableUsd.toBigDecimal().setScale(2,RoundingMode.HALF_EVEN)) throw AppException("Tu línea disponible cambió. Vuelve a escanear el QR.")
        val itemRows=connection.prepareStatement("SELECT i.product_id,i.quantity,p.name,p.stock FROM items_oferta_qr i JOIN productos p ON p.id=i.product_id WHERE i.offer_id=? ORDER BY i.product_id FOR UPDATE OF p").use { st->st.setLong(1,offerId);st.executeQuery().use { rs->buildList { while(rs.next())add(arrayOf<Any?>(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getInt(4))) } } }
        itemRows.forEach { r-> if((r[3] as Int)<(r[1] as Int)) throw AppException("No hay existencias suficientes de ${r[2]}.") }
        itemRows.forEach { r->connection.prepareStatement("UPDATE productos SET stock=stock-?,updated_at=NOW() WHERE id=?").use { st->st.setInt(1,r[1] as Int);st.setLong(2,r[0] as Long);st.executeUpdate() } }
        val rate=MoneyMath.rate(bcvRateService.currentUsdRate().rate); val principalBs=financed.multiply(rate).setScale(MoneyMath.VES_SCALE,RoundingMode.HALF_EVEN)
        val invoice="KQR-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}-${id.toString().padStart(6,'0')}"
        var loanId:Long?=null
        if(financed>BigDecimal.ZERO){
            loanId=connection.prepareStatement("""INSERT INTO prestamos_credito(user_id,order_id,invoice_number,level,principal_usd,principal_bs,bcv_rate,installment_count,status,lender_type,lender_business_id,repayment_business_id,disbursement_destination_type,repayment_payment_mode,repayment_business_commercial_name,repayment_business_legal_name,repayment_business_rif,repayment_business_logo_path,repayment_mobile_bank,repayment_mobile_phone,repayment_mobile_identity_number,repayment_mobile_holder_name,repayment_bank_name,repayment_bank_account_type,repayment_bank_account_number,repayment_bank_identity_number,repayment_bank_holder_name)
                SELECT ?,NULL,?,?,?, ?,?,?, 'ACTIVE','MERCHANT_PURCHASE',b.id,b.id,'IN_STORE_PURCHASE',b.payment_mode,b.commercial_name,b.legal_name,b.rif,b.logo_path,b.mobile_bank,b.mobile_phone,b.mobile_identity_number,b.mobile_holder_name,b.bank_name,b.bank_account_type,b.bank_account_number,b.bank_identity_number,b.bank_holder_name FROM negocios_asociados b WHERE b.id=? RETURNING id""").use { st->st.setLong(1,userId);st.setString(2,invoice);st.setInt(3,level);st.setBigDecimal(4,financed);st.setBigDecimal(5,principalBs);st.setBigDecimal(6,rate);st.setInt(7,installments);st.setLong(8,businessId);st.executeQuery().use { rs->rs.next();rs.getLong(1) } }
            val usdParts=MoneyMath.splitExact(financed,installments,MoneyMath.USD_SCALE); val bsParts=MoneyMath.splitExact(principalBs,installments,MoneyMath.VES_SCALE)
            for(i in 1..installments) connection.prepareStatement("INSERT INTO cuotas_credito(loan_id,installment_number,amount_usd,original_amount_bs,due_date,status) VALUES (?,?,?,?,?,'PENDING')").use { st->st.setLong(1,loanId);st.setInt(2,i);st.setBigDecimal(3,usdParts[i-1]);st.setBigDecimal(4,bsParts[i-1]);st.setObject(5,LocalDate.now().plusDays(14L*i));st.executeUpdate() }
            val after=creditAccountSnapshot(connection,userId).availableUsd.coerceAtLeast(0.0)
            recordCredimpulsoTransaction(connection=connection,userId=userId,type="PURCHASE",amountUsd=financed.toDouble(),amountBs=principalBs.toDouble(),bcvRate=rate.toDouble(),balanceBeforeUsd=after+financed.toDouble(),balanceAfterUsd=after,description="Compra QR $invoice · $offerName · $businessName",loanId=loanId,performedBy=userId)
        }
        connection.prepareStatement("UPDATE compras_qr_comerciales SET status='COMPLETED',loan_id=?,initial_received_at=NOW(),updated_at=NOW() WHERE id=?").use { st->if(loanId==null)st.setNull(1,java.sql.Types.BIGINT)else st.setLong(1,loanId);st.setLong(2,id);st.executeUpdate() }
        connection.prepareStatement("UPDATE ofertas_qr_comerciales SET sold_count=sold_count+1,status=CASE WHEN stock_limit IS NOT NULL AND sold_count+1>=stock_limit THEN 'SOLD_OUT' ELSE status END,updated_at=NOW() WHERE id=?").use { st->st.setLong(1,offerId);st.executeUpdate() }
        audit(connection,actorId,"COMMERCIAL_QR_PURCHASE_COMPLETED","COMMERCIAL_QR_PURCHASE",id.toString(),"$invoice · $offerName · total US$ $total · inicial US$ $initial · nivel $levelName")
    }

    private fun refreshCommercialOfferStates(connection: Connection){
        connection.prepareStatement("UPDATE ofertas_qr_comerciales SET status='ACTIVE',updated_at=NOW() WHERE status='SCHEDULED' AND (starts_at IS NULL OR starts_at<=NOW()) AND (ends_at IS NULL OR ends_at>=NOW())").use { it.executeUpdate() }
        connection.prepareStatement("UPDATE ofertas_qr_comerciales SET status='FINISHED',updated_at=NOW() WHERE status IN ('ACTIVE','SCHEDULED') AND ends_at IS NOT NULL AND ends_at<NOW()").use { it.executeUpdate() }
        connection.prepareStatement("UPDATE ofertas_qr_comerciales SET status='SOLD_OUT',updated_at=NOW() WHERE status='ACTIVE' AND stock_limit IS NOT NULL AND sold_count>=stock_limit").use { it.executeUpdate() }
    }
    private fun expireCommercialQrPurchases(connection: Connection){ connection.prepareStatement("UPDATE compras_qr_comerciales SET status='EXPIRED',updated_at=NOW() WHERE status IN ('AWAITING_CONFIRMATION','AWAITING_INITIAL') AND expires_at<NOW()").use { it.executeUpdate() } }

    private fun commercialQrOfferDto(connection: Connection,id:Long):CommercialQrOfferDto{
        val base=connection.prepareStatement("""SELECT o.id,o.offer_type,o.source_id,o.business_id,b.commercial_name,o.name,o.description,o.price_usd,o.cover_path,o.status,o.stock_limit,o.sold_count,o.one_purchase_per_user,o.starts_at,o.ends_at,o.public_code FROM ofertas_qr_comerciales o JOIN negocios_asociados b ON b.id=o.business_id WHERE o.id=?""").use { st->st.setLong(1,id);st.executeQuery().use { rs->if(!rs.next())throw NotFoundException("Oferta QR no encontrada."); arrayOf<Any?>(rs.getLong(1),rs.getString(2),rs.getLong(3).takeUnless{rs.wasNull()},rs.getLong(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getBigDecimal(8),rs.getString(9),rs.getString(10),rs.getObject(11)?.let{rs.getInt(11)},rs.getInt(12),rs.getBoolean(13),rs.getObject(14,OffsetDateTime::class.java),rs.getObject(15,OffsetDateTime::class.java),rs.getString(16)) } }
        val items=connection.prepareStatement("""SELECT i.product_id,p.name,i.quantity,i.unit_price_usd,
                   (SELECT pj.image_path FROM productos_jornada pj
                    WHERE pj.product_id=p.id AND pj.image_path IS NOT NULL AND BTRIM(pj.image_path)<>''
                    ORDER BY pj.fair_id DESC LIMIT 1) AS image_path
            FROM items_oferta_qr i
            JOIN productos p ON p.id=i.product_id
            WHERE i.offer_id=?
            ORDER BY p.name""".trimIndent()).use { st->
            st.setLong(1,id)
            st.executeQuery().use { rs->buildList {
                while(rs.next()) add(CommercialQrItemDto(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getInt(3),
                    (rs.getBigDecimal(4)?:BigDecimal.ZERO).toDouble(),
                    publicUrl(rs.getString(5))
                ))
            } }
        }
        val limit=base[10] as Int?; val sold=base[11] as Int; val code=base[15] as String
        return CommercialQrOfferDto(base[0] as Long,base[1] as String,base[2] as Long?,base[3] as Long,base[4] as String,base[5] as String,base[6] as String,(base[7] as BigDecimal).toDouble(),publicUrl(base[8] as String?),base[9] as String,limit,sold,limit?.let{(it-sold).coerceAtLeast(0)},base[12] as Boolean,(base[13] as OffsetDateTime?)?.toString(),(base[14] as OffsetDateTime?)?.toString(),code,"krediplus://offer?code=$code","${config.publicBaseUrl.trimEnd('/')}/qr/$code.png",items)
    }

    private fun commercialQrPurchaseDto(connection:Connection,id:Long,userId:Long,admin:Boolean):QrSaleSessionDto =
        connection.prepareStatement("""SELECT p.id,p.status,p.business_id,b.commercial_name,COALESCE(NULLIF(p.user_name_snapshot,''),up.full_name,u.username),p.total_usd,
                   p.level_snapshot,p.level_name_snapshot,p.available_line_snapshot,p.initial_percent,p.initial_usd,p.financed_usd,p.installment_count,p.installment_usd,p.loan_id,
                   p.created_at,p.expires_at,o.id,o.offer_type,o.name,o.cover_path,o.stock_limit,o.sold_count,o.one_purchase_per_user
            FROM compras_qr_comerciales p
            JOIN ofertas_qr_comerciales o ON o.id=p.offer_id
            JOIN negocios_asociados b ON b.id=p.business_id
            JOIN usuarios u ON u.id=p.user_id
            LEFT JOIN perfiles_usuario up ON up.user_id=u.id
            WHERE p.id=? AND (? OR p.user_id=?)""").use { st ->
            st.setLong(1,id);st.setBoolean(2,admin);st.setLong(3,userId)
            st.executeQuery().use { rs ->
                if(!rs.next()) throw NotFoundException("Compra QR no encontrada.")
                val offerId=rs.getLong(18)
                val offer=commercialQrOfferDto(connection,offerId)
                val status=rs.getString(2)
                val limit=rs.getObject(22)?.let{rs.getInt(22)}
                val sold=rs.getInt(23)
                val pending=connection.prepareStatement("SELECT COUNT(*) FROM compras_qr_comerciales WHERE offer_id=? AND status IN ('AWAITING_CONFIRMATION','AWAITING_INITIAL') AND expires_at>NOW()").use { p ->
                    p.setLong(1,offerId);p.executeQuery().use { q->q.next();q.getInt(1) }
                }
                val remaining=limit?.let{(it-sold-pending).coerceAtLeast(0)}
                val msg=when(status){
                    "AWAITING_CONFIRMATION"->"Revisa la oferta y confirma tu compra."
                    "AWAITING_INITIAL"->"Compra registrada. Paga la inicial directamente al comercio para activar tus cuotas."
                    "COMPLETED"->"Compra aprobada. Tus cuotas ya están activas."
                    "EXPIRED"->"La reserva venció. Escanea nuevamente el QR."
                    "CANCELLED"->"La compra fue cancelada."
                    else->"Compra QR Kredi+."
                }
                QrSaleSessionDto(
                    id=rs.getLong(1),status=status,businessId=rs.getLong(3),businessName=rs.getString(4),
                    branchName="Oferta comercial",terminalName=rs.getString(19),customerName=rs.getString(5).orEmpty(),
                    totalUsd=rs.getBigDecimal(6)?.toDouble(),invoiceReference=null,level=rs.getInt(7),levelName=rs.getString(8),
                    availableLineUsd=rs.getBigDecimal(9)?.toDouble(),initialPercent=rs.getBigDecimal(10)?.toDouble(),initialUsd=rs.getBigDecimal(11)?.toDouble(),
                    financedUsd=rs.getBigDecimal(12)?.toDouble(),installmentCount=rs.getInt(13),installmentUsd=rs.getBigDecimal(14)?.toDouble(),
                    loanId=rs.getObject(15)?.let{rs.getLong(15)},createdAt=rs.getObject(16,OffsetDateTime::class.java).toString(),expiresAt=rs.getObject(17,OffsetDateTime::class.java).toString(),
                    message=msg,offerType=rs.getString(19),offerId=offerId,offerName=rs.getString(20),coverUrl=publicUrl(rs.getString(21)),items=offer.items,
                    stockRemaining=remaining,onePurchasePerUser=rs.getBoolean(24),commerceFlow=true
                )
            }
        }

    fun startQrPurchase(userId: Long, request: QrScanRequest): QrSaleSessionDto = database.transaction { connection ->
        expireQrSessions(connection)
        val code = normalizeQrCode(request.code)
        val terminalId = connection.prepareStatement(
            """SELECT t.id FROM terminales_qr_negocio t
               JOIN negocios_asociados b ON b.id=t.business_id
               WHERE t.public_code=? AND t.active=TRUE AND b.active=TRUE"""
        ).use { st -> st.setString(1, code); st.executeQuery().use { rs -> if(rs.next()) rs.getLong(1) else throw NotFoundException("Este QR Kredi+ no está activo.") } }
        val terminal = findQrTerminal(connection, terminalId)
        ensureCreditAccount(connection, userId)
        refreshCreditStatuses(connection, userId)
        val account = creditAccountSnapshot(connection, userId)
        if (!account.status.equals("ACTIVE", true)) throw ForbiddenException("Tu línea Kredi+ no está activa para comprar.")

        connection.prepareStatement(
            """UPDATE sesiones_compra_qr SET status='CANCELLED',updated_at=NOW()
               WHERE user_id=? AND status IN ('AWAITING_MERCHANT','AWAITING_CUSTOMER','AWAITING_INITIAL')"""
        ).use { it.setLong(1,userId); it.executeUpdate() }
        val sessionId = connection.prepareStatement(
            """INSERT INTO sesiones_compra_qr(terminal_id,business_id,user_id,status,expires_at)
               VALUES (?,?,?,'AWAITING_MERCHANT',NOW()+INTERVAL '15 minutes') RETURNING id"""
        ).use { st ->
            st.setLong(1,terminal.id); st.setLong(2,terminal.businessId); st.setLong(3,userId)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        qrSessionDto(connection, sessionId, userId, false)
    }

    fun qrPurchaseSession(userId: Long, sessionId: Long): QrSaleSessionDto = database.transaction { connection ->
        expireQrSessions(connection)
        qrSessionDto(connection, sessionId, userId, false)
    }

    fun myQrPurchases(userId: Long): List<QrSaleSessionDto> = database.transaction { connection ->
        expireQrSessions(connection)
        connection.prepareStatement("SELECT id FROM sesiones_compra_qr WHERE user_id=? AND status='COMPLETED' ORDER BY created_at DESC LIMIT 100").use { st ->
            st.setLong(1,userId)
            st.executeQuery().use { rs -> buildList { while(rs.next()) add(qrSessionDto(connection,rs.getLong(1),userId,false)) } }
        }
    }

    fun adminQrSessions(status: String?): List<QrSaleSessionDto> = database.transaction { connection ->
        expireQrSessions(connection)
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val sql = buildString {
            append("SELECT id,user_id FROM sesiones_compra_qr")
            if (normalized != null) append(" WHERE status=?")
            append(" ORDER BY created_at DESC LIMIT 150")
        }
        connection.prepareStatement(sql).use { st ->
            if (normalized != null) st.setString(1, normalized)
            st.executeQuery().use { rs -> buildList { while(rs.next()) add(qrSessionDto(connection, rs.getLong(1), rs.getLong(2), true)) } }
        }
    }

    fun quoteQrPurchase(actorId: Long, sessionId: Long, request: QrSaleQuoteRequest): QrSaleSessionDto = database.transaction { connection ->
        if (currentRole(actorId) != "ADMIN") throw ForbiddenException("Solo Administración puede preparar una compra QR.")
        expireQrSessions(connection)
        val userId = lockQrSessionUserForQuote(connection, sessionId)
        val total = request.totalUsd.toBigDecimal().setScale(2, RoundingMode.HALF_EVEN)
        if (total <= BigDecimal.ZERO) throw AppException("El total de la compra debe ser mayor que cero.")

        ensureCreditAccount(connection, userId)
        lockCreditAccount(connection, userId)
        refreshCreditStatuses(connection, userId)
        val progress = refreshCredimpulsoLevel(connection, userId)
        val account = creditAccountSnapshot(connection, userId)
        if (!account.status.equals("ACTIVE", true)) throw ForbiddenException("La línea de compra del cliente no está activa.")

        val ruleInitial = total.multiply(progress.current.downPaymentPercent.toBigDecimal()).divide(BigDecimal("100"), 2, RoundingMode.HALF_EVEN)
        val available = account.availableUsd.toBigDecimal().setScale(2, RoundingMode.HALF_EVEN)
        val lineRequiredInitial = (total - available).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN)
        val initial = ruleInitial.max(lineRequiredInitial).min(total).setScale(2, RoundingMode.HALF_EVEN)
        val financed = (total - initial).setScale(2, RoundingMode.HALF_EVEN)
        if (financed <= BigDecimal.ZERO) throw AppException("La compra debe utilizar al menos una parte de tu línea Kredi+.")
        if (financed > available) throw AppException("La línea disponible del cliente no alcanza para esta compra.")
        val installments = account.preferredInstallments.coerceIn(2, progress.current.maxInstallments)
        val split = MoneyMath.splitExact(financed, installments, MoneyMath.USD_SCALE)
        val regularInstallment = split.firstOrNull() ?: financed
        val effectiveInitialPercent = initial.multiply(BigDecimal("100")).divide(total, 2, RoundingMode.HALF_EVEN)
        connection.prepareStatement(
            """UPDATE sesiones_compra_qr SET status='AWAITING_CUSTOMER',total_usd=?,invoice_reference=?,level_snapshot=?,level_name_snapshot=?,
               available_line_snapshot=?,initial_percent=?,initial_usd=?,financed_usd=?,installment_count=?,installment_usd=?,customer_confirmed_at=NULL,expires_at=NOW()+INTERVAL '15 minutes',updated_at=NOW()
               WHERE id=?"""
        ).use { st ->
            st.setBigDecimal(1,total); st.setString(2,request.invoiceReference.trim().take(160)); st.setInt(3,progress.current.level)
            st.setString(4,progress.current.name); st.setBigDecimal(5,available); st.setBigDecimal(6,effectiveInitialPercent); st.setBigDecimal(7,initial)
            st.setBigDecimal(8,financed); st.setInt(9,installments); st.setBigDecimal(10,regularInstallment); st.setLong(11,sessionId); st.executeUpdate()
        }
        audit(connection, actorId, "QR_PURCHASE_QUOTED", "QR_SESSION", sessionId.toString(), "US$ $total · inicial US$ $initial · financiado US$ $financed")
        qrSessionDto(connection, sessionId, userId, true)
    }

    fun confirmQrPurchase(userId: Long, sessionId: Long): QrSaleSessionDto = database.transaction { connection ->
        expireQrSessions(connection)
        connection.prepareStatement(
            """UPDATE sesiones_compra_qr SET status='AWAITING_INITIAL',customer_confirmed_at=NOW(),expires_at=NOW()+INTERVAL '15 minutes',updated_at=NOW()
               WHERE id=? AND user_id=? AND status='AWAITING_CUSTOMER'"""
        ).use { st -> st.setLong(1,sessionId); st.setLong(2,userId); if(st.executeUpdate()!=1) throw AppException("La compra ya cambió de estado. Actualiza la pantalla.") }
        qrSessionDto(connection, sessionId, userId, false)
    }

    fun completeQrPurchase(actorId: Long, sessionId: Long): QrSaleSessionDto {
        if (currentRole(actorId) != "ADMIN") throw ForbiddenException("Solo Administración puede confirmar la inicial recibida por el comercio.")
        var notifyUserId = 0L
        var businessName = ""
        var initialText = ""
        val dto = database.transaction { connection ->
            expireQrSessions(connection)
            val row = connection.prepareStatement(
                """SELECT s.user_id,s.business_id,s.total_usd,s.invoice_reference,s.level_snapshot,s.level_name_snapshot,s.initial_usd,s.financed_usd,s.installment_count,
                          b.commercial_name,b.payment_mode,b.mobile_bank,b.mobile_phone,b.mobile_identity_number,b.mobile_holder_name,
                          b.bank_name,b.bank_account_type,b.bank_account_number,b.bank_identity_number,b.bank_holder_name
                   FROM sesiones_compra_qr s JOIN negocios_asociados b ON b.id=s.business_id
                   WHERE s.id=? AND s.status='AWAITING_INITIAL' FOR UPDATE"""
            ).use { st ->
                st.setLong(1,sessionId); st.executeQuery().use { rs -> if(!rs.next()) throw AppException("La compra no está esperando la confirmación de la inicial.")
                    QrCompletionRow(
                        userId=rs.getLong(1),businessId=rs.getLong(2),totalUsd=rs.getBigDecimal(3),invoiceReference=rs.getString(4).orEmpty(),
                        level=rs.getInt(5),levelName=rs.getString(6).orEmpty(),initialUsd=rs.getBigDecimal(7),financedUsd=rs.getBigDecimal(8),installments=rs.getInt(9),
                        businessName=rs.getString(10),paymentMode=rs.getString(11).orEmpty(),mobileBank=rs.getString(12),mobilePhone=rs.getString(13),
                        mobileIdentity=rs.getString(14),mobileHolder=rs.getString(15),bankName=rs.getString(16),bankAccountType=rs.getString(17),
                        bankAccountNumber=rs.getString(18),bankIdentity=rs.getString(19),bankHolder=rs.getString(20)
                    )
                }
            }
            notifyUserId=row.userId; businessName=row.businessName; initialText=row.initialUsd.toPlainString()
            ensureCreditAccount(connection,row.userId); lockCreditAccount(connection,row.userId); refreshCreditStatuses(connection,row.userId)
            val account=creditAccountSnapshot(connection,row.userId)
            if(row.financedUsd > account.availableUsd.toBigDecimal().setScale(2,RoundingMode.HALF_EVEN)) throw AppException("La línea disponible cambió. Debes recalcular la compra antes de confirmarla.")
            val rate = MoneyMath.rate(bcvRateService.currentUsdRate().rate)
            val principalBs = row.financedUsd.multiply(rate).setScale(MoneyMath.VES_SCALE,RoundingMode.HALF_EVEN)
            val invoice = "KQR-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}-${sessionId.toString().padStart(6,'0')}"
            val loanId = connection.prepareStatement(
                """INSERT INTO prestamos_credito(user_id,order_id,invoice_number,level,principal_usd,principal_bs,bcv_rate,installment_count,status,lender_type,lender_business_id,repayment_business_id,
                   disbursement_destination_type,repayment_payment_mode,repayment_business_commercial_name,repayment_business_legal_name,repayment_business_rif,repayment_business_logo_path,
                   repayment_mobile_bank,repayment_mobile_phone,repayment_mobile_identity_number,repayment_mobile_holder_name,repayment_bank_name,repayment_bank_account_type,repayment_bank_account_number,repayment_bank_identity_number,repayment_bank_holder_name)
                   SELECT ?,NULL,?,?,?, ?,?,?, 'ACTIVE','MERCHANT_PURCHASE',b.id,b.id,'IN_STORE_PURCHASE',b.payment_mode,b.commercial_name,b.legal_name,b.rif,b.logo_path,
                          b.mobile_bank,b.mobile_phone,b.mobile_identity_number,b.mobile_holder_name,b.bank_name,b.bank_account_type,b.bank_account_number,b.bank_identity_number,b.bank_holder_name
                   FROM negocios_asociados b WHERE b.id=? RETURNING id"""
            ).use { st ->
                st.setLong(1,row.userId); st.setString(2,invoice); st.setInt(3,row.level); st.setBigDecimal(4,row.financedUsd); st.setBigDecimal(5,principalBs); st.setBigDecimal(6,rate)
                st.setInt(7,row.installments); st.setLong(8,row.businessId); st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            val usdParts=MoneyMath.splitExact(row.financedUsd,row.installments,MoneyMath.USD_SCALE)
            val bsParts=MoneyMath.splitExact(principalBs,row.installments,MoneyMath.VES_SCALE)
            for(i in 1..row.installments){
                connection.prepareStatement("INSERT INTO cuotas_credito(loan_id,installment_number,amount_usd,original_amount_bs,due_date,status) VALUES (?,?,?,?,?,'PENDING')").use { st ->
                    st.setLong(1,loanId); st.setInt(2,i); st.setBigDecimal(3,usdParts[i-1]); st.setBigDecimal(4,bsParts[i-1]); st.setObject(5,LocalDate.now().plusDays(14L*i)); st.executeUpdate()
                }
            }
            val balanceAfter = creditAccountSnapshot(connection,row.userId).availableUsd.coerceAtLeast(0.0)
            recordCredimpulsoTransaction(
                connection = connection,
                userId = row.userId,
                type = "PURCHASE",
                amountUsd = row.financedUsd.toDouble(),
                amountBs = principalBs.toDouble(),
                bcvRate = rate.toDouble(),
                balanceBeforeUsd = balanceAfter + row.financedUsd.toDouble(),
                balanceAfterUsd = balanceAfter,
                description = "Compra presencial $invoice en ${row.installments} cuotas · ${row.businessName}",
                loanId = loanId,
                performedBy = row.userId
            )
            connection.prepareStatement("UPDATE sesiones_compra_qr SET status='COMPLETED',loan_id=?,initial_received_at=NOW(),updated_at=NOW() WHERE id=?").use { st -> st.setLong(1,loanId); st.setLong(2,sessionId); st.executeUpdate() }
            audit(connection,actorId,"QR_PURCHASE_COMPLETED","QR_SESSION",sessionId.toString(),"$invoice · inicial directa al comercio US$ ${row.initialUsd} · línea usada US$ ${row.financedUsd}")
            qrSessionDto(connection,sessionId,row.userId,true)
        }
        notifyUsers(listOf(notifyUserId),"Compra Kredi+ aprobada","$businessName confirmó tu inicial de US$ $initialText. Tu plan de cuotas ya está activo.","QR_PURCHASE_COMPLETED",mapOf("sessionId" to sessionId.toString()))
        return dto
    }

    private fun normalizeQrCode(raw: String): String {
        val value=raw.trim()
        val extracted = Regex("(?:terminal=|code=|/qr/)(KQ(?:O)?-[A-Z0-9]+)",RegexOption.IGNORE_CASE).find(value)?.groupValues?.getOrNull(1)
        return (extracted ?: value).uppercase().take(96)
    }

    private fun expireQrSessions(connection: Connection) {
        connection.prepareStatement("UPDATE sesiones_compra_qr SET status='EXPIRED',updated_at=NOW() WHERE status IN ('AWAITING_MERCHANT','AWAITING_CUSTOMER','AWAITING_INITIAL') AND expires_at<NOW()").use { it.executeUpdate() }
    }

    private fun lockQrSessionUserForQuote(connection: Connection, sessionId: Long): Long =
        connection.prepareStatement(
            "SELECT user_id FROM sesiones_compra_qr WHERE id=? AND status IN ('AWAITING_MERCHANT','AWAITING_CUSTOMER','AWAITING_INITIAL') FOR UPDATE"
        ).use { st ->
            st.setLong(1, sessionId)
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else throw AppException("La compra QR ya fue completada, cancelada o venció.")
            }
        }

    private fun findQrTerminal(connection: Connection, id: Long): QrTerminalDto = connection.prepareStatement(
        """SELECT t.id,t.business_id,b.commercial_name,t.branch_name,t.terminal_name,t.public_code,t.active,t.created_at
           FROM terminales_qr_negocio t JOIN negocios_asociados b ON b.id=t.business_id WHERE t.id=?"""
    ).use { st -> st.setLong(1,id); st.executeQuery().use { rs -> if(!rs.next()) throw NotFoundException("Caja QR no encontrada."); qrTerminalDto(rs) } }

    private fun qrTerminalDto(rs: ResultSet): QrTerminalDto {
        val code=rs.getString(6)
        val payload="krediplus://buy?terminal=$code"
        return QrTerminalDto(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),code,payload,"${config.publicBaseUrl.trimEnd('/')}/qr/$code.png",rs.getBoolean(7),rs.getObject(8,OffsetDateTime::class.java).toString())
    }

    private fun qrSessionDto(connection: Connection, sessionId: Long, userId: Long, admin: Boolean): QrSaleSessionDto = connection.prepareStatement(
        """SELECT s.id,s.status,s.business_id,b.commercial_name,t.branch_name,t.terminal_name,COALESCE(up.full_name,u.username),s.total_usd,s.invoice_reference,s.level_snapshot,s.level_name_snapshot,
                  s.available_line_snapshot,s.initial_percent,s.initial_usd,s.financed_usd,s.installment_count,s.installment_usd,s.loan_id,s.created_at,s.expires_at
           FROM sesiones_compra_qr s JOIN terminales_qr_negocio t ON t.id=s.terminal_id JOIN negocios_asociados b ON b.id=s.business_id
           JOIN usuarios u ON u.id=s.user_id LEFT JOIN perfiles_usuario up ON up.user_id=u.id
           WHERE s.id=? AND (? OR s.user_id=?)"""
    ).use { st ->
        st.setLong(1,sessionId); st.setBoolean(2,admin); st.setLong(3,userId)
        st.executeQuery().use { rs -> if(!rs.next()) throw NotFoundException("Compra QR no encontrada.")
            fun dec(i:Int)=rs.getBigDecimal(i)?.toDouble()
            val status=rs.getString(2)
            val message=when(status){
                "AWAITING_MERCHANT"->"QR reconocido. Esperando que la caja indique el total de la compra."
                "AWAITING_CUSTOMER"->"Revisa tu plan y confirma si deseas continuar."
                "AWAITING_INITIAL"->"Plan confirmado. Paga la inicial directamente al comercio."
                "COMPLETED"->"Compra aprobada. Tus cuotas ya están activas."
                "EXPIRED"->"La sesión venció. Escanea nuevamente el QR de la caja."
                "CANCELLED"->"La sesión fue cancelada."
                else->"Compra QR Kredi+."
            }
            QrSaleSessionDto(
                id = rs.getLong(1),
                status = status,
                businessId = rs.getLong(3),
                businessName = rs.getString(4),
                branchName = rs.getString(5),
                terminalName = rs.getString(6),
                customerName = rs.getString(7).orEmpty(),
                totalUsd = dec(8),
                invoiceReference = rs.getString(9),
                level = rs.getObject(10)?.let { rs.getInt(10) },
                levelName = rs.getString(11),
                availableLineUsd = dec(12),
                initialPercent = dec(13),
                initialUsd = dec(14),
                financedUsd = dec(15),
                installmentCount = rs.getObject(16)?.let { rs.getInt(16) },
                installmentUsd = dec(17),
                loanId = rs.getObject(18)?.let { rs.getLong(18) },
                createdAt = rs.getObject(19, OffsetDateTime::class.java).toString(),
                expiresAt = rs.getObject(20, OffsetDateTime::class.java).toString(),
                message = message
            )
        }
    }

    private data class QrCompletionRow(
        val userId:Long,val businessId:Long,val totalUsd:BigDecimal,val invoiceReference:String,val level:Int,val levelName:String,
        val initialUsd:BigDecimal,val financedUsd:BigDecimal,val installments:Int,val businessName:String,val paymentMode:String,
        val mobileBank:String?,val mobilePhone:String?,val mobileIdentity:String?,val mobileHolder:String?,val bankName:String?,val bankAccountType:String?,
        val bankAccountNumber:String?,val bankIdentity:String?,val bankHolder:String?
    )

    private data class CredimpulsoLevelProgress(
        val current: CredimpulsoLevelRuleDto,
        val next: CredimpulsoLevelRuleDto?,
        val completedPayments: Int,
        val rules: List<CredimpulsoLevelRuleDto>,
        val overdueDays: Int = 0,
        val overduePenaltySteps: Int = 0
    )
    private data class CreditAccountSnapshot(
        val level: Int,
        val creditLimitUsd: Double,
        val usedUsd: Double,
        val availableUsd: Double,
        val status: String,
        val preferredInstallments: Int
    )
    private data class CreditHistorySnapshot(
        val scorePercentage: Int,
        val latePaymentCount: Int,
        val onTimePaymentCount: Int,
        val status: String
    )
    private data class ComboPurchaseComponent(val productId: Long, val quantityPerCombo: Int, val productName: String, val unit: String, val fairPrice: java.math.BigDecimal)
    private data class ComboPurchaseResolved(val id: Long, val name: String, val quantity: Int, val unitPrice: java.math.BigDecimal, val components: List<ComboPurchaseComponent>)
    private data class PurchaseResolved(val id: Long, val name: String, val unit: String, val stock: Int, val price: java.math.BigDecimal, val quantity: Int)
    private data class InvoiceHeader(
        val id: Long,
        val total: Double,
        val createdAt: OffsetDateTime,
        val email: String,
        val customerName: String,
        val firstName: String,
        val middleName: String,
        val lastName: String,
        val secondLastName: String,
        val birthDate: String,
        val employmentType: String,
        val fairName: String,
        val fairPlace: String,
        val invoiceNumber: String,
        val paymentMethod: String?,
        val reference: String,
        val originBank: String,
        val originPhone: String,
        val customerPhone: String,
        val state: String,
        val municipality: String,
        val parish: String,
        val community: String,
        val address: String,
        val documentType: String,
        val documentNumber: String,
        val subtotal: Double,
        val discountAmount: Double,
        val promotionName: String?
    )
    private data class FairBase(
        val id: Long,val name: String,val place: String,val schedule: String,val description: String,val published: Boolean,val finalized: Boolean,val paymentMode: String,
        val businessId: Long?,val businessCommercialName: String?,val businessLegalName: String?,val businessRif: String?,val businessLogoPath: String?,
        val mobileBank: String?,val mobilePhone: String?,val mobileIdentity: String?,val mobileHolder: String?,
        val bankName: String?,val bankType: String?,val bankAccount: String?,val bankIdentity: String?,val bankHolder: String?
    )


    private fun ensureWalletV27Schema(connection: Connection) {
        if (walletSchemaReady) return
        synchronized(walletSchemaLock) {
            if (walletSchemaReady) return

            // El esquema principal se aplica al iniciar el backend. Este respaldo se ejecuta
            // una sola vez por proceso; nunca en cada actualización de saldo.
            val statements = listOf(
                "ALTER TABLE carteras_presupuesto_contador ADD COLUMN IF NOT EXISTS wallet_address VARCHAR(80)",
                "ALTER TABLE carteras_credimpulso_admin ADD COLUMN IF NOT EXISTS wallet_address VARCHAR(80)",
                "ALTER TABLE cuentas_credito ADD COLUMN IF NOT EXISTS wallet_address VARCHAR(80)",
                "ALTER TABLE solicitudes_credito ADD COLUMN IF NOT EXISTS wallet_transaction_id VARCHAR(120)",
                "ALTER TABLE solicitudes_credito ADD COLUMN IF NOT EXISTS source_wallet_address VARCHAR(80)",
                "ALTER TABLE solicitudes_credito ADD COLUMN IF NOT EXISTS destination_wallet_address VARCHAR(80)",
                """UPDATE carteras_presupuesto_contador
                   SET wallet_address='ISC-' || UPPER(SUBSTRING(MD5('ACCOUNTANT:' || contador_id::TEXT),1,32))
                   WHERE wallet_address IS NULL OR BTRIM(wallet_address)=''""",
                """UPDATE carteras_credimpulso_admin
                   SET wallet_address='ISA-' || UPPER(SUBSTRING(MD5('ADMIN:' || admin_id::TEXT),1,32))
                   WHERE wallet_address IS NULL OR BTRIM(wallet_address)=''""",
                """UPDATE cuentas_credito
                   SET wallet_address='ISU-' || UPPER(SUBSTRING(MD5('USER:' || user_id::TEXT),1,32))
                   WHERE wallet_address IS NULL OR BTRIM(wallet_address)=''"""
            )

            statements.forEachIndexed { index, sql ->
                withSavepointFallback(
                    connection = connection,
                    fallback = false,
                    context = "preparar esquema de cartera v27 #$index"
                ) {
                    connection.createStatement().use { statement -> statement.execute(sql) }
                    true
                }
            }
            walletSchemaReady = true
        }
    }

    private fun insertAccountantAllocationIntoAdminWallet(
        connection: Connection,
        adminId: Long,
        amount: Double,
        balanceBefore: Double,
        balanceAfter: Double,
        reference: String,
        description: String
    ) {
        fun insert(type: String) {
            connection.prepareStatement(
                """INSERT INTO movimientos_cartera_credimpulso(
                       admin_id,tipo,monto_usd,saldo_antes_usd,saldo_despues_usd,referencia,descripcion
                   ) VALUES (?,?,?,?,?,?,?)"""
            ).use { statement ->
                statement.setLong(1, adminId)
                statement.setString(2, type)
                statement.setBigDecimal(3, MoneyMath.usd(amount, "Monto USD"))
                statement.setBigDecimal(4, MoneyMath.usd(balanceBefore, "Saldo anterior"))
                statement.setBigDecimal(5, MoneyMath.usd(balanceAfter, "Saldo posterior"))
                statement.setString(6, reference)
                statement.setString(7, description)
                statement.executeUpdate()
            }
        }

        val savepoint = connection.setSavepoint("accountant_admin_wallet_movement")
        try {
            insert("ASIGNACION_CONTADOR")
            runCatching { connection.releaseSavepoint(savepoint) }
        } catch (error: Exception) {
            runCatching { connection.rollback(savepoint) }
            runCatching { connection.releaseSavepoint(savepoint) }
            logger.warn(
                "La base de datos conserva una restricción antigua. La asignación se registrará como ABONO compatible.",
                error
            )
            insert("ABONO")
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun close() {
        notificationExecutor.shutdown()
        try {
            if (!notificationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                notificationExecutor.shutdownNow()
            }
        } catch (error: InterruptedException) {
            notificationExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun safeCurrentBcvRate(): BcvRate = runCatching {
        bcvRateService.currentUsdRate()
    }.getOrElse { error ->
        logger.warn("No fue posible obtener la tasa BCV para la cartera. Se conserva el saldo en dólares.", error)
        BcvRate(
            rate = 0.0,
            date = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            source = "BCV temporalmente no disponible"
        )
    }

    private fun walletWithBcv(wallet: AdminCredimpulsoWalletDto): AdminCredimpulsoWalletDto {
        val bcv = safeCurrentBcvRate()
        return wallet.copy(
            balanceBs = MoneyMath.usdToVesOrZero(wallet.balanceUsd, bcv.rate).toDouble(),
            bcvRate = bcv.rate,
            bcvDate = bcv.date,
            bcvSource = bcv.source
        )
    }

    private fun money(value: Double): Double =
        MoneyMath.usd(value).toDouble()


    companion object {
        private val USERNAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_.]{3,23}$")
        private val EMAIL_REGEX = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    }
}
