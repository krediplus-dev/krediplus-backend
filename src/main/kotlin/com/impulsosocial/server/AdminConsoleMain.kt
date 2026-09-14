package com.impulsosocial.server

import com.impulsosocial.server.config.AppConfig
import com.impulsosocial.server.db.Database
import com.impulsosocial.server.security.PasswordSecurity
import com.impulsosocial.server.security.PasswordPolicy
import com.impulsosocial.server.security.PinPolicy
import java.time.LocalDate

internal data class ConsoleAdminInput(
    val email: String,
    val firstName: String,
    val middleName: String,
    val lastName: String,
    val secondLastName: String,
    val phone: String,
    val birthDate: LocalDate,
    val password: String,
    val pin: String,
)

fun main(args: Array<String>) {
    val config = AppConfig()
    val database = Database(config)
    val passwordSecurity = PasswordSecurity()
    try {
        database.initializeSchema()
        println()
        println("KREDI+ - ADMINISTRADORES")
        println("================================")
        listAdmins(database)

        val existingCount = adminCount(database)
        val ensureOnly = args.any { it.equals("ensure", ignoreCase = true) }
        if (existingCount == 0L) {
            println("No existe ningún administrador. Debes crear el administrador inicial.")
            createOrUpdateAdmin(database, passwordSecurity, readAdminInput())
        } else if (!ensureOnly) {
            val forceCreate = args.any { it.equals("admin", ignoreCase = true) || it.equals("crear", ignoreCase = true) }
            if (forceCreate || askYesNo("¿Deseas crear o actualizar otro administrador? (S/N): ")) {
                do {
                    createOrUpdateAdmin(database, passwordSecurity, readAdminInput())
                } while (askYesNo("¿Deseas gestionar otro administrador? (S/N): "))
            }
        }

        println()
        listAdmins(database)
    } finally {
        database.close()
    }
}

private fun adminCount(database: Database): Long = database.dataSource.connection.use { connection ->
    connection.prepareStatement(
        "SELECT COUNT(*) FROM usuarios WHERE UPPER(role)='ADMIN' AND account_status='ACTIVE'"
    ).use { statement ->
        statement.executeQuery().use { result -> result.next(); result.getLong(1) }
    }
}

private fun listAdmins(database: Database) {
    database.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT u.email, COALESCE(NULLIF(TRIM(p.full_name),''), u.email) AS nombre, u.account_status, u.last_login_at
            FROM usuarios u
            LEFT JOIN perfiles_usuario p ON p.user_id=u.id
            WHERE UPPER(u.role)='ADMIN'
            ORDER BY u.created_at
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { result ->
                println("Administradores registrados:")
                var any = false
                while (result.next()) {
                    any = true
                    println(" - ${result.getString("nombre")} | ${result.getString("email")} | ${result.getString("account_status")} | último acceso: ${result.getObject("last_login_at") ?: "sin ingreso"}")
                }
                if (!any) println(" - Ninguno")
            }
        }
    }
}

private fun readAdminInput(): ConsoleAdminInput {
    println()
    val firstName = readRequired("Primer nombre: ")
    val middleName = readRequired("Segundo nombre: ")
    val lastName = readRequired("Primer apellido: ")
    val secondLastName = readRequired("Segundo apellido: ")
    val email = readRequired("Correo: ").trim().lowercase().also {
        require(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it)) { "El correo no es válido." }
    }
    val phone = normalizePhone(readRequired("Celular venezolano (+58 412-1234567): "))
        ?: error("El celular venezolano no es válido.")
    val birthDate = runCatching { LocalDate.parse(readRequired("Fecha de nacimiento (AAAA-MM-DD): ")) }
        .getOrElse { error("La fecha de nacimiento debe tener el formato AAAA-MM-DD y ser una fecha real.") }
    val password = readSecret("Contraseña segura (8+ caracteres, mayúscula, minúscula, número y especial): ").also {
        PasswordPolicy.validationError(it, email.substringBefore('@'), email)?.let { error -> error(error) }
    }
    val pin = readSecret("PIN de 6 dígitos: ").also {
        PinPolicy.validationError(it)?.let { message -> error(message) }
    }
    return ConsoleAdminInput(email, firstName, middleName, lastName, secondLastName, phone, birthDate, password, pin)
}

