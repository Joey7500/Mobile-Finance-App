package io.github.joey7500.vaultflow.data.repository

import io.github.joey7500.vaultflow.core.security.SecretVault
import io.github.joey7500.vaultflow.data.local.VaultDatabase
import io.github.joey7500.vaultflow.data.local.entity.SnapshotEntity
import io.github.joey7500.vaultflow.data.local.toDomain
import io.github.joey7500.vaultflow.data.remote.CnbClient
import io.github.joey7500.vaultflow.data.remote.GoldPriceClient
import io.github.joey7500.vaultflow.data.remote.Trading212Client
import io.github.joey7500.vaultflow.domain.Money
import io.github.joey7500.vaultflow.domain.demo.DemoData
import io.github.joey7500.vaultflow.domain.fx.FxRates
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.ManualAsset
import io.github.joey7500.vaultflow.domain.wealth.GoldMath
import io.github.joey7500.vaultflow.domain.wealth.NetWorth
import io.github.joey7500.vaultflow.domain.wealth.NetWorthCalculator
import io.github.joey7500.vaultflow.domain.wealth.Pillar
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class Trading212Info(
    val valueCzkMinor: Long?,
    val nativeValue: Double?,
    val currency: String?,
    val profitLossNative: Double?,
    val updatedAt: Long?,
    val hasCredentials: Boolean,
    val isManual: Boolean,
)

data class GoldInfo(val grams: Double, val czkPerGram: Double?, val isManualPrice: Boolean, val updatedAt: Long?) {
    val valueMinor: Long? get() = czkPerGram?.let { GoldMath.valueMinor(grams, it) }
}

data class WealthState(
    val netWorth: NetWorth,
    val trading212: Trading212Info,
    val gold: GoldInfo,
    val accounts: List<Pair<Account, Long>>,
    val manualAssets: List<ManualAsset>,
    val history: List<SnapshotEntity>,
    val lastRefreshAt: Long?,
)

