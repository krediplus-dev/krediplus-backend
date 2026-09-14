package com.impulsosocial.server

import com.impulsosocial.server.config.AppConfig
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.postgresql.PGConnection
import org.slf4j.Logger

/**
 * Mantiene una sola conexión PostgreSQL LISTEN para todos los navegadores.
 * La conexión no pertenece al pool Hikari, pero usa exactamente el mismo endpoint
 * y credenciales resueltos por AppConfig.
 *
 * 7.2.2: una caída o sustitución temporal de PostgreSQL no genera una tormenta de
 * reconexiones. El backoff solo vuelve a 2 s después de una conexión realmente estable.
 */
class OperationalRealtimeNotifier(
    private val config: AppConfig,
    private val logger: Logger
) {
    private val subscribers = ConcurrentHashMap.newKeySet<Channel<String>>()
    private val activeConnection = AtomicReference<Connection?>(null)
    private val listenerJob = AtomicReference<Job?>(null)

    @Volatile private var stopping = false

    fun subscribe(): Channel<String> = Channel<String>(Channel.CONFLATED).also(subscribers::add)

    fun unsubscribe(channel: Channel<String>) {
        subscribers.remove(channel)
        channel.close()
    }

    fun start(scope: CoroutineScope) {
        val current = listenerJob.get()
        if (current?.isActive == true) return

        stopping = false
        val job = scope.launch(Dispatchers.IO) {
            var retryDelayMs = 2_000L
            var lastWarnAtMs = 0L
            while (isActive && !stopping) {
                var connectedAtNanos = 0L
                try {
                    openRealtimeConnection("KrediPlus-Realtime-Operational").use { connection ->
                        activeConnection.set(connection)
                        try {
                            connection.autoCommit = true
                            check(connection.isValid(5)) { "PostgreSQL no respondió a la validación del canal de tiempo real." }
                            connection.createStatement().use { statement ->
                                statement.execute("LISTEN credicash_operational_changed")
                            }
                            val postgres = connection.unwrap(PGConnection::class.java)
                            connectedAtNanos = System.nanoTime()
                            logger.info("Kredi+: canal privado de cambios operativos conectado.")

                            while (isActive && !stopping && !connection.isClosed) {
                                val notifications = postgres.getNotifications(15_000) ?: emptyArray()
                                notifications.forEach { notification ->
                                    broadcast(notification.parameter ?: "{\"changed\":true}")
                                }
                            }
                        } finally {
                            activeConnection.compareAndSet(connection, null)
                        }
                    }
                } catch (error: Throwable) {
                    if (!isActive || stopping) break
                    val stableForMs = if (connectedAtNanos == 0L) 0L else (System.nanoTime() - connectedAtNanos) / 1_000_000L
                    if (stableForMs >= 60_000L) retryDelayMs = 2_000L
                    val waitMs = retryDelayMs
                    val now = System.currentTimeMillis()
                    if (lastWarnAtMs == 0L || now - lastWarnAtMs >= 30_000L) {
                        logger.warn(
                            "Kredi+: canal operativo temporalmente no disponible; nuevo intento en {} s. Causa: {}",
                            waitMs / 1_000L,
                            safeErrorMessage(error)
                        )
                        lastWarnAtMs = now
                    } else {
                        logger.debug("Canal operativo aún no disponible; reintento en {} ms: {}", waitMs, safeErrorMessage(error))
                    }
                    delay(waitMs)
                    retryDelayMs = (retryDelayMs * 2L).coerceAtMost(60_000L)
                }
            }
        }

        if (!listenerJob.compareAndSet(current, job)) job.cancel()
    }

    fun stop() {
        stopping = true
        listenerJob.getAndSet(null)?.cancel()
        activeConnection.getAndSet(null)?.let { connection ->
            runCatching { connection.close() }
                .onFailure { error -> logger.debug("No fue posible cerrar el canal operativo ya detenido: {}", error.message) }
        }
        subscribers.toList().forEach(::unsubscribe)
        logger.info("Kredi+: canal privado de cambios operativos detenido limpiamente.")
    }

    private fun openRealtimeConnection(applicationName: String): Connection {
        val properties = Properties().apply {
            setProperty("user", config.dbUser)
            setProperty("password", config.dbPassword)
            setProperty("ApplicationName", applicationName)
            setProperty("connectTimeout", "5")
            setProperty("tcpKeepAlive", "true")
        }
        return DriverManager.getConnection(config.dbUrl, properties)
    }

    private fun safeErrorMessage(error: Throwable): String =
        error.message?.replace(Regex("(?i)(password|pwd)=[^&\\s]+"), "$1=***")?.take(240)
            ?: error::class.simpleName.orEmpty()

    private fun broadcast(rawPayload: String) {
        val payload = rawPayload.replace('\r', ' ').replace('\n', ' ')
        val stale = mutableListOf<Channel<String>>()
        subscribers.forEach { subscriber -> if (subscriber.trySend(payload).isFailure) stale += subscriber }
        stale.forEach(::unsubscribe)
    }
}
