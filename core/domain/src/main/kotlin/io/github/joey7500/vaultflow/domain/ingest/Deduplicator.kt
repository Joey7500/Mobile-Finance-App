package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.domain.text.TextNormalizer
import kotlin.math.abs

/**
 * Detects the same purchase reported twice, typically once by Google Wallet
 * and seconds later by the bank app.
 *
 * Unlike a naive "same amount within N minutes" rule, two notifications from
 * the SAME app are never merged (two 89 Kč coffees are two purchases). Only an
 * identical re-post (same fingerprint) from the same app is dropped.
 */
object Deduplicator {
    const val WINDOW_MS: Long = 10 * 60 * 1000L

    fun findDuplicate(candidate: Transaction, recent: List<Transaction>): Transaction? =
        recent.firstOrNull { isDuplicate(candidate, it) }

    fun isDuplicate(candidate: Transaction, existing: Transaction): Boolean {
        if (candidate.fingerprint != null && candidate.fingerprint == existing.fingerprint) return true
        if (abs(candidate.timestampMillis - existing.timestampMillis) > WINDOW_MS) return false
        if (candidate.amountCzkMinor.sign() != existing.amountCzkMinor.sign()) return false

        val sameChannel = candidate.sourcePackage != null && candidate.sourcePackage == existing.sourcePackage
        if (sameChannel) return false

        if (!amountsMatch(candidate, existing)) return false

        // A manual entry only merges when the merchant clearly matches.
        val strict = existing.source == TransactionSource.MANUAL || candidate.source == TransactionSource.MANUAL
        return merchantsCompatible(candidate.merchant, existing.merchant, strict)
    }

    /** Same currency: within 1 minor unit. Different currency (FX estimate vs settled): within 3 % or 1 Kč. */
    fun amountsMatch(a: Transaction, b: Transaction): Boolean =
        if (a.currency == b.currency) {
            abs(a.amountMinor - b.amountMinor) <= 1
        } else {
            val tolerance = maxOf(100L, (abs(a.amountCzkMinor) * 3) / 100)
            abs(a.amountCzkMinor - b.amountCzkMinor) <= tolerance
        }

    fun merchantsCompatible(a: String, b: String, strict: Boolean = false): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return !strict
        if (ta.any { it in tb }) return true
        val ka = TextNormalizer.merchantKey(a)
        val kb = TextNormalizer.merchantKey(b)
        return ka.contains(kb) || kb.contains(ka)
    }

    /**
     * Combines two reports of the same purchase. The bank report wins for the
     * amount (it is the settled CZK value); the Wallet merchant name is usually
     * cleaner; a category the user already chose is kept.
     */
    fun merge(existing: Transaction, incoming: Transaction): Transaction {
        val bankReport = listOf(existing, incoming).firstOrNull { it.source == TransactionSource.BANK_NOTIFICATION }
        val walletReport = listOf(existing, incoming).firstOrNull { it.source == TransactionSource.GOOGLE_WALLET }
        val amountSource = bankReport ?: existing
        return existing.copy(
            amountMinor = amountSource.amountMinor,
            currency = amountSource.currency,
            amountCzkMinor = amountSource.amountCzkMinor,
            merchant = walletReport?.merchant?.takeIf { it.isNotBlank() }
                ?: existing.merchant.ifBlank { incoming.merchant },
            accountId = existing.accountId ?: incoming.accountId,
            categoryId = existing.categoryId ?: incoming.categoryId,
            confidence = maxOf(existing.confidence, incoming.confidence),
            status = if (bankReport != null) TransactionStatus.SETTLED else existing.status,
        )
    }

    private val GENERIC = setOf(
        "PLATBA", "KARTOU", "KARTA", "CARD", "PAYMENT", "PURCHASE", "TRANSAKCE", "VISA", "MASTERCARD",
        "GOOGLE", "WALLET", "PAY", "CZK", "EUR", "THE", "AND",
    )

    private fun tokens(s: String): Set<String> =
        TextNormalizer.merchantKey(s)
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.length >= 3 && it.any(Char::isLetter) && it !in GENERIC }
            .toSet()

    private fun Long.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