/** Tab 2: overall accumulated wealth. All values are converted to CZK. */
class WealthRepository(
    private val db: VaultDatabase,
    private val settings: SettingsStore,
    private val finance: FinanceRepository,
    private val vault: SecretVault,
    private val isDemo: Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    val state: Flow<WealthState> = combine(
        settings.all,
        finance.accounts,
        finance.accountBalances,
        finance.manualAssets,
        db.snapshots().observeAll(),
    ) { s, accounts, balances, assets, history ->
        val hasCreds = isDemo || vault.has(SecretVault.T212_API_KEY)
        val manualT212 = s[SettingsStore.T212_MANUAL_VALUE_MINOR]?.toLongOrNull()
        val t212 = Trading212Info(
            valueCzkMinor = s[SettingsStore.T212_LAST_VALUE_CZK_MINOR]?.toLongOrNull() ?: manualT212,
            nativeValue = s[SettingsStore.T212_LAST_NATIVE]?.toDoubleOrNull(),
            currency = s[SettingsStore.T212_LAST_CURRENCY],
            profitLossNative = s[SettingsStore.T212_LAST_PNL_NATIVE]?.toDoubleOrNull(),
            updatedAt = s[SettingsStore.T212_LAST_AT]?.toLongOrNull(),
            hasCredentials = hasCreds,
            isManual = !hasCreds && manualT212 != null,
        )
        val manualGold = s[SettingsStore.GOLD_MANUAL_CZK_PER_GRAM]?.toDoubleOrNull()
        val gold = GoldInfo(
            grams = s[SettingsStore.GOLD_GRAMS]?.toDoubleOrNull() ?: 0.0,
            czkPerGram = manualGold ?: s[SettingsStore.GOLD_LAST_CZK_PER_GRAM]?.toDoubleOrNull(),
            isManualPrice = manualGold != null,
            updatedAt = s[SettingsStore.GOLD_LAST_AT]?.toLongOrNull(),
        )
        val accountValues = accounts.map { it to (balances[it.id] ?: it.anchorBalanceMinor) }
        val netWorth = NetWorthCalculator.compute(
            trading212Minor = t212.valueCzkMinor,
            goldMinor = gold.valueMinor,
            bankBalancesMinor = accountValues.sumOf { it.second },
            manualAssets = assets,
        )
        WealthState(netWorth, t212, gold, accountValues, assets, history, s[SettingsStore.LAST_REFRESH_AT]?.toLongOrNull())
    }

    suspend fun setGoldGrams(grams: Double) = settings.putDouble(SettingsStore.GOLD_GRAMS, grams)

    suspend fun setManualGoldPrice(czkPerGram: Double?) {
        if (czkPerGram == null) settings.put(SettingsStore.GOLD_MANUAL_CZK_PER_GRAM, "") else settings.putDouble(SettingsStore.GOLD_MANUAL_CZK_PER_GRAM, czkPerGram)
    }

    suspend fun setManualTrading212(valueMinor: Long?) =
        settings.put(SettingsStore.T212_MANUAL_VALUE_MINOR, valueMinor?.toString() ?: "")

    /**
     * Pulls live values (ČNB FX, Trading 212, gold spot) and stores today's
     * net-worth snapshot. Returns human-readable problems, empty when all went well.
     */
    suspend fun refresh(): List<String> {
        val problems = mutableListOf<String>()
        val now = System.currentTimeMillis()

        if (isDemo) {
            settings.putLong(SettingsStore.T212_LAST_VALUE_CZK_MINOR, DemoData.trading212ValueMinor)
            settings.putDouble(SettingsStore.T212_LAST_NATIVE, Money.toUnits(DemoData.trading212ValueMinor))
            settings.put(SettingsStore.T212_LAST_CURRENCY, "CZK")
            settings.putLong(SettingsStore.T212_LAST_AT, now)
            settings.putDouble(SettingsStore.GOLD_LAST_CZK_PER_GRAM, GoldMath.czkPerGram(DemoData.GOLD_USD_PER_OZ, DemoData.CZK_PER_USD))
            settings.putLong(SettingsStore.GOLD_LAST_AT, now)
            settings.putLong(SettingsStore.LAST_REFRESH_AT, now)
            saveSnapshot()
            return problems
        }

        val fx: FxRates? = try {
            CnbClient.dailyRates().also { settings.saveFx(it, now) }
        } catch (e: Exception) {
            problems += "ČNB exchange rates: ${e.message ?: "unavailable"}"
            settings.cachedFx()
        }

        val key = vault.get(SecretVault.T212_API_KEY)
        if (key != null) {
            try {
                val summary = Trading212Client.accountSummary(key, vault.get(SecretVault.T212_API_SECRET))
                val currency = summary.currency ?: "CZK"
                val native = summary.totalValue
                    ?: ((summary.investments?.currentValue ?: 0.0) + (summary.cash?.availableToTrade ?: 0.0))
                val czkMinor = (native * 100).roundToLong().let { minor ->
                    if (currency == "CZK") minor else fx?.toCzkMinor(minor, currency)
                }
                if (czkMinor == null) {
                    problems += "Trading 212: no exchange rate for $currency"
                } else {
                    settings.putLong(SettingsStore.T212_LAST_VALUE_CZK_MINOR, czkMinor)
                    settings.putDouble(SettingsStore.T212_LAST_NATIVE, native)
                    settings.put(SettingsStore.T212_LAST_CURRENCY, currency)
                    summary.investments?.unrealizedProfitLoss?.let { settings.putDouble(SettingsStore.T212_LAST_PNL_NATIVE, it) }
                    settings.putLong(SettingsStore.T212_LAST_AT, now)
                }
            } catch (e: Exception) {
                problems += e.message ?: "Trading 212 unavailable"
            }
        }

        val grams = settings.getDouble(SettingsStore.GOLD_GRAMS, 0.0)
        val manualPrice = settings.get(SettingsStore.GOLD_MANUAL_CZK_PER_GRAM)?.toDoubleOrNull()
        if (grams > 0 && manualPrice == null) {
            try {
                val usdPerOz = GoldPriceClient.usdPerOunce()
                val czkPerUsd = fx?.rate("USD") ?: throw IllegalStateException("no USD/CZK rate")
                settings.putDouble(SettingsStore.GOLD_LAST_CZK_PER_GRAM, GoldMath.czkPerGram(usdPerOz, czkPerUsd))
                settings.putLong(SettingsStore.GOLD_LAST_AT, now)
            } catch (e: Exception) {
                problems += "Gold price: ${e.message ?: "unavailable"} (you can set a manual price)"
            }
        }

        settings.putLong(SettingsStore.LAST_REFRESH_AT, now)
        saveSnapshot()
        return problems
    }

    /** Checks Trading 212 credentials without storing anything. */
    suspend fun testTrading212(key: String, secret: String?): String {
        val s = Trading212Client.accountSummary(key, secret)
        return "Connected · account ${s.currency ?: ""} · total ${s.totalValue ?: "?"}"
    }

    private suspend fun saveSnapshot() {
        val current = latest() ?: return
        fun v(p: Pillar) = current.pillars.firstOrNull { it.pillar == p }?.valueMinor ?: 0L
        db.snapshots().upsert(
            SnapshotEntity(
                epochDay = LocalDate.now(zone).toEpochDay(),
                totalMinor = current.totalMinor,
                trading212Minor = v(Pillar.TRADING212),
                goldMinor = v(Pillar.GOLD),
                banksMinor = v(Pillar.BANKS),
                buildingSavingsMinor = v(Pillar.BUILDING_SAVINGS),
                otherMinor = v(Pillar.OTHER),
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun latest(): NetWorth? {
        val accounts = db.accounts().getAll()
        val txs = db.transactions().getAll()
        val bank = accounts.sumOf { a ->
            a.anchorBalanceMinor + txs.filter { it.accountId == a.id && it.timestampMillis > a.anchorDateMillis }.sumOf { it.amountCzkMinor }
        }
        val grams = settings.getDouble(SettingsStore.GOLD_GRAMS, 0.0)
        val price = settings.get(SettingsStore.GOLD_MANUAL_CZK_PER_GRAM)?.toDoubleOrNull()
            ?: settings.get(SettingsStore.GOLD_LAST_CZK_PER_GRAM)?.toDoubleOrNull()
        val t212 = settings.get(SettingsStore.T212_LAST_VALUE_CZK_MINOR)?.toLongOrNull()
            ?: settings.get(SettingsStore.T212_MANUAL_VALUE_MINOR)?.toLongOrNull()
        return NetWorthCalculator.compute(
            trading212Minor = t212,
            goldMinor = price?.let { GoldMath.valueMinor(grams, it) },
            bankBalancesMinor = bank,
            manualAssets = db.manualAssets().getAll().map { it.toDomain() },
        )
    }
}
