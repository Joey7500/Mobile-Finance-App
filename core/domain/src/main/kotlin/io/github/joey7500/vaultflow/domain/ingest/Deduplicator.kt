package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionSource.BANK_NOTIFICATION
import io.github.joey7500.vaultflow.domain.model.TransactionSource.EMAIL
import io.github.joey7500.vaultflow.domain.model.TransactionSource.GOOGLE_WALLET
import io.github.joey7500.vaultflow.domain.model.TransactionSource.INFERRED
import io.github.joey7500.vaultflow.domain.model.TransactionSource.MANUAL
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.domain.text.TextNormalizer
import kotlin.math.abs

/**
 * Fuses reports of the SAME payment coming from different sources:
 * Google Wallet (instant), the bank app (instant) and the bank e-mail
 * (≈ 6 minutes later, with the settled amount and the balance).
 *
 * Rules:
 * - A record already confirmed by a source never absorbs a second report from
 *   that same source: two e-mails or two Wallet taps are two purchases
 *   (e.g. two 89 Kč coffees).
 * - Time windows depend on the sources: ±10 min between instant sources,
 *   ±3 h when an e-mail is involved (some e-mails only carry the date),
 *   ±72 h for the expected counterpart of a transfer (interbank clearing).
 * - Different accounts never merge.
 * - Among several candidates the one closest in time wins.
 */
object Deduplicator {
    const val WINDOW_MS: Long = 10 * 60 * 1000L
    const val EMAIL_WINDOW_MS: Long = 3 * 60 * 60 * 1000L
    const val TRANSFER_WINDOW_MS: Long = 72 * 60 * 60 * 1000L
    const val SAME_MOMENT_MS: Long = 3 * 60 * 1000L

    fun findDuplicate(candidate: Transaction, recent: List<Transaction>): Transaction? =
        recent.filter { isDuplicate(candidate, it) }
            .minByOrNull { abs(it.timestampMillis - candidate.timestampMillis) }

    fun isDuplicate(candidate: Transaction, existing: Transaction): Boolean {
        if (candidate.fingerprint != null && candidate.fingerprint == existing.fingerprint) return true
        if (candidate.amountCzkMinor.sign() != existing.amountCzkMinor.sign()) return false
        if (candidate.accountId != null && existing.accountId != null && candidate.accountId != existing.accountId) return false

        val inferred = candidate.source == INFERRED || existing.source == INFERRED
        val window = when {
            inferred -> TRANSFER_WINDOW_MS
            candidate.source == EMAIL || existing.source == EMAIL -> EMAIL_WINDOW_MS
            else -> WINDOW_MS
        }
        if (abs(candidate.timestampMillis - existing.timestampMillis) > window) return false

        // Same source twice = two real transactions. This also applies to expected transfer
        // counterparts: two identical top-ups are two counterparts (the SAME counterpart
        // re-created by a later report is caught by its fingerprint above).
        if (candidate.source in existing.evidence) return false
        val sameChannel = candidate.sourcePackage != null && candidate.sourcePackage == existing.sourcePackage
        if (sameChannel && !inferred) return false

        if (!amountsMatch(candidate, existing)) return false

        if (inferred) {
            // A transfer counterpart is identified by account + amount; the texts differ by nature.
            return candidate.accountId != null && candidate.accountId == existing.accountId
        }
        val strict = existing.source == MANUAL || candidate.source == MANUAL
        // Two apps reporting the exact same amount on the SAME account within 3 minutes is
        // the same payment, however differently they spell the merchant.
        val sameAccount = candidate.accountId != null && candidate.accountId == existing.accountId
        if (!strict && sameAccount && abs(candidate.timestampMillis - existing.timestampMillis) <= SAME_MOMENT_MS && candidate.amountCzkMinor == existing.amountCzkMinor) {
            return true
        }
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

    /**
     * "NYX*VeryGoodiesas" (Wallet) and "Very Goodies" (Revolut) are the same shop:
     * compare word by word AND with spaces/punctuation removed, since every source
     * spells merchants differently.
     */
    fun merchantsCompatible(a: String, b: String, strict: Boolean = false): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return !strict
        if (ta.any { it in tb }) return true
        val ca = compact(a)
        val cb = compact(b)
        if (ca.length >= 4 && cb.length >= 4 && (ca.contains(cb) || cb.contains(ca))) return true
        // Every word of one name appears inside the other ("VERY", "GOODIES" in "NYXVERYGOODIESAS").
        if (ta.all { cb.contains(it) } || tb.all { ca.contains(it) }) return true
        // Shared word start ("GOODIES" vs "GOODIESAS").
        return ta.any { x -> x.length >= 4 && tb.any { y -> y.length >= 4 && (x.startsWith(y) || y.startsWith(x)) } }
    }

