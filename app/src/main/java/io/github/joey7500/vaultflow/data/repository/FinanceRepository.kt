package io.github.joey7500.vaultflow.data.repository

import androidx.room.withTransaction
import io.github.joey7500.vaultflow.data.local.VaultDatabase
import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.CaptureLogEntity
import io.github.joey7500.vaultflow.data.local.entity.CategoryEntity
import io.github.joey7500.vaultflow.data.local.entity.ManualAssetEntity
import io.github.joey7500.vaultflow.data.local.entity.RuleEntity
import io.github.joey7500.vaultflow.data.local.entity.SenderEntity
import io.github.joey7500.vaultflow.data.local.toDomain
import io.github.joey7500.vaultflow.data.local.toEntity
import io.github.joey7500.vaultflow.data.remote.CnbClient
import io.github.joey7500.vaultflow.domain.budget.ReserveCalculator
import io.github.joey7500.vaultflow.domain.classify.Classifier
import io.github.joey7500.vaultflow.data.local.entity.ProcessedMessageEntity
import io.github.joey7500.vaultflow.domain.ingest.BalanceObservation
import io.github.joey7500.vaultflow.domain.ingest.IncomingMessage
import io.github.joey7500.vaultflow.domain.ingest.IngestContext
import io.github.joey7500.vaultflow.domain.ingest.IngestionPipeline
import io.github.joey7500.vaultflow.domain.ingest.NotificationParser
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.DefaultCategories
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.ManualAsset
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.text.TextNormalizer
import io.github.joey7500.vaultflow.domain.budget.PeriodSettings
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything about the monthly budget, accounts and captured transactions. */
class FinanceRepository(
    private val db: VaultDatabase,
    private val settings: SettingsStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val ingestMutex = Mutex()

    val categories: Flow<List<Category>> = db.categories().observeAll().map { list -> list.map { it.toDomain() } }
    val accounts: Flow<List<Account>> = db.accounts().observeAll().map { list -> list.map { it.toDomain() } }
    val rules: Flow<List<ClassificationRule>> = db.rules().observeAll().map { list -> list.map { it.toDomain() } }
    val manualAssets: Flow<List<ManualAsset>> = db.manualAssets().observeAll().map { list -> list.map { it.toDomain() } }
    val senders: Flow<List<SenderEntity>> = db.senders().observeAll()
    val captureLog: Flow<List<CaptureLogEntity>> = db.captureLog().observeRecent(60)

    /** Current balance per account id = anchor + transactions after the anchor. */
    val accountBalances: Flow<Map<Long, Long>> =
        combine(accounts, db.transactions().observeAccountDeltas()) { accs, deltas ->
            val byAccount = deltas.associate { it.accountId to it.total }
            accs.associate { it.id to it.anchorBalanceMinor + (byAccount[it.id] ?: 0L) }
        }

    val reserveFloorMinor: Flow<Long> = settings.all.map {
        it[SettingsStore.RESERVE_FLOOR_MINOR]?.toLongOrNull() ?: ReserveCalculator.DEFAULT_FLOOR_MINOR
    }

    fun monthRange(month: YearMonth): Pair<Long, Long> {
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }

    val periodSettings: Flow<PeriodSettings> = settings.all.map {
        PeriodSettings(
            startDay = it[SettingsStore.PERIOD_START_DAY]?.toIntOrNull() ?: 1,
            incomeGraceDays = it[SettingsStore.INCOME_GRACE_DAYS]?.toIntOrNull() ?: 0,
        )
    }

    suspend fun setPeriodSettings(startDay: Int, graceDays: Int) {
        settings.put(SettingsStore.PERIOD_START_DAY, startDay.coerceIn(1, 28).toString())
        settings.put(SettingsStore.INCOME_GRACE_DAYS, graceDays.coerceIn(0, 10).toString())
    }

    /** Transactions with dates in [from, toExclusive). */
    fun transactionsBetween(from: LocalDate, toExclusive: LocalDate): Flow<List<Transaction>> {
        val a = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val b = toExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        return db.transactions().observeBetween(a, b).map { list -> list.map { it.toDomain() } }
    }

    fun transactionsIn(month: YearMonth): Flow<List<Transaction>> {
        val (from, to) = monthRange(month)
        return db.transactions().observeBetween(from, to).map { list -> list.map { it.toDomain() } }
    }

    // ───────────────────────── setup ─────────────────────────

    /** Idempotent first-run seeding of categories and defaults. */
    suspend fun ensureSeeded(budgets: Map<String, Long> = emptyMap()) {
        db.withTransaction {
            if (db.categories().count() == 0) {
                db.categories().insertAll(
                    DefaultCategories.all.mapIndexed { i, t ->
                        CategoryEntity(
                            key = t.key, name = t.name, groupName = t.group, kind = t.kind.name,
                            monthlyBudgetMinor = budgets[t.key] ?: t.budgetMinor, sortOrder = i * 10,
                        )
                    },
                )
            }
            if (settings.get(SettingsStore.RESERVE_FLOOR_MINOR) == null) {
                settings.putLong(SettingsStore.RESERVE_FLOOR_MINOR, ReserveCalculator.DEFAULT_FLOOR_MINOR)
            }
            if (db.senders().get(NotificationParser.GOOGLE_WALLET_PACKAGE) == null) {
                db.senders().upsert(SenderEntity(NotificationParser.GOOGLE_WALLET_PACKAGE, enabled = true, accountId = null, seenCount = 0, lastSeenMillis = 0))
            }
        }
    }

    // ───────────────────────── transactions ─────────────────────────

    suspend fun addTransaction(tx: Transaction): Long = db.transactions().insert(tx.toEntity())

    suspend fun updateTransaction(tx: Transaction) = db.transactions().update(tx.toEntity())

    suspend fun deleteTransaction(id: Long) = db.transactions().delete(id)

    /**
     * Assigns a category. With [learn] = true an "always classify like this"
     * rule is stored and applied to other uncategorised transactions from the
     * same merchant. Returns the learned pattern, if any.
     */
    suspend fun assignCategory(txId: Long, categoryId: Long, learn: Boolean): String? = db.withTransaction {
        val tx = db.transactions().get(txId) ?: return@withTransaction null
        db.transactions().update(tx.copy(categoryId = categoryId, confidence = 1.0, note = "set by you"))
        if (!learn) return@withTransaction null
        val pattern = Classifier.learnedPattern(tx.merchant) ?: return@withTransaction null
        db.rules().upsert(RuleEntity(pattern = pattern, matchType = RuleMatch.CONTAINS.name, categoryId = categoryId, createdAtMillis = System.currentTimeMillis()))
        val since = System.currentTimeMillis() - 120L * 24 * 3600 * 1000
        db.transactions().getSince(since)
            .filter { it.categoryId == null && TextNormalizer.merchantKey(it.merchant).contains(pattern) }
            .forEach { db.transactions().update(it.copy(categoryId = categoryId, confidence = 1.0, note = "your rule: \"$pattern\"")) }
        pattern
    }

    // ───────────────────────── categories ─────────────────────────

    suspend fun setBudget(categoryId: Long, minor: Long) = db.categories().setBudget(categoryId, minor)

    suspend fun addCategory(name: String, group: String, kind: FlowKind, budgetMinor: Long) {
        val maxOrder = db.categories().getAll().maxOfOrNull { it.sortOrder } ?: 0
        db.categories().insert(
            CategoryEntity(key = null, name = name.trim(), groupName = group.trim().ifBlank { "Custom" }, kind = kind.name, monthlyBudgetMinor = budgetMinor, sortOrder = maxOrder + 10),
        )
    }

    suspend fun renameCategory(category: Category, name: String) {
        val entity = db.categories().getAll().firstOrNull { it.id == category.id } ?: return
        db.categories().update(entity.copy(name = name.trim()))
    }

    /** Only user-created categories (no key) can be removed; their transactions return to Uncertain. */
    suspend fun archiveCategory(category: Category) = db.withTransaction {
        if (category.key != null) return@withTransaction
        val entity = db.categories().getAll().firstOrNull { it.id == category.id } ?: return@withTransaction
        db.categories().update(entity.copy(archived = true))
        db.transactions().clearCategory(category.id)
    }

    // ───────────────────────── accounts, rules, assets ─────────────────────────

    suspend fun saveAccount(entity: AccountEntity): Long = db.accounts().upsert(entity)

    suspend fun deleteAccount(id: Long) = db.accounts().delete(id)

    suspend fun accountEntities(): List<AccountEntity> = db.accounts().getAll()

    suspend fun deleteRule(id: Long) = db.rules().delete(id)

    suspend fun saveManualAsset(entity: ManualAssetEntity) = db.manualAssets().upsert(entity)

    suspend fun deleteManualAsset(id: Long) = db.manualAssets().delete(id)

    suspend fun setReserveFloor(minor: Long) = settings.putLong(SettingsStore.RESERVE_FLOOR_MINOR, minor)

    suspend fun updateSender(pkg: String, enabled: Boolean, accountId: Long?) {
        val current = db.senders().get(pkg) ?: SenderEntity(pkg, enabled, accountId, 0, 0)
        db.senders().upsert(current.copy(enabled = enabled, accountId = accountId))
    }

    suspend fun deleteSender(pkg: String) = db.senders().delete(pkg)

    suspend fun clearCaptureLog() = db.captureLog().deleteAll()

    // ───────────────────────── notification ingestion ─────────────────────────

    /**
     * Called by the notification listener for notifications that contain a
     * money amount. Apps you have not enabled are only counted (package name),
     * their content is not stored.
     */
    /**
     * Single entry point for notifications AND e-mails. Senders you have not
     * enabled are only counted (package name / address), their content is not
     * stored. Returns true when the message was actually processed.
     */
    suspend fun ingestMessage(msg: IncomingMessage): Boolean = ingestMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = db.senders().get(msg.sourceKey)
        val sender = (existing ?: SenderEntity(msg.sourceKey, enabled = defaultEnabled(msg.sourceKey), accountId = null, seenCount = 0, lastSeenMillis = 0))
            .let { it.copy(seenCount = it.seenCount + 1, lastSeenMillis = now) }
        db.senders().upsert(sender)
        if (!sender.enabled) return@withLock false

        val fx = settings.cachedFx() ?: runCatching { CnbClient.dailyRates() }.getOrNull()?.also { settings.saveFx(it, now) }
        val categoryIds = db.categories().getAll().filter { it.key != null }.associate { it.key!! to it.id }
        val ctx = IngestContext(
            defaultAccountId = sender.accountId,
            rules = db.rules().getAll().map { it.toDomain() },
            accounts = db.accounts().getAll().map { it.toDomain() },
            categoryIdByKey = categoryIds,
            // 4 days back: covers e-mail delays and interbank transfer counterparts.
            recentTransactions = db.transactions().getSince(msg.receivedAtMillis - 4L * 24 * 3600 * 1000).map { it.toDomain() },
            fx = fx,
        )

        val result = IngestionPipeline.process(msg, ctx)
        var firstId: Long? = null
        db.withTransaction {
            result.updates.forEach {
                db.transactions().update(it.toEntity())
                if (firstId == null) firstId = it.id
            }
            result.inserts.forEach {
                val id = db.transactions().insert(it.toEntity())
                if (firstId == null) firstId = id
            }
            result.balance?.let { applyBalance(it) }
        }

        if (settings.get(SettingsStore.CAPTURE_LOG_ENABLED) != "false") {
            db.captureLog().insert(
                CaptureLogEntity(
                    postedAtMillis = msg.receivedAtMillis, packageName = msg.sourceKey,
                    title = msg.title.orEmpty().take(120), text = msg.text.orEmpty().take(400),
                    outcome = result.outcome, transactionId = firstId,
                ),
            )
        }
        db.captureLog().purgeBefore(now - CAPTURE_LOG_RETENTION_MS)
        true
    }

    /**
     * The bank told us the real balance: compare with what the app computed
     * (a difference means a missed or wrong transaction) and re-anchor so the
     * shown balance is exact from now on.
     */
    private suspend fun applyBalance(obs: BalanceObservation) {
        val acc = db.accounts().getAll().firstOrNull { it.id == obs.accountId } ?: return
        if (obs.asOfMillis <= acc.anchorDateMillis) return // older than what we already know
        val computed = acc.anchorBalanceMinor + db.transactions().sumForAccount(acc.id, acc.anchorDateMillis, obs.asOfMillis)
        db.accounts().upsert(
            acc.copy(
                anchorBalanceMinor = obs.ownFundsMinor,
                anchorDateMillis = obs.asOfMillis,
                overdraftMinor = obs.overdraftMinor ?: acc.overdraftMinor,
                lastCheckAtMillis = obs.asOfMillis,
                lastCheckDiffMinor = obs.ownFundsMinor - computed,
                lastReportedMinor = obs.ownFundsMinor,
            ),
        )
    }

    /** Google Wallet and known Czech bank e-mail domains start enabled. */
    private fun defaultEnabled(sourceKey: String): Boolean {
        if (sourceKey == NotificationParser.GOOGLE_WALLET_PACKAGE) return true
        if (!sourceKey.startsWith("mail:")) return false
        val domain = sourceKey.substringAfter('@').lowercase()
        return KNOWN_BANK_MAIL_DOMAINS.any { domain == it || domain.endsWith(".$it") }
    }

    suspend fun processedMessage(key: String) = db.processedMessages().get(key)

    suspend fun isSenderEnabled(key: String): Boolean = db.senders().get(key)?.enabled ?: defaultEnabled(key)

    suspend fun markProcessed(key: String, handled: Boolean) {
        db.processedMessages().put(ProcessedMessageEntity(key, System.currentTimeMillis(), handled))
    }

    suspend fun purgeProcessed() = db.processedMessages().purgeBefore(System.currentTimeMillis() - 45L * 24 * 3600 * 1000)

    fun emailConfirmedCount(month: YearMonth): Flow<Int> {
        val (from, to) = monthRange(month)
        return db.transactions().observeEmailConfirmed(from, to)
    }

    companion object {
        const val CAPTURE_LOG_RETENTION_MS = 7L * 24 * 3600 * 1000
        val KNOWN_BANK_MAIL_DOMAINS = listOf(
            "csas.cz", "kb.cz", "csob.cz", "fio.cz", "airbank.cz", "moneta.cz", "rb.cz", "unicreditgroup.cz",
            "mbank.cz", "equabank.cz", "creditas.cz", "partners.cz", "revolut.com",
        )
    }
}
