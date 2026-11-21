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
 * (≈ 6 minutes to several hours later, with the settled amount and the balance).
 *
 * Rules:
 * - A message already fused into a record (any of its [Transaction.fingerprints])
 *   is recognised when it is replayed.
 * - A record already confirmed by a source never absorbs a second report from
 *   that same source: two e-mails or two bank pushes are two purchases
 *   (e.g. two 89 Kč coffees).
 * - Time is compared as a RANGE: a date-only e-mail ("ze dne 10. 11.") happened
 *   somewhere on that day before it arrived. Windows: ±10 min between instant
 *   sources, ±3 h when an e-mail is involved, the stated day for date-only
 *   e-mails, 90 min for Wallet re-posts, 7 days for a transfer counterpart.
 * - Different accounts never merge.
 * - Among several candidates the one closest in time wins; a date-only e-mail
 *   confirms the unconfirmed payment of its day closest before it arrived.
 */
object Deduplicator {
    private const val MIN = 60 * 1000L
    const val WINDOW_MS: Long = 10 * MIN
    const val EMAIL_WINDOW_MS: Long = 3 * 60 * MIN
    const val TRANSFER_WINDOW_MS: Long = 7 * 24 * 60 * MIN
    const val SAME_MOMENT_MS: Long = 3 * MIN
    const val WALLET_REPOST_WINDOW_MS: Long = 90 * MIN

    /** Sources that fix the exact time of a payment. */
    private val INSTANT = setOf(BANK_NOTIFICATION, GOOGLE_WALLET, INFERRED)


    /** A push that arrives late (phone was off) may still confirm a payment its date-only e-mail reported. */
    const val LATE_PUSH_MS: Long = 12 * 60 * MIN

    fun findDuplicate(candidate: Transaction, recent: List<Transaction>): Transaction? {
        val all = recent.filter { isDuplicate(candidate, it) }
        // A match by name beats a match only by "same amount at the same moment".
        val matches = all.filterNot { matchedOnlyByMoment(candidate, it) }.ifEmpty { all }
        if (candidate.source == EMAIL && candidate.earliestMillis != null) {
            // Date-only e-mail: the unconfirmed payment of that day closest BEFORE it arrived;
            // otherwise the earliest push that was delivered late (after the e-mail arrived).
            val (inDay, delayed) = matches.partition { it.timestampMillis <= candidate.timestampMillis }
            return inDay.maxByOrNull { it.timestampMillis } ?: delayed.minByOrNull { it.timestampMillis }
        }
        // An exact-time record within minutes (Wallet: its re-post window) beats a vague date range
        // that merely contains the time.
        val near = if (candidate.source == GOOGLE_WALLET) WALLET_REPOST_WINDOW_MS else WINDOW_MS
        return matches.minWithOrNull(
            compareBy<Transaction>(
                { if (it.earliestMillis == null && gap(candidate, it) <= near) 0 else 1 },
                { gap(candidate, it) },
                { abs(it.timestampMillis - candidate.timestampMillis) },
            ),
        )
    }

    private fun matchedOnlyByMoment(candidate: Transaction, existing: Transaction): Boolean =
        candidate.source != INFERRED && existing.source != INFERRED && !existing.knows(candidate.fingerprint) &&
            !merchantsCompatible(candidate.merchant, existing.merchant)

    /** Distance between the two records' time ranges (0 when they overlap). */
    fun gap(a: Transaction, b: Transaction): Long {
        val aFrom = a.earliestMillis ?: a.timestampMillis
        val bFrom = b.earliestMillis ?: b.timestampMillis
        return when {
            a.timestampMillis < bFrom -> bFrom - a.timestampMillis
            b.timestampMillis < aFrom -> aFrom - b.timestampMillis
            else -> 0L
        }
    }

