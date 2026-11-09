package io.github.joey7500.vaultflow.domain.demo

import io.github.joey7500.vaultflow.domain.Money
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ManualAssetKind
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Synthetic, clearly fictional data for Demo Mode (screenshots, portfolio
 * reviewers, trying the app). Deterministic so screenshots are reproducible.
 * Contains no real merchants' account numbers and no real person's data.
 */
object DemoData {

    data class DemoAccount(val key: String, val name: String, val type: AccountType, val balanceMinor: Long, val liquid: Boolean)
    data class DemoTx(
        val date: LocalDate,
        val hour: Int,
        val amountMinor: Long,
        val merchant: String,
        val accountKey: String,
        val categoryKey: String?,
        val source: TransactionSource,
    )
    data class DemoAsset(val name: String, val kind: ManualAssetKind, val valueMinor: Long)

    val budgets: Map<String, Long> = mapOf(
        CategoryKeys.SALARY to Money.ofUnits(45_000L),
        CategoryKeys.OTHER_INCOME to Money.ofUnits(3_000L),
        CategoryKeys.RENT to Money.ofUnits(14_000L),
        CategoryKeys.SUBSCRIPTIONS to Money.ofUnits(1_200L),
        CategoryKeys.GROCERIES to Money.ofUnits(6_500L),
        CategoryKeys.EATING_OUT to Money.ofUnits(2_500L),
        CategoryKeys.TRAVELLING to Money.ofUnits(2_500L),
        CategoryKeys.FUN to Money.ofUnits(3_000L),
        CategoryKeys.HOLIDAY to Money.ofUnits(3_000L),
        CategoryKeys.OTHER_EXPENSES to Money.ofUnits(2_000L),
        CategoryKeys.INVESTMENTS to Money.ofUnits(10_000L),
    )

    val accounts = listOf(
        DemoAccount("main", "Demo Bank · Current", AccountType.BANK, Money.ofUnits(38_400L), true),
        DemoAccount("save", "Demo Bank · Savings", AccountType.SAVINGS, Money.ofUnits(62_000L), true),
        DemoAccount("cash", "Wallet cash", AccountType.CASH, Money.ofUnits(1_800L), true),
    )

    val manualAssets = listOf(
        DemoAsset("Building savings (demo)", ManualAssetKind.BUILDING_SAVINGS, Money.ofUnits(118_500L)),
    )

    const val GOLD_GRAMS = 31.1
    val trading212ValueMinor = Money.ofUnits(96_300L)
    const val GOLD_USD_PER_OZ = 4_100.0
    const val CZK_PER_USD = 21.4

    fun transactions(today: LocalDate, months: Int = 3, seed: Int = 42): List<DemoTx> {
        val rnd = Random(seed)
        val out = mutableListOf<DemoTx>()
        val firstMonth = today.withDayOfMonth(1).minusMonths((months - 1).toLong())

        for (m in 0 until months) {
            val start = firstMonth.plusMonths(m.toLong())
            val lastDay = if (start.year == today.year && start.month == today.month) today.dayOfMonth else start.lengthOfMonth()
            fun day(d: Int) = start.withDayOfMonth(d.coerceAtMost(lastDay).coerceAtLeast(1))
            fun add(d: Int, units: Long, merchant: String, cat: String?, src: TransactionSource = TransactionSource.DEMO, acc: String = "main") {
                if (d <= lastDay) out += DemoTx(day(d), 8 + rnd.nextInt(12), Money.ofUnits(units), merchant, acc, cat, src)
            }

            add(1, -14_000, "Rent · Demo Landlord", CategoryKeys.RENT)
            add(3, 45_000, "Salary · Demo Employer s.r.o.", CategoryKeys.SALARY)
            add(4, -10_000, "Trading 212 top-up", CategoryKeys.INVESTMENTS)
            add(5, -5_000, "Transfer to savings", CategoryKeys.INTERNAL_TRANSFER)
            add(5, 5_000, "Transfer from current", CategoryKeys.INTERNAL_TRANSFER, acc = "save")
            add(6, -299, "Spotify", CategoryKeys.SUBSCRIPTIONS)
            add(9, -259, "Netflix", CategoryKeys.SUBSCRIPTIONS)
            add(12, -549, "Demo Mobile Tariff", CategoryKeys.SUBSCRIPTIONS)

            val groceries = listOf("Albert", "Lidl", "Kaufland", "Billa", "Rohlik.cz")
            val eating = listOf("Bistro U Parku", "Wolt", "Kavárna Demo", "Pizzeria Demo")
            for (i in 0 until 9) add(2 + i * 3, -(250L + rnd.nextInt(700)), groceries[rnd.nextInt(groceries.size)], CategoryKeys.GROCERIES, TransactionSource.GOOGLE_WALLET)
            for (i in 0 until 5) add(4 + i * 5, -(120L + rnd.nextInt(480)), eating[rnd.nextInt(eating.size)], CategoryKeys.EATING_OUT, TransactionSource.GOOGLE_WALLET)
            add(7, -1_450, "Shell", CategoryKeys.TRAVELLING, TransactionSource.GOOGLE_WALLET)
            add(18, -390, "RegioJet", CategoryKeys.TRAVELLING)
            add(14, -890, "Decathlon", CategoryKeys.FUN, TransactionSource.GOOGLE_WALLET)
            add(21, -350, "Squash court booking", CategoryKeys.FUN)
            add(16, -420, "Dr.Max", CategoryKeys.OTHER_EXPENSES, TransactionSource.GOOGLE_WALLET)
            add(23, 1_500, "Gift from family", CategoryKeys.GIFTS)
            add(25, 400, "Friend paid back dinner", CategoryKeys.EATING_OUT)
            add(20, -450, "GOPAY *OBCHOD", null)
            add(24, -189, "SUMUP *STANEK", null)
        }
        return out.sortedBy { it.date }
    }

    fun millisOf(tx: DemoTx, zone: ZoneId = ZoneId.systemDefault()): Long =
        tx.date.atTime(tx.hour, 15).atZone(zone).toInstant().toEpochMilli()
}
