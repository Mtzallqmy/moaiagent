package com.agentdroid.integration

import android.content.Context
import com.agentdroid.core.ai.UsageRecord
import com.agentdroid.core.model.ProviderKind

/** User-supplied pricing avoids silently hard-coding provider prices which change over time. */
data class ModelTokenRate(val inputUsdPerMillion: Double = 0.0, val outputUsdPerMillion: Double = 0.0)
data class UsageCostSummary(val usd: Double, val unpricedRecords: Int)

class UsageCostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agentdroid_usage_pricing", Context.MODE_PRIVATE)

    fun rate(kind: ProviderKind, modelId: String): ModelTokenRate? {
        val key = key(kind, modelId)
        if (!prefs.contains("$key.in") && !prefs.contains("$key.out")) return null
        return ModelTokenRate(
            java.lang.Double.longBitsToDouble(prefs.getLong("$key.in", java.lang.Double.doubleToLongBits(0.0))),
            java.lang.Double.longBitsToDouble(prefs.getLong("$key.out", java.lang.Double.doubleToLongBits(0.0)))
        )
    }

    fun setRate(kind: ProviderKind, modelId: String, rate: ModelTokenRate) {
        require(rate.inputUsdPerMillion >= 0 && rate.outputUsdPerMillion >= 0)
        val key = key(kind, modelId)
        prefs.edit()
            .putLong("$key.in", java.lang.Double.doubleToLongBits(rate.inputUsdPerMillion))
            .putLong("$key.out", java.lang.Double.doubleToLongBits(rate.outputUsdPerMillion))
            .apply()
    }

    fun estimate(records: List<UsageRecord>): UsageCostSummary {
        var usd = 0.0
        var unpriced = 0
        records.forEach { record ->
            val rate = rate(record.providerKind, record.modelId)
            if (rate == null) {
                unpriced++
            } else {
                usd += (record.usage.promptTokens ?: 0) * rate.inputUsdPerMillion / 1_000_000.0
                usd += (record.usage.completionTokens ?: 0) * rate.outputUsdPerMillion / 1_000_000.0
            }
        }
        return UsageCostSummary(usd, unpriced)
    }

    private fun key(kind: ProviderKind, modelId: String): String {
        val safeModel = modelId.lowercase().replace(Regex("[^a-z0-9._-]+"), "_").take(160)
        return "${kind.name.lowercase()}.$safeModel"
    }
}
