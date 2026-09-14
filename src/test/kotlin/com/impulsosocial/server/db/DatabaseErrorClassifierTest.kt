package com.impulsosocial.server.db

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseErrorClassifierTest {
    @Test
    fun `foreign key history is protected`() {
        assertEquals(DatabaseErrorKind.PROTECTED_HISTORY, classifyDatabaseError("23503", "foreign key"))
    }

    @Test
    fun `immutable trigger is protected history`() {
        assertEquals(
            DatabaseErrorKind.PROTECTED_HISTORY,
            classifyDatabaseError("P0001", "Este registro es inmutable y no puede eliminarse")
        )
    }

    @Test
    fun `schema drift is retryable migration state`() {
        assertEquals(DatabaseErrorKind.SCHEMA_UPDATING, classifyDatabaseError("42703", "column does not exist"))
        assertEquals(DatabaseErrorKind.SCHEMA_UPDATING, classifyDatabaseError("42P01", "relation does not exist"))
        assertEquals(DatabaseErrorKind.SCHEMA_UPDATING, classifyDatabaseError("42883", "function does not exist"))
    }

    @Test
    fun `oversized or malformed values are invalid data`() {
        assertEquals(DatabaseErrorKind.INVALID_DATA, classifyDatabaseError("22001", "value too long"))
        assertEquals(DatabaseErrorKind.INVALID_DATA, classifyDatabaseError("22P02", "invalid input syntax"))
    }
}
