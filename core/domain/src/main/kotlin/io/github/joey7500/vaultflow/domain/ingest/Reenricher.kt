package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.classify.Classifier
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.text.TextNormalizer
import kotlin.math.abs

/**
 * Re-reads a bank e-mail that is ALREADY part of a record and applies what the e-mail-first
 * rules would have taken from it: the name (shop, counterparty or payment message), the
 * description, the counterparty account, the symbols, and the category unless you picked one.
 *
 * Enrich-only by design: it never creates records and never changes the amount, the time, the
 * account, the evidence or the fingerprints. Used once after the update to 1.3.0 and by
 * Settings → "Re-check names from e-mails".
 */
object Reenricher {

    /** The fingerprint the pipeline stored for this e-mail; a record that knows it fused this e-mail. */
    fun fingerprintOf(mail: IncomingMessage): String =
        TextNormalizer.fingerprint(mail.sourceKey, mail.messageKey, mail.title.orEmpty(), mail.text.orEmpty())

    /**
     * The updated record, or null when the e-mail adds nothing (or does not belong to [t]:
     * not an e-mail record, or another amount).
     */
    fun enrich(t: Transaction, mail: IncomingMessage, ctx: IngestContext): Transaction? {
        if (TransactionSource.EMAIL !in t.evidence) return null
        val bank = BankMessageParser.parse(mail.sourceKey, Channel.EMAIL, mail.title, mail.text, ctx.zone) ?: return null
        val parsed = bank.transaction ?: return null
        if (parsed.currency == t.currency && abs(abs(parsed.amountMinor) - abs(t.amountMinor)) > 1) return null
        val d = bank.details

        val transferId = ctx.categoryIdByKey[CategoryKeys.INTERNAL_TRANSFER]
        val ownTransfer = transferId != null && t.categoryId == transferId
        val emailName = d.merchant ?: d.counterpartyName ?: d.message?.let(EmailFirst::nameFromMessage)
        // "Transfer to KB" (between your accounts) already says what it is.
        val name = if (emailName != null && !ownTransfer && Deduplicator.prefersEmailName(t, emailName)) emailName else t.merchant
        val description = d.message ?: t.description
        val counterparty = d.counterpartyAccount ?: t.counterpartyAccount
        val symbols = d.symbols.ifBlank { t.symbols }

        var categoryId = t.categoryId
        var confidence = t.confidence
        var note = t.note
        val locked = t.categoryLocked || t.note == "set by you" || ownTransfer || (t.confidence >= 1.0 && t.categoryId != null)
        if (!locked) {
            val account = ctx.accounts.firstOrNull { it.id == t.accountId }
            val c = Classifier(ctx.rules, categoryKinds = ctx.categoryKindById).classify(
                name, listOfNotNull(mail.title, mail.text).joinToString(" "), t.amountMinor, account?.type,
                description = description, counterpartyAccount = counterparty,
            )
            val newId = c.categoryId ?: c.categoryKey?.let(ctx.categoryIdByKey::get)
            val better = c.confidence >= Classifier.CONFIDENCE_THRESHOLD || t.categoryId == null || t.confidence < Classifier.CONFIDENCE_THRESHOLD
            if (newId != null && c.confidence > 0.0 && better) {
                categoryId = newId
                confidence = c.confidence
                note = if (c.isSuggestion) "check: " + c.reason else c.reason
            }
        }

        val updated = t.copy(
            merchant = name,
            nameSource = if (name != t.merchant) TransactionSource.EMAIL else t.nameSource,
            description = description,
            counterpartyAccount = counterparty,
            symbols = symbols,
            categoryId = categoryId,
            confidence = confidence,
            note = note,
            awaitingEmail = false,
        )
        return updated.takeIf { it != t }
    }
}