    private fun compact(s: String): String = TextNormalizer.merchantKey(s).filter(Char::isLetterOrDigit)

    /** Which source is trusted for the AMOUNT (the bank's settled CZK value wins). */
    private fun amountRank(s: TransactionSource) = when (s) {
        EMAIL -> 4
        BANK_NOTIFICATION -> 3
        MANUAL -> 2
        GOOGLE_WALLET -> 1
        else -> 0
    }

    /** Which source has the nicest MERCHANT name (Wallet shows the brand). */
    private fun merchantRank(s: TransactionSource) = when (s) {
        GOOGLE_WALLET -> 4
        BANK_NOTIFICATION -> 3
        MANUAL -> 3
        EMAIL -> 2
        else -> 0
    }

    /**
     * Combines two reports of one payment into a single record: the bank's
     * amount, the best merchant name, the earliest real timestamp, the
     * category you already chose, and the union of all evidence.
     */
    fun merge(existing: Transaction, incoming: Transaction): Transaction {
        val amountSource = if (amountRank(incoming.source) > amountRank(existing.source)) incoming else existing
        val merchantSource = listOf(existing, incoming)
            .filter { it.merchant.isNotBlank() && it.source != INFERRED }
            .maxByOrNull { merchantRank(it.source) }
            ?: existing
        val evidence = existing.evidence + incoming.evidence
        val timestamp = when {
            existing.source == INFERRED && incoming.source != INFERRED -> incoming.timestampMillis
            incoming.source == INFERRED -> existing.timestampMillis
            else -> minOf(existing.timestampMillis, incoming.timestampMillis)
        }
        val primary = if (existing.source == INFERRED && incoming.source != INFERRED) incoming.source else existing.source
        val userChose = existing.confidence >= 1.0 && existing.categoryId != null
        return existing.copy(
            timestampMillis = timestamp,
            amountMinor = amountSource.amountMinor,
            currency = amountSource.currency,
            amountCzkMinor = amountSource.amountCzkMinor,
            merchant = merchantSource.merchant,
            accountId = existing.accountId ?: incoming.accountId,
            categoryId = if (userChose) existing.categoryId else (existing.categoryId ?: incoming.categoryId),
            confidence = maxOf(existing.confidence, incoming.confidence),
            source = primary,
            status = if (EMAIL in evidence || BANK_NOTIFICATION in evidence) TransactionStatus.SETTLED else existing.status,
            sourcePackage = existing.sourcePackage ?: incoming.sourcePackage,
            evidence = evidence,
            note = listOf(existing.note, incoming.note).filter { it.isNotBlank() }.distinct().joinToString(" · ").take(200),
        )
    }

    private val GENERIC = setOf(
        "PLATBA", "KARTOU", "KARTA", "CARD", "PAYMENT", "PURCHASE", "TRANSAKCE", "VISA", "MASTERCARD",
        "GOOGLE", "WALLET", "PAY", "CZK", "EUR", "THE", "AND", "PAID", "TRANSFER", "PREVOD", "UCET", "UCTU",
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
