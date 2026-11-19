package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.Transaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Test harness that replays message streams through the REAL pipeline, the way
 * FinanceRepository does on the phone: per-sender default account, the 4-day
 * recent window, e-mails de-duplicated by Message-ID (unless the processed list
 * was lost), results applied with fresh ids.
 *
 * Everything is FAKE: bank codes 9999 / 9998, invented card digits and shops.
 */
object Sim {
    val zone: ZoneId = ZoneId.of("Europe/Prague")

    const val CS = 1L
    const val KB = 2L
    const val REVOLUT = 3L

    val accounts = listOf(
        Account(CS, "ČS", AccountType.BANK, 0, 0, ownIdentifiers = listOf("1234567890"), cardIdentifiers = listOf("1111")),
        Account(KB, "KB", AccountType.BANK, 0, 0, ownIdentifiers = listOf("7654321")),
        Account(REVOLUT, "Revolut", AccountType.BANK, 0, 0, cardIdentifiers = listOf("3043"), transferAliases = listOf("REVOLUT")),
    )
    val categoryIds = mapOf(
        CategoryKeys.GROCERIES to 2L, CategoryKeys.EATING_OUT to 3L, CategoryKeys.UNCERTAIN to 4L,
        CategoryKeys.SUBSCRIPTIONS to 6L, CategoryKeys.INTERNAL_TRANSFER to 9L,
    )

    const val GEORGE = "cz.example.george"
    const val REVOLUT_APP = "com.revolut.revolut"
    const val CS_MAIL = "mail:info@bank.example"
    const val KB_MAIL = "mail:info@kb.example"
    val WALLET = NotificationParser.GOOGLE_WALLET_PACKAGE

    /** Account each sender is mapped to in Settings (Wallet: none, it is only a hint). */
    val senderAccount = mapOf(GEORGE to CS, REVOLUT_APP to REVOLUT, CS_MAIL to CS, KB_MAIL to KB)

    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR
    const val RECENT_WINDOW_MS = IngestionPipeline.RECENT_WINDOW_MS

    private val dateFmt = DateTimeFormatter.ofPattern("d. M. yyyy")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("d. M. yyyy HH:mm")
    fun date(t: Long): String = Instant.ofEpochMilli(t).atZone(zone).format(dateFmt)
    fun dateTime(t: Long): String = Instant.ofEpochMilli(t).atZone(zone).format(dateTimeFmt)

    fun kc(minor: Long): String = "%d,%02d".format(minor / 100, minor % 100)

    // ───────────── message builders (formats taken from IngestionPipelineTest) ─────────────

    fun georgeCard(t: Long, merchant: String, minor: Long, key: String) =
        IncomingMessage(Channel.NOTIFICATION, GEORGE, key, t, "💳 $merchant", "${kc(minor)} Kč platba kartou 1111.")

    fun walletCard(t: Long, merchant: String, minor: Long, key: String, digits: String = "1111") =
        IncomingMessage(Channel.NOTIFICATION, WALLET, key, t, merchant, "${kc(minor)} Kč kartou Visa ••$digits")

    fun revolutCard(t: Long, merchant: String, minor: Long, key: String) =
        IncomingMessage(Channel.NOTIFICATION, REVOLUT_APP, key, t, merchant, "Zaplaceno ${kc(minor)} Kč")

    fun revolutTopUp(t: Long, minor: Long, key: String) =
        IncomingMessage(Channel.NOTIFICATION, REVOLUT_APP, key, t, "Dobití", "Přidali jsme ${kc(minor)} Kč z karty ••1111")

    /** ČS card e-mail: only the DATE of the payment is stated. */
    fun csCardMail(received: Long, paidAt: Long, merchant: String, minor: Long, id: String) = IncomingMessage(
        Channel.EMAIL, CS_MAIL, "<$id@mail>", received, "Platba kartou",
        """Dobrý den,
potvrzujeme platbu kartou 1111 ve výši ${kc(minor)} Kč ze dne ${date(paidAt)} u $merchant.
Česká spořitelna""",
    )

    /** ČS outgoing transfer e-mail with the exact time. */
    fun csTransferMail(received: Long, paidAt: Long, minor: Long, id: String) = IncomingMessage(
        Channel.EMAIL, CS_MAIL, "<$id@mail>", received, "Odchozí platba",
        """Dobrý den,
z účtu 1234567890/9999 odešla platba ve výši ${kc(minor)} Kč.
Informace o transakci
Datum a čas platby: ${dateTime(paidAt)}
Směr platby: odchozí
Z účtu: 1234567890/9999
Na účet: 7654321/9998
Částka v měně účtu: ${kc(minor)} Kč""",
    )

