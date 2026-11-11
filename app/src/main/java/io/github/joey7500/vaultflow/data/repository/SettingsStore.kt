package io.github.joey7500.vaultflow.data.repository

import io.github.joey7500.vaultflow.data.local.dao.SettingDao
import io.github.joey7500.vaultflow.data.local.entity.SettingEntity
import io.github.joey7500.vaultflow.domain.fx.FxRates
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Key/value settings stored INSIDE the encrypted database (gold grams, floor, cached values...). */
class SettingsStore(private val dao: SettingDao) {

    val all: Flow<Map<String, String>> = dao.observeAll().map { list -> list.associate { it.key to it.value } }

    suspend fun get(key: String): String? = dao.get(key)
    suspend fun put(key: String, value: String) = dao.put(SettingEntity(key, value))

    suspend fun getLong(key: String, default: Long): Long = get(key)?.toLongOrNull() ?: default
    suspend fun putLong(key: String, value: Long) = put(key, value.toString())
    suspend fun getDouble(key: String, default: Double): Double = get(key)?.toDoubleOrNull() ?: default
    suspend fun putDouble(key: String, value: Double) = put(key, value.toString())

    suspend fun saveFx(fx: FxRates, fetchedAt: Long) {
        put(FX_RATES, fx.czkPerUnit.entries.joinToString("\n") { "${it.key}=${it.value}" })
        put(FX_DATE, fx.date?.toString() ?: "")
        putLong(FX_FETCHED_AT, fetchedAt)
    }

    suspend fun cachedFx(): FxRates? {
        val text = get(FX_RATES)?.takeIf { it.isNotBlank() } ?: return null
        val map = text.lines().mapNotNull { line ->
            val (k, v) = line.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            v.toDoubleOrNull()?.let { k to it }
        }.toMap()
        val date = get(FX_DATE)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return FxRates(date, map)
    }

    companion object {
        const val RESERVE_FLOOR_MINOR = "reserve_floor_minor"
        const val GOLD_GRAMS = "gold_grams"
        const val GOLD_MANUAL_CZK_PER_GRAM = "gold_manual_czk_per_gram"
        const val GOLD_LAST_CZK_PER_GRAM = "gold_last_czk_per_gram"
        const val GOLD_LAST_AT = "gold_last_at"
        const val T212_LAST_VALUE_CZK_MINOR = "t212_last_value_czk_minor"
        const val T212_LAST_NATIVE = "t212_last_native"
        const val T212_LAST_CURRENCY = "t212_last_currency"
        const val T212_LAST_PNL_NATIVE = "t212_last_pnl_native"
        const val T212_LAST_AT = "t212_last_at"
        const val T212_MANUAL_VALUE_MINOR = "t212_manual_value_minor"
        const val FX_RATES = "fx_rates"
        const val FX_DATE = "fx_date"
        const val FX_FETCHED_AT = "fx_fetched_at"
        const val LAST_REFRESH_AT = "last_refresh_at"
        const val CAPTURE_LOG_ENABLED = "capture_log_enabled"
        const val EMAIL_LAST_SYNC_AT = "email_last_sync_at"
        const val PERIOD_START_DAY = "period_start_day"
        const val DUPLICATE_REPAIR_DONE = "duplicate_repair_v1"
        const val DRIVE_CONNECTED = "drive_connected"
        const val DRIVE_LAST_BACKUP_AT = "drive_last_backup_at"
        const val DRIVE_LAST_RESULT = "drive_last_result"
        const val INCOME_GRACE_DAYS = "income_grace_days"
        const val EMAIL_LAST_RESULT = "email_last_result"
    }
}
