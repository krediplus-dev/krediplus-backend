package com.impulsosocial.server

import io.ktor.server.application.Application
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Punto de entrada explícito del servidor local y Railway.
 *
 * La API escucha en 0.0.0.0 y separa los hilos de conexiones, red y llamadas.
 * Esto evita que operaciones JDBC o integraciones externas reduzcan la capacidad
 * de aceptar nuevas solicitudes bajo carga, sin cambiar ningún contrato HTTP.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("SERVER_HOST")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0.0.0"
    val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val connectionThreads = envInt("SERVER_CONNECTION_THREADS", 1, 1, 4)
    val workerThreads = envInt("SERVER_WORKER_THREADS", maxOf(2, processors * 2), 2, 32)
    val callThreads = envInt("SERVER_CALL_THREADS", maxOf(8, processors * 4), 4, 64)

    println(
        "Kredi+: iniciando API en $host:$port " +
            "(connection=$connectionThreads, worker=$workerThreads, calls=$callThreads)"
    )
    embeddedServer(
        factory = Netty,
        configure = {
            connectors.add(
                EngineConnectorBuilder().apply {
                    this.host = host
                    this.port = port
                }
            )
            connectionGroupSize = connectionThreads
            workerGroupSize = workerThreads
            callGroupSize = callThreads
            shutdownGracePeriod = 5_000
            shutdownTimeout = 15_000
        },
        module = Application::module
    ).start(wait = true)
}

private fun envInt(name: String, fallback: Int, minimum: Int, maximum: Int): Int =
    System.getenv(name)?.trim()?.toIntOrNull()?.coerceIn(minimum, maximum)
        ?: fallback.coerceIn(minimum, maximum)
