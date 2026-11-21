package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.classify.Classifier
import io.github.joey7500.vaultflow.domain.fx.FxRates
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.EmailMode
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.domain.text.TextNormalizer
import java.time.ZoneId
import kotlin.math.abs

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
    /** Kind of each category id (lets the classifier spot a rule that contradicts the money direction). */
    val categoryKindById: Map<Long, FlowKind> = emptyMap(),
    /** Resolved "bank e-mails are the main source" mode per account (see [EmailFirst]). */
    val emailModeByAccount: Map<Long, EmailMode> = emptyMap(),
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

    /** How far back the repository looks for records to fuse with (late e-mails, week-long transfers). */
    const val RECENT_WINDOW_MS: Long = 8L * 24 * 3600 * 1000

    /** The same notification (same id, same text) posted again within this time is a re-post. */
    const val REPOST_WINDOW_MS: Long = 2 * 60 * 1000L

    private const val MIRROR = ":mirror"

    /** An expected transfer counterpart is confirmed without an explicit account link only within this time. */
    const val UNLINKED_TRANSFER_WINDOW_MS: Long = 72L * 3600 * 1000

    /** A card top-up shows on both accounts within minutes (only its e-mail may be late, as a date range). */
    const val CARD_TRANSFER_WINDOW_MS: Long = 3L * 3600 * 1000

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

        // The e-mail names the payment from its structured fields: the card shop, the counterparty,
        // else the payment message ("padel 3. 12." -> "Padel"). See EmailFirst.
        val details = bank.details
        val emailName = if (msg.channel == Channel.EMAIL) {
            details.merchant ?: details.counterpartyName ?: details.message?.let(EmailFirst::nameFromMessage)
        } else {
            null
        }
        val merchant = when {
            counterparty != null && direction == Direction.OUT && !bank.hasExplicitMerchant -> "Transfer to ${counterparty.name}"
            counterparty != null && direction == Direction.IN && !bank.hasExplicitMerchant -> "Transfer from ${counterparty.name}"
            emailName != null -> emailName
            !bank.hasExplicitMerchant && msg.channel == Channel.EMAIL -> describeCounterpartyAccount(bank, direction) ?: parsed.merchant
            else -> parsed.merchant
        }
        val description = details.message.orEmpty()

        val classification = Classifier(ctx.rules, categoryKinds = ctx.categoryKindById).classify(
            merchant, listOfNotNull(msg.title, msg.text).joinToString(" "), parsed.amountMinor, sourceAccount?.type,
            description = description, counterpartyAccount = details.counterpartyAccount, currency = parsed.currency,
        )
        // Confident results and suggestions both get their category; a suggestion keeps its low
        // confidence so it shows up under "Quick review" instead of silently counting as certain.
        val categoryId = when {
            counterparty != null -> transferId
            classification.confidence <= 0.0 -> null
            classification.categoryId != null -> classification.categoryId
            else -> classification.categoryKey?.let(ctx.categoryIdByKey::get)
        }

        val source = when {
            msg.channel == Channel.EMAIL -> TransactionSource.EMAIL
            parsed.isFromWallet -> TransactionSource.GOOGLE_WALLET
            else -> TransactionSource.BANK_NOTIFICATION
        }
        // A date-only e-mail ("ze dne 10. 11. 2026") happened somewhere on that day before it arrived:
        // the record spans [start of that day, arrival] (arrival capped at the end of that day).
        val dateOnly = msg.channel == Channel.EMAIL && bank.eventTimeMillis == null && bank.eventDate != null
        val dayStart = bank.eventDate?.atStartOfDay(ctx.zone)?.toInstant()?.toEpochMilli()
        val dayEnd = bank.eventDate?.plusDays(1)?.atStartOfDay(ctx.zone)?.toInstant()?.toEpochMilli()?.minus(1)
        val timestamp = when {
            bank.eventTimeMillis != null -> bank.eventTimeMillis
            dateOnly && dayStart != null && dayEnd != null -> msg.receivedAtMillis.coerceIn(dayStart, dayEnd)
            msg.channel == Channel.EMAIL -> msg.receivedAtMillis - EMAIL_DELAY_MS
            else -> msg.receivedAtMillis
        }
        // A push for an account whose bank e-mails every such payment: provisional until the e-mail names it.
        val cardPayment = bank.cardDigits.isNotEmpty() || parsed.isFromWallet || details.kind == BankMessageKind.CARD_PAYMENT
        val awaitingEmail = msg.channel == Channel.NOTIFICATION &&
            EmailFirst.expectsEmail(sourceAccount?.let { ctx.emailModeByAccount[it.id] }, cardPayment)
        val fingerprint = TextNormalizer.fingerprint(msg.sourceKey, msg.messageKey, msg.title.orEmpty(), msg.text.orEmpty())
        // Same notification id + same text, without the post time: recognises an app re-posting it.
        val contentKey = if (msg.channel == Channel.NOTIFICATION) {
            "c:" + TextNormalizer.fingerprint(msg.sourceKey, msg.messageKey.substringBeforeLast('|'), msg.title.orEmpty(), msg.text.orEmpty())
        } else {
            null
        }

        // Replays: a message already fused into ANY recent record (not only the closest one).
        if (ctx.recentTransactions.any { it.knows(fingerprint) }) {
            return IngestResult(outcome = "skipped: already captured")
        }
        if (contentKey != null && ctx.recentTransactions.any { it.knows(contentKey) && abs(it.timestampMillis - timestamp) <= REPOST_WINDOW_MS }) {
            return IngestResult(outcome = "skipped: same notification posted again")
        }

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
            note = when {
                counterparty != null -> "transfer between your accounts"
                categoryId != null && classification.isSuggestion -> "check: " + classification.reason
                else -> classification.reason
            },
            sourcePackage = msg.sourceKey,
            fingerprint = fingerprint,
            fingerprints = setOfNotNull(fingerprint, contentKey),
            earliestMillis = if (dateOnly) dayStart else null,
            description = description,
            counterpartyAccount = details.counterpartyAccount,
            symbols = details.symbols,
            nameSource = source,
            awaitingEmail = awaitingEmail,
        )

        val inserts = mutableListOf<Transaction>()
        val updates = mutableListOf<Transaction>()
        val duplicate = Deduplicator.findDuplicate(candidate, fusionCandidates(msg, bank, direction, counterparty, candidate, ctx))

        val main: Transaction
        val outcome: String
        if (duplicate == null) {
            main = candidate
            inserts += candidate
            outcome = "added"
        } else {
            var merged = Deduplicator.merge(duplicate, candidate)
            if (counterparty != null && transferId != null && !isUserChoice(duplicate, transferId)) {
                merged = merged.copy(categoryId = transferId, confidence = 1.0, note = "transfer between your accounts")
            }
            main = merged
            updates += merged
            outcome = "merged with ${describe(duplicate)}"
        }

        // Transfer between your own accounts: the other account must show the money too.
        var mirrorOutcome = ""
        if (counterparty != null && counterparty.id != sourceAccount?.id) {
            // The counterpart is linked by fingerprint: record from message X <-> "X:mirror". A record
            // that started as a mirror points back to its origin, so the link never chains.
            val root = main.fingerprint ?: fingerprint
            val counterpartFp = if (root.endsWith(MIRROR)) root.removeSuffix(MIRROR) else root + MIRROR
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
                fingerprint = counterpartFp,
                earliestMillis = main.earliestMillis,
                description = main.description,
                nameSource = TransactionSource.INFERRED,
            )
            val others = ctx.recentTransactions.filter { it.id != main.id || main.id == 0L }
            val linked = others.firstOrNull { it.accountId == counterparty.id && it.knows(counterpartFp) }
            // A real report confirms the counterpart only if it looks like this transfer: already
            // booked as an internal transfer (it named our account), or not categorised at all and recent.
            // A record that already belongs to ANOTHER transfer pair is never claimed twice.
            val cardBased = bank.cardDigits.isNotEmpty()
            val confirmers = others.filter { t ->
                !isPaired(t, others) && (!cardBased || cardTopUpMatches(mirror, t)) && (
                    TransactionSource.INFERRED in t.evidence || t.categoryId == transferId ||
                        (t.categoryId == null && isGenericTransferText(t.merchant) &&
                            abs(t.timestampMillis - mirror.timestampMillis) <= UNLINKED_TRANSFER_WINDOW_MS)
                    )
            }
            val otherSide = linked ?: Deduplicator.findDuplicate(mirror, confirmers)
            when {
                otherSide == null -> {
                    inserts += mirror
                    mirrorOutcome = " + counterpart on ${counterparty.name}"
                }
                otherSide.knows(counterpartFp) -> Unit
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

    /**
     * Records this message may be fused with:
     * - an expected transfer counterpart is only confirmed by a transfer-like report: one that names
     *   the other own account (any time within the week) or carries no shop name ("Money added",
     *   within 72 h). A card payment at a shop never eats it, and neither does a stranger's payment;
     * - a Wallet report for a card stored on no account never folds into a payment of an account
     *   whose cards are known (it is another card);
     * - an e-mail never confirms a payment that happened after the e-mail arrived.
     */
    private fun fusionCandidates(
        msg: IncomingMessage, bank: BankMessage, direction: Direction, counterparty: Account?, candidate: Transaction, ctx: IngestContext,
    ): List<Transaction> {
        val cardBased = bank.cardDigits.isNotEmpty()
        val otherRole = if (direction == Direction.OUT) AccountRole.TO else AccountRole.FROM
        val stranger = counterparty == null && bank.accounts.any { it.role == otherRole && ownAccountFor(it, ctx) == null }
        val transferLike = !stranger && (counterparty != null || isGenericTransferText(candidate.merchant))
        val messageTime = bank.eventTimeMillis ?: msg.receivedAtMillis
        val unknownCard = bank.cardDigits.isNotEmpty() && cardAccount(bank, ctx) == null
        val accountsWithCards = ctx.accounts.filter { it.cardIdentifiers.isNotEmpty() }.map { it.id }.toSet()
        return ctx.recentTransactions.filter { t ->
            val isTransferCounterpart = TransactionSource.INFERRED in t.evidence
            val counterpartOk = transferLike &&
                (counterparty != null || abs(t.timestampMillis - messageTime) <= UNLINKED_TRANSFER_WINDOW_MS) &&
                (!cardBased || cardTopUpMatches(candidate, t))
            !(isTransferCounterpart && !counterpartOk) &&
                !(unknownCard && t.accountId in accountsWithCards) &&
                !(msg.channel == Channel.EMAIL && !isTransferCounterpart && t.timestampMillis > msg.receivedAtMillis + REPOST_WINDOW_MS && !isDelayedPush(t, msg))
        }
    }

    /**
     * Both sides of a card top-up happen at the same moment: within 3 h, and on the stated DAY
     * when one side is only known by its date (a date-only e-mail or its counterpart).
     */
    private fun cardTopUpMatches(a: Transaction, b: Transaction): Boolean {
        val aFrom = a.earliestMillis
        val bFrom = b.earliestMillis
        // A push timed after a date-only side's range was delivered late (phone was off).
        fun delayedPush(push: Transaction, ranged: Transaction) =
            TransactionSource.BANK_NOTIFICATION in push.evidence && push.earliestMillis == null &&
                push.timestampMillis > ranged.timestampMillis &&
                push.timestampMillis - ranged.timestampMillis <= Deduplicator.LATE_PUSH_MS
        return when {
            aFrom != null && bFrom != null -> Deduplicator.gap(a, b) == 0L
            aFrom != null -> b.timestampMillis in aFrom..a.timestampMillis || delayedPush(b, a)
            bFrom != null -> a.timestampMillis in bFrom..b.timestampMillis || delayedPush(a, b)
            else -> Deduplicator.gap(a, b) <= CARD_TRANSFER_WINDOW_MS
        }
    }

    /** A bank push timed after this e-mail arrived: delivered late because the phone was off. */
    private fun isDelayedPush(t: Transaction, mail: IncomingMessage): Boolean =
        TransactionSource.BANK_NOTIFICATION in t.evidence && TransactionSource.EMAIL !in t.evidence &&
            t.earliestMillis == null && t.timestampMillis - mail.receivedAtMillis <= Deduplicator.LATE_PUSH_MS

    private val TRANSFER_WORDS = setOf(
        "MONEY", "ADDED", "ADD", "TOP", "UP", "TOPUP", "DOBITI", "DOBIJENI", "PRIDANO", "PRIDALI", "JSME", "VKLAD",
        "DEPOSIT", "PRICHOZI", "ODCHOZI", "PLATBA", "INCOMING", "OUTGOING", "TRANSFER", "PREVOD", "FROM", "TO",
        "CARD", "KARTY", "KARTOU", "VIA", "PAYMENT", "Z", "NA", "UCET", "UCTU",
    )

    /** "Money added", "Dobití", "Příchozí platba": a text that names no shop. */
    private fun isGenericTransferText(merchant: String): Boolean =
        TextNormalizer.merchantKey(merchant).split(Regex("[^A-Z0-9]+")).filter { it.isNotBlank() && it.any(Char::isLetter) }
            .all { it in TRANSFER_WORDS }

    /**
     * Already one side of a transfer pair: it is (or absorbed) an expected counterpart, or another
     * record is its counterpart ("<its fingerprint>:mirror").
     */
    private fun isPaired(t: Transaction, all: List<Transaction>): Boolean {
        if (TransactionSource.INFERRED in t.evidence) return false // an open counterpart, may be confirmed
        if (t.fingerprints.any { it.endsWith(MIRROR) } || t.fingerprint?.endsWith(MIRROR) == true) return true
        val own = (t.fingerprints + listOfNotNull(t.fingerprint)).map { it + MIRROR }
        return all.any { o -> o.id != t.id && own.any { o.knows(it) } }
    }

    /** Card digits > account number in the message > account mapped to the sender. */
    fun resolveSourceAccount(bank: BankMessage, ctx: IngestContext): Account? {
        val mapped = ctx.defaultAccountId?.let { id -> ctx.accounts.firstOrNull { it.id == id } }
        cardAccount(bank, ctx)?.let { card ->
            // Outgoing card payment: the card's account paid. Incoming money that names a card of
            // ANOTHER account ("Revolut top-up from card ••1111") lands on the app's own account.
            val incoming = (bank.transaction?.amountMinor ?: -1L) > 0
            return if (incoming && mapped != null && mapped.id != card.id) mapped else card
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

    private fun cardAccount(bank: BankMessage, ctx: IngestContext): Account? =
        bank.cardDigits.firstNotNullOfOrNull { digits -> ctx.accounts.firstOrNull { a -> a.cardIdentifiers.any { it == digits } } }

    /** The OTHER own account involved: account number, your own card, or a merchant alias ("REVOLUT"). */
    fun resolveCounterparty(bank: BankMessage, merchant: String, direction: Direction, source: Account?, ctx: IngestContext): Account? {
        // Your own card from another account paid for this (top-up) -> that account is the other side.
        cardAccount(bank, ctx)?.takeIf { it.id != source?.id }?.let { return it }
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
        t.categoryLocked || (t.confidence >= 1.0 && t.categoryId != null && t.categoryId != transferId && t.note == "set by you")

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