    fun isDuplicate(candidate: Transaction, existing: Transaction): Boolean {
        if (existing.knows(candidate.fingerprint)) return true
        if (candidate.amountCzkMinor.sign() != existing.amountCzkMinor.sign()) return false
        if (candidate.accountId != null && existing.accountId != null && candidate.accountId != existing.accountId) return false

        val inferred = candidate.source == INFERRED || existing.source == INFERRED
        // Google Wallet is a HINT, not a ledger: it re-posts the same payment (minutes or half
        // an hour later, sometimes twice in a row, sometimes with a corrected merchant name).
        // Its reports always fold into a matching payment; only bank sources (app notification,
        // e-mail), which report each payment exactly once, may split two identical purchases.
        val walletReport = candidate.source == GOOGLE_WALLET
        val dateOnlyMail = candidate.source == EMAIL && candidate.earliestMillis != null
        val latePush = (candidate.source == BANK_NOTIFICATION || walletReport) &&
            existing.evidence == setOf(EMAIL) && existing.earliestMillis != null &&
            candidate.timestampMillis >= existing.earliestMillis &&
            candidate.timestampMillis - existing.timestampMillis <= LATE_PUSH_MS
        val window = when {
            inferred -> TRANSFER_WINDOW_MS
            dateOnlyMail -> 0L
            latePush -> LATE_PUSH_MS
            candidate.source == EMAIL -> EMAIL_WINDOW_MS
            // The record's time is only as good as an e-mail's (no instant source fixed it yet).
            EMAIL in existing.evidence && (existing.earliestMillis != null || existing.evidence.none { it in INSTANT }) -> EMAIL_WINDOW_MS
            walletReport -> WALLET_REPOST_WINDOW_MS
            // A record timed only by Wallet may carry a RE-POST time; the bank push can be earlier.
            candidate.source == BANK_NOTIFICATION && GOOGLE_WALLET in existing.evidence &&
                BANK_NOTIFICATION !in existing.evidence && candidate.timestampMillis <= existing.timestampMillis -> WALLET_REPOST_WINDOW_MS
            else -> WINDOW_MS
        }
        if (dateOnlyMail) {
            // Exactly the stated day, up to the e-mail's arrival (capped at midnight), or a bank push
            // timed AFTER the e-mail arrived: that push was delivered late (the phone was off).
            val from = candidate.earliestMillis ?: candidate.timestampMillis
            val delayedPush = BANK_NOTIFICATION in existing.evidence && EMAIL !in existing.evidence &&
                existing.earliestMillis == null && existing.timestampMillis > candidate.timestampMillis &&
                existing.timestampMillis - candidate.timestampMillis <= LATE_PUSH_MS
            if (existing.timestampMillis < from || (existing.timestampMillis > candidate.timestampMillis && !delayedPush)) return false
        } else if (gap(candidate, existing) > window) {
            return false
        }
        // A record known only by its DATE accepts a report only within that day up to the e-mail's
        // arrival, a Wallet re-post up to 90 min later, or a push delivered late (phone was off).
        val ranged = existing.earliestMillis
        val grace = if (walletReport) WALLET_REPOST_WINDOW_MS else 0L
        if (ranged != null && !inferred && candidate.earliestMillis == null &&
            (candidate.timestampMillis < ranged || (candidate.timestampMillis > existing.timestampMillis + grace && !latePush))
        ) {
            return false
        }

        // Same BANK source twice = two real transactions (also for transfer counterparts; the
        // SAME counterpart re-created by a later report is caught by its fingerprint above).
        if (!walletReport && candidate.source in existing.evidence) return false
        val sameChannel = candidate.sourcePackage != null && candidate.sourcePackage == existing.sourcePackage
        if (sameChannel && !inferred && !walletReport) return false

        if (!amountsMatch(candidate, existing)) return false

        if (inferred) {
            // A transfer counterpart is identified by account + amount; the texts differ by nature.
            return candidate.accountId != null && candidate.accountId == existing.accountId
        }
        val strict = existing.source == MANUAL || candidate.source == MANUAL
        // Two apps reporting the exact same amount on the SAME account within 3 minutes is
        // the same payment, however differently they spell the merchant. Only for a source's FIRST
        // report of that record: a Wallet re-post must match by name (another shop may charge the
        // same amount at that moment).
        val sameAccount = candidate.accountId != null && candidate.accountId == existing.accountId
        if (!strict && sameAccount && candidate.source !in existing.evidence && abs(candidate.timestampMillis - existing.timestampMillis) <= SAME_MOMENT_MS && candidate.amountCzkMinor == existing.amountCzkMinor) {
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
        val ca = compact(a)
        val cb = compact(b)
        if (ca.length >= 4 && cb.length >= 4 && (ca.contains(cb) || cb.contains(ca))) return true
        // Only DISTINCTIVE words count: "PRAHA" or "KAVARNA" in both names says nothing
        // ("SHELL 4021 PRAHA" and "ALBERT PRAHA 5" are different shops).
        val da = ta - NOT_DISTINCTIVE
        val db = tb - NOT_DISTINCTIVE
        if (da.isEmpty() || db.isEmpty()) return false
        if (da.any { it in db }) return true
        // Every word of one name appears inside the other ("VERY", "GOODIES" in "NYXVERYGOODIESAS").
        if (da.all { cb.contains(it) } || db.all { ca.contains(it) }) return true
        // Shared word start ("GOODIES" vs "GOODIESAS").
        return da.any { x -> x.length >= 4 && db.any { y -> y.length >= 4 && (x.startsWith(y) || y.startsWith(x)) } }
    }

    /** Cities, countries, legal forms and venue words: shared by unrelated shops. */
    private val NOT_DISTINCTIVE = setOf(
        "PRAHA", "PRAGUE", "BRNO", "OSTRAVA", "PLZEN", "OLOMOUC", "LIBEREC", "PARDUBICE", "ZLIN", "KLADNO",
        "CESKE", "BUDEJOVICE", "HRADEC", "KRALOVE", "CESKA", "REPUBLIKA", "CZECH", "REPUBLIC", "CZE",
        "SRO", "SPOL", "GMBH", "LTD", "INC",
        "KAVARNA", "RESTAURACE", "RESTAURANT", "CAFE", "BISTRO", "BAR", "PUB", "SHOP", "STORE", "MARKET",
        "PRODEJNA", "POTRAVINY", "LEKARNA", "HOTEL", "OBCHOD", "ESHOP", "CENTRUM", "CENTER", "NAKUP", "DEKUJE",
    )

    private fun compact(s: String): String = TextNormalizer.merchantKey(s).filter(Char::isLetterOrDigit)

    /** Which source is trusted for the AMOUNT (the bank's settled CZK value wins). */
    private fun amountRank(s: TransactionSource) = when (s) {
        EMAIL -> 4
        BANK_NOTIFICATION -> 3
        MANUAL -> 2
        GOOGLE_WALLET -> 1
        else -> 0
    }

    /** Where a record's name came from (older records: derived from their evidence). */
    fun nameSourceOf(t: Transaction): TransactionSource = t.nameSource ?: when {
        t.evidence.size == 1 -> t.evidence.single()
        GOOGLE_WALLET in t.evidence -> GOOGLE_WALLET // older versions preferred the Wallet name
        BANK_NOTIFICATION in t.evidence -> BANK_NOTIFICATION
        else -> t.source
    }

    /**
     * How informative a record's NAME is. Bank boilerplate ("Odešla platba", "Payment to
     * 123/0800") is worth nothing; the bank e-mail names the shop or the counterparty from
     * structured fields; Wallet shows a brand; the push title is the weakest real name.
     */
    fun nameRank(t: Transaction): Int {
        if (GenericNames.isGeneric(t.merchant)) return 0
        return when (nameSourceOf(t)) {
            MANUAL -> 5
            EMAIL -> 4
            GOOGLE_WALLET -> 3
            INFERRED -> 1
            else -> 2
        }
    }

    /**
     * The report whose name the fused record keeps: the most informative one, except that a
     * Wallet brand beats the e-mail's terminal descriptor of the SAME shop
     * ("Very Goodies" vs "NYX*VeryGoodiesas"). When the two disagree about the shop,
     * the bank's descriptor is what was charged, so the e-mail wins.
     */
    private fun nameSourceRecord(existing: Transaction, incoming: Transaction): Transaction {
        val named = listOf(existing, incoming).filter { nameRank(it) > 0 }
        val wallet = named.firstOrNull { nameSourceOf(it) == GOOGLE_WALLET }
        val mail = named.firstOrNull { nameSourceOf(it) == EMAIL }
        if (wallet != null && mail != null && wallet !== mail && merchantsCompatible(wallet.merchant, mail.merchant)) return wallet
        return if (nameRank(incoming) > nameRank(existing)) incoming else existing
    }

    /**
     * The report whose category the fused record keeps. A category you picked never changes;
     * otherwise the bank e-mail's classification (made from its name, payment message and
     * counterparty account) replaces the push's guess, unless that would turn a confident
     * category into a mere suggestion.
     */
    private fun categorySourceRecord(existing: Transaction, incoming: Transaction): Transaction {
        val threshold = io.github.joey7500.vaultflow.domain.classify.Classifier.CONFIDENCE_THRESHOLD
        val eMail = EMAIL in existing.evidence
        val iMail = EMAIL in incoming.evidence
        return when {
            existing.categoryLocked -> existing
            incoming.categoryLocked -> incoming
            existing.confidence >= 1.0 && existing.categoryId != null -> existing // your rule / own transfer
            incoming.categoryId == null -> existing
            existing.categoryId == null -> incoming
            iMail && !eMail && (incoming.confidence >= threshold || existing.confidence < threshold) -> incoming
            eMail && !iMail && (existing.confidence >= threshold || incoming.confidence < threshold) -> existing
            incoming.confidence > existing.confidence -> incoming
            else -> existing
        }
    }

    /**
     * Combines two reports of one payment into a single record: the bank's amount, the most
     * informative name (see [nameSourceRecord]), the e-mail's description, counterparty account
     * and symbols, the earliest real timestamp, the category you chose (else the e-mail's),
     * and the union of all evidence.
     */
    fun merge(existing: Transaction, incoming: Transaction): Transaction {
        val amountSource = if (amountRank(incoming.source) > amountRank(existing.source)) incoming else existing
        val nameSource = nameSourceRecord(existing, incoming)
        val evidence = existing.evidence + incoming.evidence
        val (timestamp, earliest) = mergedTime(existing, incoming)
        val primary = if (existing.source == INFERRED && incoming.source != INFERRED) incoming.source else existing.source
        val categorySource = categorySourceRecord(existing, incoming)
        val other = if (categorySource === existing) incoming else existing
        // The e-mail's structured details first, then whatever the other report had.
        val details = if (EMAIL in incoming.evidence && EMAIL !in existing.evidence) listOf(incoming, existing) else listOf(existing, incoming)
        return existing.copy(
            timestampMillis = timestamp,
            amountMinor = amountSource.amountMinor,
            currency = amountSource.currency,
            amountCzkMinor = amountSource.amountCzkMinor,
            merchant = nameSource.merchant,
            nameSource = nameSourceOf(nameSource),
            accountId = existing.accountId ?: incoming.accountId,
            categoryId = categorySource.categoryId,
            confidence = if (categorySource.categoryId == null) maxOf(existing.confidence, incoming.confidence) else categorySource.confidence,
            categoryLocked = existing.categoryLocked || incoming.categoryLocked,
            source = primary,
            status = if (EMAIL in evidence || BANK_NOTIFICATION in evidence) TransactionStatus.SETTLED else existing.status,
            sourcePackage = existing.sourcePackage ?: incoming.sourcePackage,
            evidence = evidence,
            note = categorySource.note.ifBlank { other.note }.take(200),
            fingerprints = existing.fingerprints + incoming.fingerprints + listOfNotNull(existing.fingerprint, incoming.fingerprint),
            earliestMillis = earliest,
            description = details.map { it.description }.firstOrNull { it.isNotBlank() }.orEmpty(),
            counterpartyAccount = details.firstNotNullOfOrNull { it.counterpartyAccount },
            symbols = details.map { it.symbols }.firstOrNull { it.isNotBlank() }.orEmpty(),
            awaitingEmail = EMAIL !in evidence && (existing.awaitingEmail || incoming.awaitingEmail),
        )
    }

    /**
     * Time of the fused record: a real report beats the expected transfer counterpart; an exact
     * time beats a date-only range, except a late push that came after the e-mail's day ended.
     */
    private fun mergedTime(existing: Transaction, incoming: Transaction): Pair<Long, Long?> {
        // A date-only report keeps the counterpart's exact time when that time lies inside its range.
        fun inRange(exact: Transaction, ranged: Transaction) = ranged.earliestMillis != null &&
            exact.timestampMillis in ranged.earliestMillis..ranged.timestampMillis
        if (existing.source == INFERRED && incoming.source != INFERRED) {
            return if (inRange(existing, incoming)) existing.timestampMillis to null else incoming.timestampMillis to incoming.earliestMillis
        }
        if (incoming.source == INFERRED) {
            return if (inRange(incoming, existing)) incoming.timestampMillis to null else existing.timestampMillis to existing.earliestMillis
        }
        val exact = listOf(existing, incoming).filter { it.earliestMillis == null }
        // Wallet is a hint and may carry a RE-POST time: the bank push's time always wins.
        val pushed = listOf(existing, incoming).filter { BANK_NOTIFICATION in it.evidence }
        val walletOnly = listOf(existing, incoming).filter { it.evidence == setOf(GOOGLE_WALLET) }
        if (exact.size == 2 && pushed.size == 1 && walletOnly.size == 1) return pushed.single().timestampMillis to null
        return when (exact.size) {
            2 -> minOf(existing.timestampMillis, incoming.timestampMillis) to null
            0 -> minOf(existing.timestampMillis, incoming.timestampMillis) to
                maxOf(existing.earliestMillis ?: 0L, incoming.earliestMillis ?: 0L)
            else -> {
                val e = exact.single()
                val approx = if (e === existing) incoming else existing
                // A Wallet hint arriving onto an e-mail's date range may be a re-post: it never turns
                // the range into an exact time (a Wallet record that was there FIRST keeps its time).
                // A late push (after the e-mail arrived) keeps the e-mail's date.
                val hintOnly = e === incoming && e.evidence == setOf(GOOGLE_WALLET)
                if (hintOnly || e.timestampMillis > approx.timestampMillis) approx.timestampMillis to approx.earliestMillis else e.timestampMillis to null
            }
        }
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
