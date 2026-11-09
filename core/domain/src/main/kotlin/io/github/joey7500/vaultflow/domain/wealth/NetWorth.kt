package io.github.joey7500.vaultflow.domain.wealth

import io.github.joey7500.vaultflow.domain.model.ManualAsset
import io.github.joey7500.vaultflow.domain.model.ManualAssetKind
import kotlin.math.roundToLong

enum class Pillar(val label: String) {
    TRADING212("Trading 212"),
    GOLD("Physical gold"),
    BANKS("Bank accounts"),
    BUILDING_SAVINGS("Building savings"),
    OTHER("Other assets"),
}

data class PillarValue(val pillar: Pillar, val valueMinor: Long, val isStale: Boolean = false)

data class NetWorth(val pillars: List<PillarValue>) {
    val totalMinor: Long get() = pillars.sumOf { it.valueMinor }

    fun share(pillar: Pillar): Double {
        val total = totalMinor
        if (total <= 0) return 0.0
        return (pillars.firstOrNull { it.pillar == pillar }?.valueMinor ?: 0L).toDouble() / total
    }
}

object NetWorthCalculator {
    fun compute(
        trading212Minor: Long?,
        goldMinor: Long?,
        bankBalancesMinor: Long,
        manualAssets: List<ManualAsset>,
        trading212Stale: Boolean = false,
        goldStale: Boolean = false,
    ): NetWorth {
        val building = manualAssets.filter { it.kind == ManualAssetKind.BUILDING_SAVINGS }.sumOf { it.valueMinor }
        val other = manualAssets.filter { it.kind == ManualAssetKind.OTHER }.sumOf { it.valueMinor }
        return NetWorth(
            listOf(
                PillarValue(Pillar.TRADING212, trading212Minor ?: 0L, trading212Stale),
                PillarValue(Pillar.GOLD, goldMinor ?: 0L, goldStale),
                PillarValue(Pillar.BANKS, bankBalancesMinor),
                PillarValue(Pillar.BUILDING_SAVINGS, building),
                PillarValue(Pillar.OTHER, other),
            ),
        )
    }
}

object GoldMath {
    const val GRAMS_PER_TROY_OUNCE = 31.1034768

    /** USD per troy ounce × CZK per USD -> CZK per gram. */
    fun czkPerGram(usdPerOunce: Double, czkPerUsd: Double): Double = usdPerOunce * czkPerUsd / GRAMS_PER_TROY_OUNCE

    /** Value of [grams] in CZK minor units. */
    fun valueMinor(grams: Double, czkPerGram: Double): Long = (grams * czkPerGram * 100).roundToLong()
}