    /** KB incoming transfer e-mail (credited later, interbank clearing). */
    fun kbIncomingMail(received: Long, creditedAt: Long, minor: Long, id: String) = IncomingMessage(
        Channel.EMAIL, KB_MAIL, "<$id@mail>", received, "Příchozí platba",
        "Na účet 7654321/9998 byla připsána platba ${kc(minor)} Kč z účtu 1234567890/9999. Datum: ${dateTime(creditedAt)}",
    )

    // ───────────── the phone ─────────────

    /** The app's database + processed-message list, fed one message at a time. */
    class Phone {
        val rows = mutableListOf<Transaction>()
        private var nextId = 1L
        private val processedMails = mutableSetOf<String>()
        val outcomes = mutableListOf<String>()

        fun receive(msg: IncomingMessage) {
            if (msg.channel == Channel.EMAIL && !processedMails.add(msg.messageKey)) return
            val ctx = IngestContext(
                defaultAccountId = senderAccount[msg.sourceKey],
                rules = emptyList(),
                accounts = accounts,
                categoryIdByKey = categoryIds,
                recentTransactions = rows.filter { it.timestampMillis >= msg.receivedAtMillis - RECENT_WINDOW_MS },
                fx = null,
                zone = zone,
            )
            val r = IngestionPipeline.process(msg, ctx)
            val body = msg.text.orEmpty().lines().joinToString(" ").take(90)
            outcomes += "${dateTime(msg.receivedAtMillis)} ${msg.sourceKey.substringAfterLast('.')} [${msg.messageKey}] ${msg.title} | $body -> ${r.outcome}"
            r.updates.forEach { u -> rows.replaceAll { if (it.id == u.id) u else it } }
            r.inserts.forEach { rows += it.copy(id = nextId++) }
        }

        /** App data restored / processed list purged: e-mails can be seen again. */
        fun forgetProcessedMails() = processedMails.clear()

        fun count(account: Long) = rows.count { it.accountId == account }
        fun sum(account: Long) = rows.filter { it.accountId == account }.sumOf { it.amountCzkMinor }
    }

    // ───────────── random scenarios ─────────────

    /** One real-world payment and the effect it must have on each account. */
    data class Payment(
        val label: String,
        val deltas: Map<Long, Long>,
        val messages: List<IncomingMessage>,
        /** When it happened, and what makes it look identical to another payment (kind + shop + amount). */
        val at: Long = 0,
        val lookalike: String = label,
    )

    /** Shop names as the different sources spell them: (George / e-mail, Wallet, Revolut). */
    private val shops = listOf(
        Triple("ALBERT 0123", "Albert", "Albert"),
        Triple("LIDL DEKUJE ZA NAKUP", "Lidl dekuje za nakup", "Lidl"),
        Triple("Restaurace na Purkynce", "Restaurace Na Purkyňce", "Restaurace na Purkynce"),
        Triple("VERY GOODIES", "NYX*VeryGoodiesas", "Very Goodies"),
        Triple("FAKE BAKERY 42", "Fake Bakery", "Fake Bakery"),
        Triple("STARBUCKS", "Starbucks", "Starbucks"),
    )
    private val amounts = listOf(8_900L, 8_900L, 18_000L, 16_680L, 36_000L, 7_290L, 30_000L, 1_800L)

    /**
     * Random month of payments. Generator constraints (the limits of what ANY
     * matcher can know, see docs/ARCHITECTURE.md):
     * - every card payment is reported by at least one BANK source, or it has no
     *   same-amount twin within the Wallet re-post window on that account;
     * - two DIFFERENT shops never charge the same amount on the same card
     *   within 3 minutes (the "same moment" rule treats that as one payment);
     * - when the SAME shop charges the same amount twice on one day (or within
     *   12 h), both have the bank push. (One reported only by the push and the
     *   other only by its date-only e-mail is indistinguishable from one payment
     *   reported twice, or from a push delivered late because the phone was off.)
     */
    private enum class Kind { CS_CARD, REVOLUT_CARD, TOP_UP, TRANSFER }
    private data class Spec(val id: String, val kind: Kind, val at: Long, val shop: Triple<String, String, String>?, val amount: Long)

