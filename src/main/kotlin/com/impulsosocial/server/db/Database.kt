package com.impulsosocial.server.db

import com.impulsosocial.server.CREDICASH_APP_VERSION
import com.impulsosocial.server.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class Database(private val config: AppConfig) {
    @Volatile private var authenticationSchemaReady = false
    private val authenticationSchemaLock = Any()
    @Volatile private var catalogSalesDestinationsSchemaReady = false
    private val catalogSalesDestinationsSchemaLock = Any()
    @Volatile private var commercialQrSchemaReady = false
    private val commercialQrSchemaLock = Any()
    private val hikariDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = config.dbMaximumPoolSize
            minimumIdle = config.dbMinimumIdle
            connectionTimeout = config.dbConnectionTimeoutMs
            validationTimeout = config.dbValidationTimeoutMs
            idleTimeout = config.dbIdleTimeoutMs
            maxLifetime = config.dbMaxLifetimeMs
            keepaliveTime = config.dbKeepaliveTimeMs.coerceAtMost(config.dbMaxLifetimeMs - 1_000L)
            leakDetectionThreshold = config.dbLeakDetectionThresholdMs
            initializationFailTimeout = -1
            isAutoCommit = true
            poolName = "KrediPlusPool"
            addDataSourceProperty("ApplicationName", "KrediPlus-$CREDICASH_APP_VERSION")
            addDataSourceProperty("tcpKeepAlive", "true")
            addDataSourceProperty("reWriteBatchedInserts", "true")
        })
    }

    private val hikari: HikariDataSource by hikariDelegate

    val dataSource: DataSource
        get() = hikari

    /**
     * Repara de forma idempotente la parte crítica del esquema usada por el PIN y la
     * sesión persistente. El servidor abre /health antes de terminar todas las migraciones;
     * por eso una instalación antigua podía aceptar usuario/contraseña y fallar justo al
     * verificar el PIN si las columnas de sesión única todavía no existían.
     *
     * No elimina ni modifica usuarios. Las sesiones antiguas sin identificador de
     * dispositivo se revocan porque no pueden cumplir la política de sesión única.
     */
    fun ensureAuthenticationSchema() {
        if (authenticationSchemaReady) return
        synchronized(authenticationSchemaLock) {
            if (authenticationSchemaReady) return
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val applied = applyAuthenticationSchema(connection)
                    connection.commit()
                    if (applied) authenticationSchemaReady = true
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }
    }

    /**
     * Variante para una transacción ya abierta. No marca la caché como lista porque la
     * transacción llamadora todavía podría revertirse por un PIN incorrecto u otro error.
     */
    fun ensureAuthenticationSchema(connection: Connection) {
        if (authenticationSchemaReady) return
        synchronized(authenticationSchemaLock) {
            if (!authenticationSchemaReady) {
                val applied = applyAuthenticationSchema(connection)
                if (applied && connection.autoCommit) authenticationSchemaReady = true
            }
        }
    }

    private fun applyAuthenticationSchema(connection: Connection): Boolean {
        val usersTableExists = connection.prepareStatement("SELECT to_regclass('public.usuarios') IS NOT NULL").use { statement ->
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
        if (!usersTableExists) return false

        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS sesiones_usuario (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
                    device_id_hash VARCHAR(64),
                    device_name VARCHAR(255),
                    app_version VARCHAR(80),
                    ip_address INET,
                    expires_at TIMESTAMPTZ NOT NULL,
                    revoked_at TIMESTAMPTZ,
                    ended_reason VARCHAR(80),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    last_used_at TIMESTAMPTZ,
                    last_heartbeat_at TIMESTAMPTZ
                )
                """.trimIndent()
            )
            statement.execute("ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS device_id_hash VARCHAR(64)")
            statement.execute("ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS device_name VARCHAR(255)")
            statement.execute("ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS app_version VARCHAR(80)")
            statement.execute("ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ")
            statement.execute("ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ")
            statement.execute("ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS ended_reason VARCHAR(80)")
            // La suspensión forma parte del estado de autenticación: estas columnas deben existir
            // incluso durante un despliegue en el que las migraciones extensas todavía estén terminando.
            statement.execute("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ")
            statement.execute("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(500)")
            statement.execute("ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspended_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL")
            statement.execute(
                """
                UPDATE sesiones_usuario
                SET revoked_at=COALESCE(revoked_at,NOW()),
                    ended_reason=COALESCE(ended_reason,'LEGACY_SESSION_WITHOUT_DEVICE')
                WHERE revoked_at IS NULL AND (device_id_hash IS NULL OR BTRIM(device_id_hash)='')
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_user_sessions_user_active
                ON sesiones_usuario(user_id, revoked_at, expires_at)
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_user_sessions_heartbeat
                ON sesiones_usuario(user_id, last_heartbeat_at DESC)
                WHERE revoked_at IS NULL
                """.trimIndent()
            )
        }
        return true
    }

    /**
     * Hotfix idempotente para los destinos de cobro del catálogo.
     *
     * El despliegue normal ejecuta schema.sql en segundo plano, pero Android puede abrir
     * "Negocios asociados" apenas el servicio responde /health. En una base existente,
     * eso dejaba una ventana donde /catalog-sales-destinations podía fallar porque la
     * migración 92 aún no había terminado. Este método garantiza el esquema justo antes
     * de leer o guardar la asociación, sin borrar ni reemplazar datos existentes.
     */
    fun ensureCatalogSalesDestinationsSchema() {
        if (catalogSalesDestinationsSchemaReady) return
        synchronized(catalogSalesDestinationsSchemaLock) {
            if (catalogSalesDestinationsSchemaReady) return
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.createStatement().use { statement ->
                        statement.execute("SELECT pg_advisory_xact_lock(5041092)")
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS configuracion_ventas_catalogo (
                                id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                                product_business_id BIGINT REFERENCES negocios_asociados(id) ON DELETE SET NULL,
                                combo_business_id BIGINT REFERENCES negocios_asociados(id) ON DELETE SET NULL,
                                updated_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
                                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                            )
                            """.trimIndent()
                        )
                        // Soporta instalaciones de prueba donde la tabla pudo haberse creado
                        // parcialmente antes de esta versión.
                        statement.execute("ALTER TABLE configuracion_ventas_catalogo ADD COLUMN IF NOT EXISTS product_business_id BIGINT")
                        statement.execute("ALTER TABLE configuracion_ventas_catalogo ADD COLUMN IF NOT EXISTS combo_business_id BIGINT")
                        statement.execute("ALTER TABLE configuracion_ventas_catalogo ADD COLUMN IF NOT EXISTS updated_by BIGINT")
                        statement.execute("ALTER TABLE configuracion_ventas_catalogo ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()")
                        statement.execute("INSERT INTO configuracion_ventas_catalogo(id) VALUES (1) ON CONFLICT(id) DO NOTHING")
                        statement.execute(
                            """
                            INSERT INTO versiones_esquema(version, description)
                            VALUES (92, 'Kredi+ 7.2.39: asociación explícita de negocio para ventas individuales y combos; pago deshabilitado sin destino')
                            ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW()
                            """.trimIndent()
                        )
                    }
                    connection.commit()
                    catalogSalesDestinationsSchemaReady = true
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }
    }

    /**
     * Autorreparación idempotente del esquema QR comercial (migración 96).
     *
     * Una base restaurada desde un dump anterior puede permitir iniciar sesión y navegar,
     * pero no tener todavía las tablas ofertas_qr_comerciales/items_oferta_qr/
     * compras_qr_comerciales. En ese caso PostgreSQL devuelve 42P01/42703 y el cliente
     * veía un 503 "SCHEMA_UPDATING" de forma permanente. Esta reparación se ejecuta justo
     * antes de usar el módulo QR y no borra ni reemplaza datos existentes.
     */
    fun ensureCommercialQrSchema() {
        if (commercialQrSchemaReady) return

        // La sincronización de combos necesita esta tabla aunque el QR ya exista.
        ensureCatalogSalesDestinationsSchema()

        synchronized(commercialQrSchemaLock) {
            if (commercialQrSchemaReady) return
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.createStatement().use { statement ->
                        statement.execute("SELECT pg_advisory_xact_lock(5041096)")
                        statement.execute("ALTER TABLE productos_jornada ADD COLUMN IF NOT EXISTS quantity INTEGER NOT NULL DEFAULT 1")

                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS ofertas_qr_comerciales (
                                id BIGSERIAL PRIMARY KEY,
                                offer_type VARCHAR(24) NOT NULL DEFAULT 'OFFER',
                                source_id BIGINT,
                                business_id BIGINT NOT NULL REFERENCES negocios_asociados(id) ON DELETE RESTRICT,
                                name VARCHAR(220) NOT NULL,
                                description TEXT NOT NULL DEFAULT '',
                                price_usd NUMERIC(12,2) NOT NULL,
                                cover_path TEXT,
                                status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
                                stock_limit INTEGER,
                                sold_count INTEGER NOT NULL DEFAULT 0,
                                one_purchase_per_user BOOLEAN NOT NULL DEFAULT TRUE,
                                starts_at TIMESTAMPTZ,
                                ends_at TIMESTAMPTZ,
                                public_code VARCHAR(96) NOT NULL,
                                created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
                                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                            )
                            """.trimIndent()
                        )
                        // Soporta instalaciones donde una versión previa alcanzó a crear la tabla parcialmente.
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS offer_type VARCHAR(24) NOT NULL DEFAULT 'OFFER'")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS source_id BIGINT")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS business_id BIGINT")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS name VARCHAR(220) NOT NULL DEFAULT 'Oferta QR'")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT ''")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS price_usd NUMERIC(12,2) NOT NULL DEFAULT 0")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS cover_path TEXT")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS stock_limit INTEGER")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS sold_count INTEGER NOT NULL DEFAULT 0")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS one_purchase_per_user BOOLEAN NOT NULL DEFAULT TRUE")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS starts_at TIMESTAMPTZ")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS ends_at TIMESTAMPTZ")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS public_code VARCHAR(96)")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS created_by BIGINT")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()")
                        statement.execute("ALTER TABLE ofertas_qr_comerciales ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()")
                        statement.execute(
                            """
                            UPDATE ofertas_qr_comerciales
                            SET public_code='KQO-' || UPPER(SUBSTRING(MD5(id::text || clock_timestamp()::text || random()::text) FROM 1 FOR 20))
                            WHERE public_code IS NULL OR BTRIM(public_code)=''
                            """.trimIndent()
                        )
                        statement.execute(
                            """
                            WITH duplicados AS (
                                SELECT id, ROW_NUMBER() OVER (PARTITION BY public_code ORDER BY id) AS rn
                                FROM ofertas_qr_comerciales
                                WHERE public_code IS NOT NULL AND BTRIM(public_code)<>''
                            )
                            UPDATE ofertas_qr_comerciales o
                            SET public_code='KQO-' || UPPER(SUBSTRING(MD5(o.id::text || clock_timestamp()::text || random()::text) FROM 1 FOR 20))
                            FROM duplicados d
                            WHERE o.id=d.id AND d.rn>1
                            """.trimIndent()
                        )
                        statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_ofertas_qr_public_code ON ofertas_qr_comerciales(public_code)")
                        statement.execute("CREATE INDEX IF NOT EXISTS idx_ofertas_qr_status ON ofertas_qr_comerciales(status,starts_at,ends_at)")
                        statement.execute("CREATE INDEX IF NOT EXISTS idx_ofertas_qr_business ON ofertas_qr_comerciales(business_id,status)")

                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS items_oferta_qr (
                                offer_id BIGINT NOT NULL REFERENCES ofertas_qr_comerciales(id) ON DELETE CASCADE,
                                product_id BIGINT NOT NULL REFERENCES productos(id) ON DELETE RESTRICT,
                                quantity INTEGER NOT NULL DEFAULT 1,
                                unit_price_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
                                PRIMARY KEY(offer_id,product_id)
                            )
                            """.trimIndent()
                        )
                        statement.execute("ALTER TABLE items_oferta_qr ADD COLUMN IF NOT EXISTS offer_id BIGINT")
                        statement.execute("ALTER TABLE items_oferta_qr ADD COLUMN IF NOT EXISTS product_id BIGINT")
                        statement.execute("ALTER TABLE items_oferta_qr ADD COLUMN IF NOT EXISTS quantity INTEGER NOT NULL DEFAULT 1")
                        statement.execute("ALTER TABLE items_oferta_qr ADD COLUMN IF NOT EXISTS unit_price_usd NUMERIC(12,2) NOT NULL DEFAULT 0")
                        statement.execute("CREATE INDEX IF NOT EXISTS idx_items_oferta_qr_offer ON items_oferta_qr(offer_id)")

                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS compras_qr_comerciales (
                                id BIGSERIAL PRIMARY KEY,
                                offer_id BIGINT NOT NULL REFERENCES ofertas_qr_comerciales(id) ON DELETE RESTRICT,
                                business_id BIGINT NOT NULL REFERENCES negocios_asociados(id) ON DELETE RESTRICT,
                                user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
                                status VARCHAR(32) NOT NULL DEFAULT 'AWAITING_CONFIRMATION',
                                total_usd NUMERIC(12,2) NOT NULL,
                                level_snapshot INTEGER NOT NULL,
                                level_name_snapshot VARCHAR(120) NOT NULL,
                                available_line_snapshot NUMERIC(12,2) NOT NULL,
                                initial_percent NUMERIC(7,2) NOT NULL,
                                initial_usd NUMERIC(12,2) NOT NULL,
                                financed_usd NUMERIC(12,2) NOT NULL,
                                installment_count INTEGER NOT NULL,
                                installment_usd NUMERIC(12,2) NOT NULL,
                                offer_name_snapshot VARCHAR(220) NOT NULL,
                                business_name_snapshot VARCHAR(220) NOT NULL,
                                user_name_snapshot VARCHAR(220) NOT NULL DEFAULT 'Usuario Kredi+',
                                user_document_snapshot VARCHAR(80),
                                items_snapshot TEXT NOT NULL DEFAULT '',
                                loan_id BIGINT REFERENCES prestamos_credito(id) ON DELETE SET NULL,
                                confirmed_at TIMESTAMPTZ,
                                initial_received_at TIMESTAMPTZ,
                                expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '15 minutes'),
                                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                            )
                            """.trimIndent()
                        )
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS offer_id BIGINT")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS business_id BIGINT")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS user_id BIGINT")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'AWAITING_CONFIRMATION'")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS total_usd NUMERIC(12,2) NOT NULL DEFAULT 0")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS level_snapshot INTEGER NOT NULL DEFAULT 1")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS level_name_snapshot VARCHAR(120) NOT NULL DEFAULT 'Nivel Kredi+'");
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS available_line_snapshot NUMERIC(12,2) NOT NULL DEFAULT 0")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS initial_percent NUMERIC(7,2) NOT NULL DEFAULT 0")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS initial_usd NUMERIC(12,2) NOT NULL DEFAULT 0")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS financed_usd NUMERIC(12,2) NOT NULL DEFAULT 0")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS installment_count INTEGER NOT NULL DEFAULT 1")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS installment_usd NUMERIC(12,2) NOT NULL DEFAULT 0")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS offer_name_snapshot VARCHAR(220) NOT NULL DEFAULT 'Oferta QR'")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS business_name_snapshot VARCHAR(220) NOT NULL DEFAULT 'Negocio Kredi+'");
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS user_name_snapshot VARCHAR(220) NOT NULL DEFAULT 'Usuario Kredi+'");
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS user_document_snapshot VARCHAR(80)")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS items_snapshot TEXT NOT NULL DEFAULT ''")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS loan_id BIGINT")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS initial_received_at TIMESTAMPTZ")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '15 minutes')")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()")
                        statement.execute("ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()")
                        statement.execute("CREATE INDEX IF NOT EXISTS idx_compras_qr_comerciales_user ON compras_qr_comerciales(user_id,created_at DESC)")
                        statement.execute("CREATE INDEX IF NOT EXISTS idx_compras_qr_comerciales_offer ON compras_qr_comerciales(offer_id,status,created_at DESC)")
                        statement.execute("CREATE INDEX IF NOT EXISTS idx_compras_qr_offer_user ON compras_qr_comerciales(offer_id,user_id,created_at DESC)")

                        statement.execute(
                            """
                            INSERT INTO versiones_esquema(version, description)
                            VALUES (96, 'Kredi+ 1.3.7: autorreparación idempotente del esquema QR comercial')
                            ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW()
                            """.trimIndent()
                        )
                    }
                    connection.commit()
                    commercialQrSchemaReady = true
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }
    }

    fun initializeSchema() {
        val schema = requireNotNull(javaClass.classLoader.getResourceAsStream("db/schema.sql")) {
            "No se encontró db/schema.sql dentro del servidor."
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val statements = SqlScriptParser.split(schema)
            .filter { !it.equals("BEGIN", ignoreCase = true) && !it.equals("COMMIT", ignoreCase = true) }

        transaction { connection ->
            // Evita que dos despliegues intenten modificar el esquema al mismo tiempo.
            connection.createStatement().use { lockStatement ->
                lockStatement.execute("SELECT pg_advisory_xact_lock(5041001)")
            }

            connection.createStatement().use { statement ->
                statements.forEachIndexed { index, sql ->
                    val savepoint = connection.setSavepoint("schema_$index")
                    try {
                        statement.execute(sql)
                        runCatching { connection.releaseSavepoint(savepoint) }
                    } catch (error: SQLException) {
                        connection.rollback(savepoint)

                        if (isRecoverableSchemaMigration(error, sql)) {
                            System.err.println(
                                "Kredi+: migración histórica omitida de forma segura " +
                                    "(${error.sqlState ?: "sin SQLSTATE"}): ${error.message.orEmpty().take(280)}"
                            )
                            runCatching { connection.releaseSavepoint(savepoint) }
                        } else {
                            val preview = sql
                                .replace(Regex("\\s+"), " ")
                                .trim()
                                .take(320)
                            throw SQLException(
                                "Falló la sentencia ${index + 1}/${statements.size} del esquema: $preview",
                                error.sqlState,
                                error.errorCode,
                                error
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isRecoverableSchemaMigration(error: SQLException, sql: String): Boolean {
        val message = error.message.orEmpty()
        val normalizedSql = sql.lowercase()
        val knownLegacyConstraint =
            normalizedSql.contains("cuotas_credito_installment_number_check") ||
                normalizedSql.contains("prestamos_credito_installment_count_check") ||
                normalizedSql.contains("cuotas_credito_loan_id_installment_number_key")

        val recoverableSqlState = error.sqlState in setOf(
            "23514", // check_violation
            "23505", // unique_violation
            "42710"  // duplicate_object
        )

        return knownLegacyConstraint && (
            recoverableSqlState ||
                message.contains("constraint", ignoreCase = true)
        )
    }


    fun verifyRequiredSchema() {
        val requiredTables = listOf(
            "versiones_esquema",
            "usuarios",
            "perfiles_usuario",
            "sesiones_usuario",
            "notificaciones",
            "verificaciones_documentos",
            "credenciales_biometricas_dispositivo",
            "consentimientos_usuario",
            "transacciones_carteras_continuas",
            "movimientos_presupuestarios",
            "catalogo_costos",
            "centros_costo",
            "periodos_presupuestarios",
            "partidas_presupuestarias",
            "compromisos_presupuestarios",
            "ajustes_presupuestarios",
            "evaluaciones_predictivas",
            "corridas_predictivas_presupuesto",
            "reportes_pago_usuario",
            "conciliaciones_pago",
            "solicitudes_doble_aprobacion",
            "cierres_contables",
            "negocios_asociados",
            "configuracion_ventas_catalogo",
            "banners_inicio",
            "calificaciones_banner",
            "calificaciones_compra",
            "encuestas_experiencia_app",
            "promociones_descuentos",
            "ofertas_qr_comerciales",
            "items_oferta_qr",
            "compras_qr_comerciales"
        )

        dataSource.connection.use { connection ->
            val missing = requiredTables.filter { table ->
                connection.prepareStatement("SELECT to_regclass(?)").use { statement ->
                    statement.setString(1, "public.$table")
                    statement.executeQuery().use { result ->
                        !result.next() || result.getString(1) == null
                    }
                }
            }

            check(missing.isEmpty()) {
                "El esquema PostgreSQL está incompleto. Faltan: ${missing.joinToString(", ")}"
            }

            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 74)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 74 de Kredi+ 7.0.0 no está registrada en versiones_esquema."
                    }
                }
            }

            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 83)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 83 del control presupuestario no está registrada en versiones_esquema."
                    }
                }
            }

            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 84)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 84 de robustez de operaciones de cuenta no está registrada en versiones_esquema."
                    }
                }
            }

            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 85)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 85 del carrusel de banners no está registrada en versiones_esquema."
                    }
                }
            }

            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 86)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 86 de calificaciones y encuestas no está registrada en versiones_esquema."
                    }
                }
            }


            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 88)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 88 de encuesta de experiencia no está registrada en versiones_esquema."
                    }
                }
            }

            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 89)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 89 de promociones y descuentos no está registrada en versiones_esquema."
                    }
                }
            }


            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 92)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 92 de destinos de pago del catálogo no está registrada en versiones_esquema."
                    }
                }
            }

            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM versiones_esquema WHERE version = 96)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La migración 96 del QR comercial no está registrada en versiones_esquema."
                    }
                }
            }

            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM prestamos_credito
                WHERE order_id IS NULL
                  AND (invoice_number IS NULL OR BTRIM(invoice_number)='')
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getLong(1) == 0L) {
                        "Existen préstamos directos sin pedido y sin número de préstamo/factura propio."
                    }
                }
            }

            val requiredColumns = mapOf(
                "productos" to listOf("base_price_usd", "bcv_rate", "pricing_mode", "price_updated_at", "minimum_stock", "last_counted_at", "technical_details"),
                "desafios_autenticacion" to listOf("attempts"),
                "sesiones_usuario" to listOf("device_id_hash", "last_heartbeat_at", "ended_reason"),
                "movimientos_presupuestarios" to listOf(
                    "tipo", "monto_usd", "tasa_bcv", "saldo_antes_usd", "saldo_despues_usd", "idempotency_key",
                    "categoria_costo_id", "centro_costo_id", "periodo_presupuestario_id",
                    "partida_presupuestaria_id", "compromiso_id", "estado_control_presupuesto"
                ),
                "facturas" to listOf("integrity_status", "integrity_score", "calculated_total_bs", "integrity_difference_bs", "document_hash", "algorithm_version", "validation_warnings", "integrity_verified_at"),
                "usuarios" to listOf(
                    "admin_subrole", "account_status", "verification_status",
                    "account_kind", "person_group_id", "linked_account_user_id",
                    "suspended_at", "suspension_reason", "suspended_by", "last_login_at"
                ),
                "reportes_pago_usuario" to listOf("risk_score", "risk_level", "proof_sha256", "proof_visual_hash", "bank_confirmed", "amount_difference_bs", "amount_difference_percent", "decision_version"),
                "conciliaciones_pago" to listOf("payment_report_id", "accountant_id", "status", "confidence_percent"),
                "solicitudes_doble_aprobacion" to listOf("action_type", "requested_by", "approved_by", "status"),
                "cierres_contables" to listOf("period_month", "accountant_id", "status", "pending_differences"),
                "negocios_asociados" to listOf("commercial_name", "legal_name", "rif", "logo_path", "active", "payment_mode", "created_by"),
                "jornadas" to listOf("business_id"),
                "pedidos" to listOf("discount_amount", "promotion_id", "promotion_name_snapshot", "original_subtotal"),
                "prestamos_credito" to listOf(
                    "invoice_number", "credit_request_id", "lender_type", "lender_business_id",
                    "repayment_business_id", "disbursement_destination_type", "repayment_payment_mode",
                    "repayment_business_commercial_name", "repayment_business_rif"
                ),
                "productos_jornada" to listOf("quantity"),
                "ofertas_qr_comerciales" to listOf(
                    "offer_type", "source_id", "business_id", "name", "description", "price_usd",
                    "status", "stock_limit", "sold_count", "one_purchase_per_user", "public_code",
                    "created_at", "updated_at"
                ),
                "items_oferta_qr" to listOf("offer_id", "product_id", "quantity", "unit_price_usd"),
                "compras_qr_comerciales" to listOf(
                    "offer_id", "business_id", "user_id", "status", "total_usd",
                    "user_name_snapshot", "user_document_snapshot", "expires_at", "created_at", "updated_at"
                )
            )
            val missingColumns = buildList {
                requiredColumns.forEach { (table, columns) ->
                    columns.forEach { column ->
                        val exists = connection.prepareStatement(
                            """
                            SELECT EXISTS(
                                SELECT 1 FROM information_schema.columns
                                WHERE table_schema='public' AND table_name=? AND column_name=?
                            )
                            """.trimIndent()
                        ).use { statement ->
                            statement.setString(1, table)
                            statement.setString(2, column)
                            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
                        }
                        if (!exists) add("$table.$column")
                    }
                }
            }
            check(missingColumns.isEmpty()) {
                "El esquema de Kredi+ 7.0.0 está incompleto. Faltan columnas: ${missingColumns.joinToString(", ")}"
            }

            connection.prepareStatement(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM pg_constraint
                    WHERE conrelid='public.usuarios'::regclass
                      AND conname='usuarios_admin_subrole_check'
                      AND pg_get_constraintdef(oid) ILIKE '%WAREHOUSE%'
                )
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getBoolean(1)) {
                        "La restricción usuarios_admin_subrole_check no admite el subrol WAREHOUSE."
                    }
                }
            }

            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM usuarios
                WHERE NOT (
                    (role='BENEFICIARY' AND admin_subrole IS NULL)
                    OR (role='ADMIN' AND admin_subrole IN ('GENERAL','SUPERVISOR','ANALYST','SUPPORT','AUDITOR','ANTIFRAUD'))
                    OR (role='ACCOUNTANT' AND admin_subrole='ACCOUNTING')
                    OR (role='WAREHOUSE' AND admin_subrole='WAREHOUSE')
                )
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next() && result.getLong(1) == 0L) {
                        "Existen usuarios con una combinación de rol y subrol incompatible."
                    }
                }
            }
        }
    }

    fun isHealthy(): Boolean = runCatching {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1").use { statement ->
                statement.executeQuery().use { result -> result.next() && result.getInt(1) == 1 }
            }
        }
    }.getOrDefault(false)

    fun <T> transaction(block: (Connection) -> T): T {
        dataSource.connection.use { connection ->
            val previous = connection.autoCommit
            connection.autoCommit = false
            return try {
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previous
            }
        }
    }

    fun close() {
        if (hikariDelegate.isInitialized()) {
            hikari.close()
        }
    }
}
