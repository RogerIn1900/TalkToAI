package com.example.talktoai.market

import android.content.Context
import org.json.JSONObject

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
        if (!MarketCachePolicy.isReusable(period, from != null && to != null, storedAtMs, nowMs())) return null
        return runCatching { JSONObject(record.getString(FIELD_PAYLOAD)).put(FIELD_CACHE_HIT, true) }.getOrNull()
    }

    fun put(symbol: String, period: String, from: String?, to: String?, payload: JSONObject) {
        val record = JSONObject()
            .put(FIELD_STORED_AT_MS, nowMs())
            .put(FIELD_PAYLOAD, payload.toString())
        preferences.edit().putString(key(symbol, period, from, to), record.toString()).apply()
    }

    private fun key(symbol: String, period: String, from: String?, to: String?): String =
        listOf(symbol.uppercase(), period, from.orEmpty(), to.orEmpty()).joinToString(KEY_SEPARATOR)

    companion object {
        private const val PREFERENCES_NAME = "talktoai_market_cache_v1"
        private const val FIELD_STORED_AT_MS = "storedAtMs"
        private const val FIELD_PAYLOAD = "payload"
        const val FIELD_CACHE_HIT = "clientCacheHit"
        private const val KEY_SEPARATOR = "|"
    }
}

internal object MarketCachePolicy {
    private const val INTRADAY_MAX_AGE_MS = 60_000L
    private const val CURRENT_PERIOD_MAX_AGE_MS = 15L * 60_000L
    private const val HISTORICAL_RANGE_MAX_AGE_MS = 7L * 24L * 60L * 60_000L

    fun isReusable(period: String, customRange: Boolean, storedAtMs: Long, nowMs: Long): Boolean {
        if (storedAtMs < 0L || nowMs < storedAtMs) return false
        val maxAgeMs = when {
            customRange -> HISTORICAL_RANGE_MAX_AGE_MS
            period == "intraday" -> INTRADAY_MAX_AGE_MS
            else -> CURRENT_PERIOD_MAX_AGE_MS
        }
        return nowMs - storedAtMs <= maxAgeMs
    }
}