internal fun createOrUpdateAdmin(database: Database, security: PasswordSecurity, input: ConsoleAdminInput) {
    database.transaction { connection ->
        val existingId = connection.prepareStatement("SELECT id,role FROM usuarios WHERE LOWER(email)=LOWER(?) FOR UPDATE").use { statement ->
            statement.setString(1, input.email)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    check(result.getString("role") == "ADMIN") {
                        "Este correo pertenece a otra clase de cuenta. Usa un correo distinto para el administrador."
                    }
                    result.getLong("id")
                } else null
            }
        }
        val userId = if (existingId != null) {
            connection.prepareStatement(
                """
                UPDATE usuarios
                SET password_hash=?, pin_hash=?, role='ADMIN', admin_subrole=COALESCE(admin_subrole,'GENERAL'),
                    account_kind='OPERATIONAL', account_status='ACTIVE', verification_status='VERIFIED',
                    email_verified=TRUE, phone_verified=TRUE, updated_at=NOW()
                WHERE id=?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, security.hash(input.password))
                statement.setString(2, security.hash(input.pin))
                statement.setLong(3, existingId)
                statement.executeUpdate()
            }
            existingId
        } else {
            connection.prepareStatement(
                """
                INSERT INTO usuarios(username,email,password_hash,pin_hash,role,admin_subrole,account_kind,
                    account_status,verification_status,email_verified,phone_verified)
                VALUES (?,?,?,?,'ADMIN','GENERAL','OPERATIONAL','ACTIVE','VERIFIED',TRUE,TRUE)
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "ADMIN_" + java.util.UUID.randomUUID().toString().replace("-", "").take(18))
                statement.setString(2, input.email)
                statement.setString(3, security.hash(input.password))
                statement.setString(4, security.hash(input.pin))
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
        }

        val fullName = listOf(input.firstName, input.middleName, input.lastName, input.secondLastName).joinToString(" ")
        connection.prepareStatement(
            """
            INSERT INTO perfiles_usuario(user_id,full_name,first_name,middle_name,last_name,second_last_name,phone,birth_date)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT (user_id) DO UPDATE SET
                full_name=EXCLUDED.full_name,
                first_name=EXCLUDED.first_name,
                middle_name=EXCLUDED.middle_name,
                last_name=EXCLUDED.last_name,
                second_last_name=EXCLUDED.second_last_name,
                phone=EXCLUDED.phone,
                birth_date=EXCLUDED.birth_date,
                updated_at=NOW()
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, fullName)
            statement.setString(3, input.firstName)
            statement.setString(4, input.middleName)
            statement.setString(5, input.lastName)
            statement.setString(6, input.secondLastName)
            statement.setString(7, input.phone)
            statement.setObject(8, input.birthDate)
            statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO perfiles_financieros_usuario(user_id) VALUES (?) ON CONFLICT DO NOTHING").use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
        println("Administrador ${if (existingId == null) "creado" else "actualizado"}: ${input.email}")
    }
}

private fun readRequired(prompt: String): String {
    print(prompt)
    val value = readlnOrNull()?.trim().orEmpty()
    require(value.isNotBlank()) { "Este dato es obligatorio." }
    return value
}

private fun readSecret(prompt: String): String {
    val console = System.console()
    return if (console != null) {
        console.readPassword(prompt)?.concatToString().orEmpty()
    } else {
        print(prompt)
        readlnOrNull().orEmpty()
    }
}

private fun askYesNo(prompt: String): Boolean {
    print(prompt)
    return readlnOrNull()?.trim()?.uppercase() in setOf("S", "SI", "SÍ", "Y", "YES")
}

private fun normalizePhone(raw: String): String? {
    var digits = raw.filter(Char::isDigit)
    if (digits.startsWith("0058")) digits = digits.drop(4)
    else if (digits.startsWith("58")) digits = digits.drop(2)
    else if (digits.startsWith("0")) digits = digits.drop(1)
    if (digits.length != 10 || !digits.startsWith("4")) return null
    return "+58 ${digits.take(3)}-${digits.drop(3)}"
}
