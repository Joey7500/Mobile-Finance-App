package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.classify.Classifier
import io.github.joey7500.vaultflow.domain.fx.FxRates
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/** Everything the pipeline needs to know about the current vault state. */
data class IngestContext(
    /** Account mapped to the notifying app, if the user configured one. */
    val accountId: Long?,
    val rules: List<ClassificationRule>,
    val ownAccountIdentifiers: List<String>,
    val categoryIdByKey: Map<String, Long>,
    /** Transactions from roughly the last [Deduplicator.WINDOW_MS] (plus margin). */
    val recentTransactions: List<Transaction>,
    /** Latest ČNB rates for converting foreign-currency spend. */
    val fx: FxRates?,
    /** Card digits (e.g. "3953") -> account id. Wins over [accountId] when the text mentions the card. */
    val cardAccounts: Map<String, Long> = emptyMap(),
)

/** Finds the account whose card digits appear in the notification as a standalone number. */
fun resolveCardAccount(text: String, cardAccounts: Map<String, Long>): Long? =
    cardAccounts.entries
        .filter { it.key.length >= 4 }
        .sortedByDescending { it.key.length }
        .firstOrNull { (digits, _) -> Regex("(?<!\\d)" + Regex.escape(digits) + "(?!\\d)").containsMatchIn(text) }
        ?.value

sealed interface IngestDecision {
    data class Insert(val transaction: Transaction) : IngestDecision
    data class Merge(val existing: Transaction, val merged: Transaction) : IngestDecision
    data class Skip(val reason: String) : IngestDecision
}

/** Notification -> parse -> FX -> classify -> de-duplicate -> decision. Pure and unit-tested. */
object IngestionPipeline {

    fun process(
        packageName: String,
        notificationKey: String,
        postedAtMillis: Long,
        title: String?,
        text: String?,
        ctx: IngestContext,
    ): IngestDecision {
        val parsed = NotificationParser.parse(packageName, title, text)
            ?: return IngestDecision.Skip("not a completed transaction")

        val czkMinor = if (parsed.currency == "CZK") {
            parsed.amountMinor
        } else {
            ctx.fx?.toCzkMinor(parsed.amountMinor, parsed.currency)
                ?: return IngestDecision.Skip("no exchange rate for ${parsed.currency}")
        }

        val classification = Classifier(ctx.rules, ctx.ownAccountIdentifiers)
            .classify(parsed.merchant, listOfNotNull(title, text).joinToString(" "), parsed.amountMinor)
        val categoryId = when {
            !classification.isConfident -> null
            classification.categoryId != null -> classification.categoryId
            else -> classification.categoryKey?.let(ctx.categoryIdByKey::get)
        }

        val source = if (parsed.isFromWallet) TransactionSource.GOOGLE_WALLET else TransactionSource.BANK_NOTIFICATION
        val candidate = Transaction(
            timestampMillis = postedAtMillis,
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            amountCzkMinor = czkMinor,
            merchant = parsed.merchant,
            accountId = resolveCardAccount(listOfNotNull(title, text).joinToString(" "), ctx.cardAccounts) ?: ctx.accountId,
            categoryId = categoryId,
            source = source,
            // A foreign-currency amount is only an estimate until the bank settles it.
            status = if (parsed.currency != "CZK") TransactionStatus.PENDING else TransactionStatus.SETTLED,
            confidence = if (categoryId != null) classification.confidence else 0.0,
            note = classification.reason,
            sourcePackage = packageName,
            fingerprint = TextNormalizer.fingerprint(packageName, notificationKey, title.orEmpty(), text.orEmpty()),
        )

        val duplicate = Deduplicator.findDuplicate(candidate, ctx.recentTransactions)
        return if (duplicate == null) {
            IngestDecision.Insert(candidate)
        } else if (duplicate.fingerprint == candidate.fingerprint) {
            IngestDecision.Skip("identical notification already captured")
        } else {
            IngestDecision.Merge(duplicate, Deduplicator.merge(duplicate, candidate))
        }
    }
}
