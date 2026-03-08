package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.AppConfig
import org.slf4j.LoggerFactory

data class QuotaStatus(
    val used: Int,
    val limit: Int,
    val remaining: Int,
    val exhausted: Boolean
)

object QuotaTracker {
    private val log = LoggerFactory.getLogger(QuotaTracker::class.java)
    var clock: Clock = SystemClock
    const val DAILY_LIMIT = 100

    init {
        MetricsRegistry.registry.gauge("mm_upc_quota_remaining", this) {
            try {
                (DAILY_LIMIT - getCount()).coerceAtLeast(0).toDouble()
            } catch (_: Exception) { 0.0 }
        }
    }
    private const val KEY_COUNT = "upc_lookups_today"
    private const val KEY_DATE = "upc_lookup_date"

    @Synchronized
    fun getStatus(): QuotaStatus {
        resetIfNewDay()
        val used = getCount()
        val remaining = (DAILY_LIMIT - used).coerceAtLeast(0)
        return QuotaStatus(used, DAILY_LIMIT, remaining, remaining == 0)
    }

    @Synchronized
    fun increment(): QuotaStatus {
        resetIfNewDay()
        val newCount = getCount() + 1
        setConfigValue(KEY_COUNT, newCount.toString())
        val remaining = (DAILY_LIMIT - newCount).coerceAtLeast(0)
        return QuotaStatus(newCount, DAILY_LIMIT, remaining, remaining == 0)
    }

    private fun resetIfNewDay() {
        val today = clock.today().toString()
        val storedDate = getConfigValue(KEY_DATE)
        if (storedDate != today) {
            setConfigValue(KEY_DATE, today)
            setConfigValue(KEY_COUNT, "0")
            log.info("Quota reset for new day: $today")
        }
    }

    private fun getCount(): Int {
        return getConfigValue(KEY_COUNT)?.toIntOrNull() ?: 0
    }

    private fun getConfigValue(key: String): String? {
        return AppConfig.findAll()
            .firstOrNull { it.config_key == key }
            ?.config_val
    }

    private fun setConfigValue(key: String, value: String) {
        val existing = AppConfig.findAll().firstOrNull { it.config_key == key }
        if (existing != null) {
            existing.config_val = value
            existing.save()
        } else {
            AppConfig(config_key = key, config_val = value).save()
        }
    }
}
