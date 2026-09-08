package com.example.talktoai.market

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Exact-query disk cache. Cached payloads preserve the server freshness fields. */
class MarketBarsCache(
    context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun get(symbol: String, period: String, from: String?, to: String?): JSONObject? {
        val raw = preferences.getString(key(symbol, period, from, to), null) ?: return null
        val record = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val storedAtMs = record.optLong(FIELD_STORED_AT_MS, -1L)
        val now = nowMs()
        val historical = MarketCachePolicy.isHistoricalRange(from, to, now)
        if (!MarketCachePolicy.isReusable(period, historical, storedAtMs, now)) return null
        return runCatching {
            JSONObject(record.getString(FIELD_PAYLOAD)).apply {
                put(FIELD_CACHE_HIT, true)
                // A cached server verdict is not a new real-time validation.
                if (optString("freshness") == "FRESH") {
                    put("freshness", "UNKNOWN")
                    put("freshnessReason", "本地缓存，实时新鲜度尚未重新验证")
                }
            }
        }.getOrNull()
    }

    fun put(symbol: String, period: String, from: String?, to: String?, payload: JSONObject) {
        val cacheKey = key(symbol, period, from, to)
        val storedAtMs = nowMs()
        val record = JSONObject()
            .put(FIELD_STORED_AT_MS, storedAtMs)
            .put(FIELD_PAYLOAD, payload.toString())
        val timestamps = preferences.all.mapNotNull { (entryKey, value) ->
            val timestamp = (value as? String)?.let { runCatching { JSONObject(it).optLong(FIELD_STORED_AT_MS, -1L) }.getOrNull() }
            timestamp?.takeIf { it >= 0L }?.let { entryKey to it }
        }.toMap().toMutableMap().apply { put(cacheKey, storedAtMs) }
        val editor = preferences.edit().putString(cacheKey, record.toString())
        MarketCachePolicy.keysToEvict(timestamps, MAX_CACHE_ENTRIES).forEach(editor::remove)
        editor.apply()
    }

    private fun key(symbol: String, period: String, from: String?, to: String?): String =
        listOf(symbol.uppercase(), period, from.orEmpty(), to.orEmpty()).joinToString(KEY_SEPARATOR)

    companion object {
        private const val PREFERENCES_NAME = "talktoai_market_cache_v1"
        private const val FIELD_STORED_AT_MS = "storedAtMs"
        private const val FIELD_PAYLOAD = "payload"
        const val FIELD_CACHE_HIT = "clientCacheHit"
        private const val KEY_SEPARATOR = "|"
        private const val MAX_CACHE_ENTRIES = 120
    }
}

internal object MarketCachePolicy {
    private val MARKET_ZONE = ZoneId.of("Asia/Shanghai")
    private const val INTRADAY_MAX_AGE_MS = 60_000L
    private const val CURRENT_PERIOD_MAX_AGE_MS = 15L * 60_000L
    private const val HISTORICAL_RANGE_MAX_AGE_MS = 7L * 24L * 60L * 60_000L

    fun isHistoricalRange(from: String?, to: String?, nowMs: Long): Boolean = runCatching {
        if (from.isNullOrBlank() || to.isNullOrBlank()) return false
        val start = LocalDate.parse(from)
        val end = LocalDate.parse(to)
        !start.isAfter(end) && end.isBefore(Instant.ofEpochMilli(nowMs).atZone(MARKET_ZONE).toLocalDate())
    }.getOrDefault(false)

    fun isReusable(period: String, customRange: Boolean, storedAtMs: Long, nowMs: Long): Boolean {
        if (storedAtMs < 0L || nowMs < storedAtMs) return false
        val maxAgeMs = when {
            customRange -> HISTORICAL_RANGE_MAX_AGE_MS
            period == "intraday" -> INTRADAY_MAX_AGE_MS
            else -> CURRENT_PERIOD_MAX_AGE_MS
        }
        return nowMs - storedAtMs <= maxAgeMs
    }

    fun keysToEvict(timestamps: Map<String, Long>, maxEntries: Int): List<String> {
        require(maxEntries > 0) { "maxEntries must be positive" }
        return timestamps.entries
            .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take((timestamps.size - maxEntries).coerceAtLeast(0))
            .map { it.key }
    }
}
