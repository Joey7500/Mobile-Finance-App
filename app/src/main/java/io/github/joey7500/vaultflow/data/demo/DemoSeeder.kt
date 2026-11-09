package io.github.joey7500.vaultflow.data.demo

import androidx.room.withTransaction
import io.github.joey7500.vaultflow.data.local.VaultDatabase
import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.ManualAssetEntity
import io.github.joey7500.vaultflow.data.local.entity.SnapshotEntity
import io.github.joey7500.vaultflow.data.local.entity.TransactionEntity
import io.github.joey7500.vaultflow.data.repository.FinanceRepository
import io.github.joey7500.vaultflow.data.repository.SettingsStore
import io.github.joey7500.vaultflow.domain.demo.DemoData
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.domain.wealth.GoldMath
import java.time.LocalDate
import java.time.ZoneId

/** Fills the in-memory demo database with synthetic data. Never touches the real vault. */
object DemoSeeder {

    suspend fun seed(db: VaultDatabase, finance: FinanceRepository, settings: SettingsStore) {
        finance.ensureSeeded(DemoData.budgets)
        if (db.accounts().getAll().isNotEmpty()) return

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()

        db.withTransaction {
            // Anchor balances at "now": historical demo transactions do not move them.
            val accountIds = DemoData.accounts.mapIndexed { i, a ->
                a.key to db.accounts().upsert(
                    AccountEntity(name = a.name, type = a.type.name, anchorBalanceMinor = a.balanceMinor, anchorDateMillis = now, isLiquid = a.liquid, ownIdentifiers = "", sortOrder = i),
                )
            }.toMap()

            val categoryIds = db.categories().getAll().filter { it.key != null }.associate { it.key!! to it.id }
            db.transactions().insertAll(
                DemoData.transactions(today).map { t ->
                    TransactionEntity(
                        timestampMillis = DemoData.millisOf(t, zone),
                        amountMinor = t.amountMinor, currency = "CZK", amountCzkMinor = t.amountMinor,
                        merchant = t.merchant, accountId = accountIds[t.accountKey],
                        categoryId = t.categoryKey?.let(categoryIds::get),
                        source = t.source.name, status = TransactionStatus.SETTLED.name,
                        confidence = if (t.categoryKey == null) 0.0 else 1.0,
                        note = "demo", sourcePackage = null, fingerprint = null,
                    )
                },
            )

            DemoData.manualAssets.forEach {
                db.manualAssets().upsert(ManualAssetEntity(name = it.name, kind = it.kind.name, valueMinor = it.valueMinor, updatedAtMillis = now))
            }

            settings.putDouble(SettingsStore.GOLD_GRAMS, DemoData.GOLD_GRAMS)
            val perGram = GoldMath.czkPerGram(DemoData.GOLD_USD_PER_OZ, DemoData.CZK_PER_USD)
            settings.putDouble(SettingsStore.GOLD_LAST_CZK_PER_GRAM, perGram)
            settings.putLong(SettingsStore.T212_LAST_VALUE_CZK_MINOR, DemoData.trading212ValueMinor)

            // Six months of gently rising net-worth history for the chart.
            val gold = GoldMath.valueMinor(DemoData.GOLD_GRAMS, perGram)
            val banks = DemoData.accounts.sumOf { it.balanceMinor }
            val building = DemoData.manualAssets.sumOf { it.valueMinor }
            for (week in 26 downTo 0) {
                val f = 1.0 - week * 0.006
                val t212 = (DemoData.trading212ValueMinor * (f - week * 0.002)).toLong()
                val g = (gold * (f - week * 0.001)).toLong()
                val b = (banks * f).toLong()
                val bs = (building * (1.0 - week * 0.0015)).toLong()
                db.snapshots().upsert(
                    SnapshotEntity(
                        epochDay = today.minusWeeks(week.toLong()).toEpochDay(),
                        totalMinor = t212 + g + b + bs, trading212Minor = t212, goldMinor = g, banksMinor = b,
                        buildingSavingsMinor = bs, otherMinor = 0, createdAtMillis = now,
                    ),
                )
            }
        }
    }
}
