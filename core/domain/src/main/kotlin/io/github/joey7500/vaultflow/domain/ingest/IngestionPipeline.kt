package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.classify.Classifier
import io.github.joey7500.vaultflow.domain.fx.FxRates
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.domain.text.TextNormalizer
import java.time.ZoneId

/** One notification or e-mail, as delivered by the platform. */
data class IncomingMessage(
    val channel: Channel,
    /** Android package name, or "mail:<sender address>" for e-mails. */
    val sourceKey: String,
    /** Stable id of this message (notification key + time, or e-mail Message-ID). */
    val messageKey: String,
    val receivedAtMillis: Long,
    val title: String?,
    val text: String?,
)

/** Everything the pipeline needs to know about the current vault state. */
data class IngestContext(
    /** Account mapped to the sending app / e-mail address, if configured. */
    val defaultAccountId: Long?,
    val rules: List<ClassificationRule>,
    val accounts: List<Account>,
    val categoryIdByKey: Map<String, Long>,
    /** Transactions from the last few days, used for de-duplication. */
    val recentTransactions: List<Transaction>,
    /** Latest ČNB rates for converting foreign-currency spend. */
    val fx: FxRates?,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** "The bank says account X held Y at time T." Used to check and re-anchor the balance. */
data class BalanceObservation(
    val accountId: Long,
    val ownFundsMinor: Long,
    val overdraftMinor: Long?,
    val asOfMillis: Long,
)

data class IngestResult(
    val inserts: List<Transaction> = emptyList(),
    /** Existing records (with their ids) updated by a merge. */
    val updates: List<Transaction> = emptyList(),
    val balance: BalanceObservation? = null,
    val outcome: String,
) {
    val isSkipped: Boolean get() = inserts.isEmpty() && updates.isEmpty() && balance == null
}

/**
 * Message -> parse -> resolve accounts -> FX -> classify / detect own transfer
 * -> fuse with earlier reports -> mirror on the other own account -> balance.
 * Pure and unit-tested.
 */
object IngestionPipeline {
    /** Typical e-mail delay after the payment; used when an e-mail only carries the date. */
    const val EMAIL_DELAY_MS: Long = 6 * 60 * 1000L

    fun process(msg: IncomingMessage, ctx: IngestContext): IngestResult {
        val bank = BankMessageParser.parse(msg.sourceKey, msg.channel, msg.title, msg.text, ctx.zone)
            ?: return IngestResult(outcome = "skipped: not a payment or balance message")

        val sourceAccount = resolveSourceAccount(bank, ctx)
        val balance = bank.ownFundsMinor?.let { funds ->
            sourceAccount?.let { acc ->
                BalanceObservation(
                    accountId = acc.id,
                    // No overdraft stated -> subtract the one we learned for this account.
                    ownFundsMinor = if (bank.overdraftMinor == null && bank.availableMayIncludeOverdraft) funds - acc.overdraftMinor else funds,
                    overdraftMinor = bank.overdraftMinor,
                    asOfMillis = bank.eventTimeMillis ?: msg.receivedAtMillis,
                )
            }
        }

        val parsed = bank.transaction
            ?: return IngestResult(
                balance = balance,
                outcome = if (balance != null) "balance reported" else "skipped: balance for an unknown account",
            )

        val czkMinor = if (parsed.currency == "CZK") {
            parsed.amountMinor
        } else {
            ctx.fx?.toCzkMinor(parsed.amountMinor, parsed.currency)
                ?: return IngestResult(balance = balance, outcome = "skipped: no exchange rate for ${parsed.currency}")
        }
        val direction = if (parsed.amountMinor < 0) Direction.OUT else Direction.IN

        val counterparty = resolveCounterparty(bank, parsed.merchant, direction, sourceAccount, ctx)
        val transferId = ctx.categoryIdByKey[CategoryKeys.INTERNAL_TRANSFER]

        val merchant = when {
            counterparty != null && direction == Direction.OUT && !bank.hasExplicitMerchant -> "Transfer to ${counterparty.name}"
            counterparty != null && direction == Direction.IN && !bank.hasExplicitMerchant -> "Transfer from ${counterparty.name}"
            !bank.hasExplicitMerchant && msg.channel == Channel.EMAIL -> describeCounterpartyAccount(bank, direction) ?: parsed.merchant
            else -> parsed.merchant
        }

        val classification = Classifier(ctx.rules)
            .classify(merchant, listOfNotNull(msg.title, msg.text).joinToString(" "), parsed.amountMinor)
        val categoryId = when {
            counterparty != null -> transferId
            !classification.isConfident -> null
            classification.categoryId != null -> classification.categoryId
            else -> classification.categoryKey?.let(ctx.categoryIdByKey::get)
        }

        val source = when {
            msg.channel == Channel.EMAIL -> TransactionSource.EMAIL
            parsed.isFromWallet -> TransactionSource.GOOGLE_WALLET
            else -> TransactionSource.BANK_NOTIFICATION
        }
        val timestamp = when {
            bank.eventTimeMillis != null -> bank.eventTimeMillis
            msg.channel == Channel.EMAIL -> msg.receivedAtMillis - EMAIL_DELAY_MS
            else -> msg.receivedAtMillis
        }
        val fingerprint = TextNormalizer.fingerprint(msg.sourceKey, msg.messageKey, msg.title.orEmpty(), msg.text.orEmpty())

        val candidate = Transaction(
            timestampMillis = timestamp,
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            amountCzkMinor = czkMinor,
            merchant = merchant,
            accountId = sourceAccount?.id,
            categoryId = categoryId,
            source = source,
            // A foreign-currency amount is only an estimate until the bank settles it.
            status = if (parsed.currency != "CZK") TransactionStatus.PENDING else TransactionStatus.SETTLED,
            confidence = when {
                categoryId == null -> 0.0
                counterparty != null -> 1.0
                else -> classification.confidence
            },
            note = if (counterparty != null) "transfer between your accounts" else classification.reason,
            sourcePackage = msg.sourceKey,
            fingerprint = fingerprint,
        )

        val inserts = mutableListOf<Transaction>()
        val updates = mutableListOf<Transaction>()
        val duplicate = Deduplicator.findDuplicate(candidate, ctx.recentTransactions)
        if (duplicate != null && duplicate.fingerprint == candidate.fingerprint) {
            return IngestResult(outcome = "skipped: already captured")
        }

        val main: Transaction
        val outcome: String
        if (duplicate == null) {
            main = candidate
            inserts += candidate
            outcome = "added"
        } else {
            var merged = Deduplicator.merge(duplicate, candidate)
            if (counterparty != null && transferId != null && !isUserChoice(duplicate, transferId)) {
                merged = merged.copy(categoryId = transferId, note = "transfer between your accounts")
            }
            main = merged
            updates += merged
            outcome = "merged with ${describe(duplicate)}"
        }

        // Transfer between your own accounts: the other account must show the money too.
        var mirrorOutcome = ""
        if (counterparty != null && counterparty.id != sourceAccount?.id) {
            val mirror = Transaction(
                timestampMillis = main.timestampMillis,
                amountMinor = -main.amountCzkMinor,
                currency = "CZK",
                amountCzkMinor = -main.amountCzkMinor,
                merchant = if (direction == Direction.OUT) {
                    "Transfer from ${sourceAccount?.name ?: "your account"}"
                } else {
                    "Transfer to ${sourceAccount?.name ?: "your account"}"
                },
                accountId = counterparty.id,
                categoryId = transferId,
                source = TransactionSource.INFERRED,
                status = TransactionStatus.PENDING,
                confidence = 1.0,
                note = "expected counterpart of a transfer",
                sourcePackage = null,
                fingerprint = "$fingerprint:mirror",
            )
            val others = ctx.recentTransactions.filter { it.id != main.id || main.id == 0L }
            val otherSide = Deduplicator.findDuplicate(mirror, others)
            when {
                otherSide == null -> {
                    inserts += mirror
                    mirrorOutcome = " + counterpart on ${counterparty.name}"
                }
                otherSide.fingerprint == mirror.fingerprint -> Unit
                else -> {
                    var merged = Deduplicator.merge(otherSide, mirror)
                    if (transferId != null && !isUserChoice(otherSide, transferId)) merged = merged.copy(categoryId = transferId)
                    if (merged != otherSide) updates += merged
                    mirrorOutcome = " + matched counterpart on ${counterparty.name}"
                }
            }
        }

        // The reported balance includes this payment: anchor after every report of it.
        val balanceAfter = balance?.let {
            if (it.accountId != main.accountId) {
                it
            } else {
                it.copy(asOfMillis = maxOf(it.asOfMillis, main.timestampMillis, candidate.timestampMillis, duplicate?.timestampMillis ?: 0L))
            }
        }
        return IngestResult(inserts, updates, balanceAfter, outcome + mirrorOutcome + if (balanceAfter != null) " + balance" else "")
    }

    /** Card digits > account number in the message > account mapped to the sender. */
    fun resolveSourceAccount(bank: BankMessage, ctx: IngestContext): Account? {
        bank.cardDigits.forEach { digits ->
            ctx.accounts.firstOrNull { a -> a.cardIdentifiers.any { it == digits } }?.let { return it }
        }
        val preferredRole = when {
            bank.transaction == null -> AccountRole.MENTION
            bank.transaction.amountMinor < 0 -> AccountRole.FROM
            else -> AccountRole.TO
        }
        val own = bank.accounts.mapNotNull { m -> ownAccountFor(m, ctx)?.let { m to it } }
        own.firstOrNull { it.first.role == preferredRole }?.let { return it.second }
        own.firstOrNull { it.first.role == AccountRole.MENTION }?.let { return it.second }
        ctx.defaultAccountId?.let { id -> ctx.accounts.firstOrNull { it.id == id } }?.let { return it }
        return own.firstOrNull()?.second
    }

    /** The OTHER own account involved: by account number, or by a merchant alias ("REVOLUT"). */
    fun resolveCounterparty(bank: BankMessage, merchant: String, direction: Direction, source: Account?, ctx: IngestContext): Account? {
        val otherRole = if (direction == Direction.OUT) AccountRole.TO else AccountRole.FROM
        bank.accounts
            .filter { it.role == otherRole }
            .mapNotNull { ownAccountFor(it, ctx) }
            .firstOrNull { it.id != source?.id }
            ?.let { return it }
        val key = TextNormalizer.merchantKey(merchant)
        return ctx.accounts.firstOrNull { a ->
            a.id != source?.id && a.transferAliases.any { alias ->
                val k = TextNormalizer.merchantKey(alias)
                k.length >= 3 && key.contains(k)
            }
        }
    }

    private fun isUserChoice(t: Transaction, transferId: Long) =
        t.confidence >= 1.0 && t.categoryId != null && t.categoryId != transferId && t.note == "set by you"

    private fun ownAccountFor(m: AccountMention, ctx: IngestContext): Account? =
        ctx.accounts.firstOrNull { a -> a.ownIdentifiers.any { BankMessageParser.sameAccount(m.number, it) } }

    private fun describeCounterpartyAccount(bank: BankMessage, direction: Direction): String? {
        val role = if (direction == Direction.OUT) AccountRole.TO else AccountRole.FROM
        val m = bank.accounts.firstOrNull { it.role == role } ?: return null
        return if (direction == Direction.OUT) "Payment to ${m.raw}" else "Payment from ${m.raw}"
    }

    private fun describe(t: Transaction): String = when (t.source) {
        TransactionSource.GOOGLE_WALLET -> "the Wallet report"
        TransactionSource.BANK_NOTIFICATION -> "the bank app report"
        TransactionSource.EMAIL -> "the e-mail report"
        TransactionSource.INFERRED -> "the expected transfer"
        else -> "an earlier record"
    }
}
