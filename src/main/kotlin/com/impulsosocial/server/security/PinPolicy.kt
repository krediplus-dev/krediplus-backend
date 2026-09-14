package com.impulsosocial.server.security

object PinPolicy {
    private val sequentialPins = buildSet {
        val ascending = "0123456789012345"
        val descending = "9876543210987654"
        for (index in 0..9) {
            add(ascending.substring(index, index + 6))
            add(descending.substring(index, index + 6))
        }
    }
    private val commonPins = setOf("123123", "121212", "112233", "101010", "696969")

    fun validationError(pin: String): String? = when {
        !pin.matches(Regex("\\d{6}")) -> "El PIN debe contener exactamente 6 dígitos."
        pin.toSet().size == 1 -> "El PIN no puede repetir el mismo dígito seis veces."
        pin in sequentialPins -> "El PIN no puede ser una secuencia ascendente o descendente."
        pin in commonPins -> "El PIN elegido es demasiado fácil de adivinar."
        else -> null
    }
}
