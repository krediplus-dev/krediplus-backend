package com.impulsosocial.server.integrations

import com.google.gson.JsonParser
import com.impulsosocial.server.CREDICASH_APP_VERSION
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

class BcvRateService {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val cache = AtomicReference<CachedRate?>(null)
    private val diskCache = File("data/bcv_rate_cache.json")

    fun currentUsdRate(): BcvRate {
        val now = Instant.now()
        cache.get()
            ?.takeIf { Duration.between(it.fetchedAt, now) < Duration.ofMinutes(30) }
            ?.let { return it.rate }

        val providers = buildList<() -> BcvRate> {
            add(::fetchDolarApi)
            backupApiUrl()?.let { add { fetchBackupApi(it) } }
        }
        val fetched = providers.asSequence()
            .mapNotNull { provider -> runCatching(provider).getOrNull() }
            .firstOrNull { it.rate.isFinite() && it.rate > 0.0 }

        if (fetched != null) {
            cache.set(CachedRate(fetched, now))
            persist(fetched)
            return fetched
        }

        cache.get()
            ?.takeIf { Duration.between(it.fetchedAt, now) <= MAX_FALLBACK_AGE }
            ?.rate
            ?.let { return it }
        loadPersisted()?.let { persisted ->
            cache.set(CachedRate(persisted.rate, persisted.cachedAt))
            return persisted.rate
        }
        error("No fue posible consultar la tasa oficial BCV. Intenta nuevamente en unos segundos.")
    }

    private fun fetchDolarApi(): BcvRate {
        val json = getJson("https://ve.dolarapi.com/v1/dolares/oficial")
        val rate = listOf("promedio", "venta", "compra")
            .asSequence()
            .mapNotNull { key -> runCatching { json.get(key)?.takeIf { !it.isJsonNull }?.asDouble }.getOrNull() }
            .firstOrNull { it > 0.0 }
            ?: error("DolarApi no devolvió una tasa válida")
        val date = json.get("fechaActualizacion")?.asString?.takeIf { it.isNotBlank() }
            ?: OffsetDateTime.now().toString()
        return BcvRate(rate, date, "BCV · DolarApi")
    }

    private fun backupApiUrl(): String? =
        System.getenv("BCV_BACKUP_API_URL")?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") }

    private fun fetchBackupApi(baseUrl: String): BcvRate {
        val endpoint = if (baseUrl.endsWith("/v1/rates/usd")) baseUrl else "$baseUrl/v1/rates/usd"
        val json = getJson(endpoint)
        val rate = json.get("rate")?.asDouble?.takeIf { it > 0.0 }
            ?: error("API secundaria sin tasa válida")
        val date = json.get("date")?.asString.orEmpty().ifBlank { OffsetDateTime.now().toString() }
        return BcvRate(rate, date, "BCV · respaldo")
    }

    private fun getJson(url: String) = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(8))
        .header("Accept", "application/json")
        .header("User-Agent", "KrediPlus/$CREDICASH_APP_VERSION")
        .GET()
        .build()
        .let { request -> http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)) }
        .also { response ->
            if (response.statusCode() !in 200..299) {
                error("Servicio BCV respondió ${response.statusCode()}")
            }
        }
        .body()
        .let(JsonParser::parseString)
        .asJsonObject

    private fun persist(rate: BcvRate) = runCatching {
        diskCache.parentFile?.mkdirs()
        diskCache.writeText(
            """{"rate":${rate.rate},"date":${quote(rate.date)},"source":${quote(rate.source)},"cachedAt":${quote(Instant.now().toString())}}""",
            StandardCharsets.UTF_8
        )
    }

    private fun loadPersisted(): PersistedRate? = runCatching {
        if (!diskCache.exists()) return@runCatching null
        val json = JsonParser.parseString(diskCache.readText(StandardCharsets.UTF_8)).asJsonObject
        val cachedAt = json.get("cachedAt")?.asString
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.ofEpochMilli(diskCache.lastModified().coerceAtLeast(0L))
        if (Duration.between(cachedAt, Instant.now()) > MAX_FALLBACK_AGE) return@runCatching null
        val rate = BcvRate(
            rate = json.get("rate")?.asDouble ?: return@runCatching null,
            date = json.get("date")?.asString.orEmpty(),
            source = json.get("source")?.asString.orEmpty().ifBlank { "BCV · caché" }
        ).takeIf { it.rate.isFinite() && it.rate > 0.0 } ?: return@runCatching null
        PersistedRate(rate, cachedAt)
    }.getOrNull()

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    fun cacheStatus(): String {
        val now = Instant.now()
        if (cache.get()?.let { Duration.between(it.fetchedAt, now) <= MAX_FALLBACK_AGE } == true) return "cached"
        if (loadPersisted() != null) return "disk_cache"
        return "not_checked"
    }

    private data class CachedRate(val rate: BcvRate, val fetchedAt: Instant)
    private data class PersistedRate(val rate: BcvRate, val cachedAt: Instant)

    companion object {
        private val MAX_FALLBACK_AGE: Duration = Duration.ofHours(24)
    }
}

data class BcvRate(val rate: Double, val date: String, val source: String)