    fun randomPayments(rnd: Random, start: Long, count: Int): List<Payment> {
        // Pass 1: what happens when (so that "same shop, same amount, same day" is known in BOTH directions).
        val specs = mutableListOf<Spec>()
        var t = start
        var n = 0
        while (specs.size < count) {
            t += (rnd.nextLong(2, 240)) * MIN
            n++
            val kind = rnd.nextInt(100)
            when {
                kind < 50 -> { // ČS card purchase, maybe with a twin a few minutes later
                    val shop = shops.random(rnd)
                    val amount = amounts.random(rnd)
                    val twins = if (rnd.nextInt(100) < 25) 2 else 1
                    for (k in 0 until twins) {
                        val at = t + k * rnd.nextLong(4, 40) * MIN
                        val conflict = specs.any { it.kind == Kind.CS_CARD && it.amount == amount && it.shop != shop && kotlin.math.abs(it.at - at) <= 4 * MIN }
                        if (!conflict) specs += Spec("p$n-$k", Kind.CS_CARD, at, shop, amount)
                    }
                }
                kind < 70 -> specs += Spec("p$n", Kind.REVOLUT_CARD, t, shops.random(rnd), amounts.random(rnd))
                kind < 85 -> specs += Spec("p$n", Kind.TOP_UP, t, null, listOf(30_000L, 50_000L, 100_000L).random(rnd))
                else -> specs += Spec("p$n", Kind.TRANSFER, t, null, listOf(1_000L, 500_000L, 1_200_000L).random(rnd))
            }
        }
        // Same shop + amount on the same day, or within the late-push window (phone off overnight).
        fun repeatedSameDay(s: Spec) = specs.any { o ->
            o !== s && o.kind == s.kind && o.shop == s.shop && o.amount == s.amount &&
                (date(o.at) == date(s.at) || kotlin.math.abs(o.at - s.at) <= 13 * HOUR)
        }

        // Pass 2: which sources report each payment, and when.
        return specs.map { s -> buildPayment(rnd, s, ::repeatedSameDay).copy(at = s.at, lookalike = "${s.kind}|${s.shop?.first}|${s.amount}") }
    }

    private fun buildPayment(rnd: Random, s: Spec, repeatedSameDay: (Spec) -> Boolean): Payment {
        return run {
            val id = s.id
            val amount = s.amount
            when (s.kind) {
                Kind.CS_CARD -> csCardPurchase(rnd, id, s.at, s.shop!!, amount, repeatedSameDay(s))
                Kind.REVOLUT_CARD -> {
                    val shop = s.shop!!
                    val msgs = mutableListOf(revolutCard(s.at, shop.third, amount, "rv-$id"))
                    if (rnd.nextInt(100) < 70) msgs += walletCard(s.at + rnd.nextLong(0, 30_000), shop.second, amount, "w-$id", digits = "3043")
                    Payment("revolut $id ${shop.third} $amount", mapOf(REVOLUT to -amount), msgs)
                }
                Kind.TOP_UP -> {
                    val msgs = mutableListOf<IncomingMessage>()
                    if (repeatedSameDay(s) || rnd.nextInt(100) < 85) msgs += georgeCard(s.at, "Revolut**5472*", amount, "g-$id")
                    if (rnd.nextInt(100) < 85) msgs += revolutTopUp(s.at + rnd.nextLong(-20_000, 40_000), amount, "rt-$id")
                    if (msgs.isEmpty() || rnd.nextInt(100) < 70) {
                        msgs += csCardMail(s.at + rnd.nextLong(6 * MIN, 3 * HOUR), s.at, "Revolut**5472*", amount, "m-$id")
                    }
                    Payment("top-up $id $amount", mapOf(CS to -amount, REVOLUT to amount), msgs)
                }
                Kind.TRANSFER -> {
                    val credited = s.at + rnd.nextLong(30 * MIN, 30 * HOUR)
                    val msgs = mutableListOf(csTransferMail(s.at + rnd.nextLong(2 * MIN, 2 * HOUR), s.at, amount, "m-$id"))
                    if (rnd.nextInt(100) < 80) msgs += kbIncomingMail(credited + rnd.nextLong(MIN, 30 * MIN), credited, amount, "k-$id")
                    Payment("transfer $id $amount", mapOf(CS to -amount, KB to amount), msgs)
                }
            }
        }
    }

    private fun csCardPurchase(rnd: Random, id: String, at: Long, shop: Triple<String, String, String>, amount: Long, repeated: Boolean): Payment {
        val msgs = mutableListOf<IncomingMessage>()
        val george = repeated || rnd.nextInt(100) < 85 // keep it decidable, see above
        val mail = rnd.nextInt(100) < 85
        if (george) msgs += georgeCard(at, shop.first, amount, "g-$id")
        if (mail) msgs += csCardMail(at + rnd.nextLong(5 * MIN, 6 * HOUR), at, shop.first, amount, "m-$id")
        // Google Wallet: usually once, sometimes re-posted up to 90 minutes later with another spelling.
        if (rnd.nextInt(100) < 75 || msgs.isEmpty()) {
            msgs += walletCard(at + rnd.nextLong(0, 40_000), shop.second, amount, "w-$id")
            repeat(rnd.nextInt(0, 3)) { k ->
                val name = if (rnd.nextBoolean()) shop.second else shop.first
                msgs += walletCard(at + rnd.nextLong(MIN, 85 * MIN), name, amount, "w$k-$id")
            }
        }
        return Payment("cs-card $id ${shop.first} $amount", mapOf(CS to -amount), msgs)
    }
}
