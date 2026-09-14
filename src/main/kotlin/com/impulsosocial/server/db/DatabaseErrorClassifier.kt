package com.impulsosocial.server.db

enum class DatabaseErrorKind {
    PROTECTED_HISTORY,
    DATA_INTEGRITY,
    SCHEMA_UPDATING,
    DUPLICATE_OPERATION,
    CONCURRENT_UPDATE,
    INVALID_DATA,
    UNKNOWN
}

fun classifyDatabaseError(sqlState: String?, message: String?): DatabaseErrorKind {
    val normalizedMessage = message.orEmpty().lowercase()
    return when {
        sqlState == "23503" -> DatabaseErrorKind.PROTECTED_HISTORY
        sqlState == "P0001" && (
            normalizedMessage.contains("inmutable") ||
                normalizedMessage.contains("immutable") ||
                normalizedMessage.contains("históric") ||
                normalizedMessage.contains("histor")
            ) -> DatabaseErrorKind.PROTECTED_HISTORY
        sqlState == "23514" -> DatabaseErrorKind.DATA_INTEGRITY
        sqlState == "23505" -> DatabaseErrorKind.DUPLICATE_OPERATION
        sqlState in setOf("42703", "42P01", "42883") -> DatabaseErrorKind.SCHEMA_UPDATING
        sqlState in setOf("40001", "40P01") -> DatabaseErrorKind.CONCURRENT_UPDATE
        sqlState in setOf("23502", "22001", "22P02") -> DatabaseErrorKind.INVALID_DATA
        else -> DatabaseErrorKind.UNKNOWN
    }
}
