package com.impulsosocial.server.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class UserDeletionPolicySourceTest {
    @Test
    fun `account deletion preserves historical activity and revokes only active account state`() {
        val source = File("src/main/kotlin/com/impulsosocial/server/service/AppService.kt").readText()

        // La cuenta deja de estar activa, pero el registro base se conserva para que las
        // compras, cuotas, jornadas, QR, comprobantes y auditorías mantengan su referencia.
        assertTrue(source.contains("account_status='DELETED'"))
        assertTrue(source.contains("USER_SOFT_DELETED"))
        assertTrue(source.contains("historial conservado"))
        assertTrue(source.contains("UPDATE sesiones_usuario SET revoked_at=COALESCE(revoked_at,NOW())"))

        // Nunca se debe borrar físicamente la fila principal de un usuario histórico desde
        // deleteUserPermanently. La única limpieza física de usuarios permitida en el servicio
        // pertenece al alta PENDING_VERIFICATION abortada, que aún no tiene historial.
        val deletionFlow = source.substringAfter("fun deleteUserPermanently").substringBefore("fun suspendAccountForNonPayment")
        assertFalse(deletionFlow.contains("DELETE FROM usuarios"))
    }
}
