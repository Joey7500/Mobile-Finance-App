package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.classify.Classifier
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.EmailMode
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionSource.BANK_NOTIFICATION
import io.github.joey7500.vaultflow.domain.model.TransactionSource.EMAIL
import io.github.joey7500.vaultflow.domain.model.TransactionSource.GOOGLE_WALLET
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end scenarios of "e-mail first" on the ČS account (push books the payment at once,
 * the bank e-mail names and classifies it). Wording copied from real ČS e-mails, George pushes
 * and Google Wallet; every number, name and card is FAKE (accounts 1234567890/9999 = ČS,
 * 7654321/9998 = KB, 5550001/9997 = a stranger, card 1111).
 */
class EmailFirstScenarioTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val cs = 1L
    private val kb = 2L
    private val revolut = 3L

    private val accounts = listOf(
        Account(cs, "ČS", AccountType.BANK, 0, 0, ownIdentifiers = listOf("1234567890"), cardIdentifiers = listOf("1111")),
        Account(kb, "KB", AccountType.BANK, 0, 0, ownIdentifiers = listOf("7654321")),
        Account(revolut, "Revolut", AccountType.BANK, 0, 0, transferAliases = listOf("REVOLUT")),
    )
    private val ids = mapOf(
        CategoryKeys.GROCERIES to 2L, CategoryKeys.EATING_OUT to 3L, CategoryKeys.UNCERTAIN to 4L,
        CategoryKeys.FUN to 5L, CategoryKeys.RENT to 7L, CategoryKeys.INTERNAL_TRANSFER to 9L,
    )
    private val george = "cz.example.george"
    private val revolutApp = "com.revolut.revolut"
    private val csMail = "mail:info@bank.example"
    private val wallet = NotificationParser.GOOGLE_WALLET_PACKAGE
    private val senderAccount = mapOf(george to cs, revolutApp to revolut, csMail to cs)
    private val modes = mapOf(cs to EmailMode.ALL, kb to EmailMode.TRANSFERS, revolut to EmailMode.OFF)

    private fun at(h: Int, m: Int, day: Int = 20, s: Int = 0) =
        LocalDateTime.of(2026, 11, day, h, m, s).atZone(zone).toInstant().toEpochMilli()

    /** The app's database fed one message at a time, like FinanceRepository does. */
    private inner class Phone {
        val rows = mutableListOf<Transaction>()
        private var nextId = 1L
        private val seenMails = mutableSetOf<String>()

        fun receive(msg: IncomingMessage) {
            if (msg.channel == Channel.EMAIL && !seenMails.add(msg.messageKey)) return
            val ctx = IngestContext(
                defaultAccountId = senderAccount[msg.sourceKey], rules = emptyList(), accounts = accounts,
                categoryIdByKey = ids,
                recentTransactions = rows.filter { it.timestampMillis >= msg.receivedAtMillis - IngestionPipeline.RECENT_WINDOW_MS },
                fx = null, zone = zone, emailModeByAccount = modes,
            )
            val r = IngestionPipeline.process(msg, ctx)
            r.updates.forEach { u -> rows.replaceAll { if (it.id == u.id) u else it } }
            r.inserts.forEach { rows += it.copy(id = nextId++) }
        }

        fun on(account: Long) = rows.filter { it.accountId == account }
    }

    // ───────────── messages ─────────────

    private fun georgePush(t: Long, title: String, text: String, key: String = "g$t") =
        IncomingMessage(Channel.NOTIFICATION, george, key, t, title, text)

    private fun georgeCard(t: Long, shop: String, amount: String, key: String = "g$t") =
        georgePush(t, "💳 $shop", "$amount Kč platba kartou 1111.", key)

    private fun georgeTransfer(t: Long, amount: String = "400,00", key: String = "g$t") =
        georgePush(t, "Odešla platba", "$amount Kč", key)

    private fun walletCard(t: Long, brand: String, amount: String, key: String = "w$t") =
        IncomingMessage(Channel.NOTIFICATION, wallet, key, t, brand, "$amount Kč kartou Visa ••1111")

    private fun mail(t: Long, subject: String, body: String, key: String = "<$t@mail>") =
        IncomingMessage(Channel.EMAIL, csMail, key, t, subject, body)

    private fun cardMail(t: Long, shop: String, amount: String, day: String = "20. 11. 2026", key: String = "<$t@mail>") = mail(
        t, "Platba kartou u $shop",
        """Dobrý den, pane Nováku,
potvrzujeme platbu kartou 1111 ve výši $amount Kč ze dne $day u $shop.
Na účtu 1234567890 je k dispozici 18 204,50 Kč (včetně kontokorentu 1 000,00 Kč).
Česká spořitelna""",
        key,
    )

    private fun transferMail(t: Long, message: String, amount: String = "400,00", key: String = "<$t@mail>") = mail(
        t, "Odešla platba",
        """Dobrý den, pane Nováku,
z účtu 1234567890/9999 odešla platba ve výši $amount Kč.
Na účtu zůstává k dispozici 19 880,40 Kč (včetně kontokorentu 1 000,00 Kč).
Vaše Česká spořitelna
Informace o transakci
Datum a čas platby: 20. 11. 2026 10:12
Směr platby: odchozí
Z účtu: 1234567890/9999
Na účet: 5550001/9997
Částka v měně transakce: $amount Kč
Částka v měně účtu: $amount Kč
Konstantní symbol: 0
Zpráva pro příjemce: $message""",
        key,
    )

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { x -> permutations(items - x).map { listOf(x) + it } }

    // ───────────── 1. push, then the e-mail ~6 min later ─────────────

    @Test fun `1a transfer push then e-mail 6 min later - one record named by the payment message`() {
        val p = Phone()
        p.receive(georgeTransfer(at(10, 12), "400,00"))
        val first = p.on(cs).single()
        assertEquals("Odešla platba", first.merchant)
        assertTrue(first.awaitingEmail)
        assertEquals(at(10, 12), first.timestampMillis)

        p.receive(transferMail(at(10, 18), "padel 3. 12."))
        val tx = p.rows.single()
        assertEquals(cs, tx.accountId)
        assertEquals(-40_000L, tx.amountCzkMinor)
        assertEquals("Padel", tx.merchant)
        assertEquals("padel 3. 12.", tx.description)
        assertEquals("5550001/9997", tx.counterpartyAccount)
        assertEquals(5L, tx.categoryId)
        assertTrue(tx.confidence >= Classifier.CONFIDENCE_THRESHOLD)
        assertFalse(tx.awaitingEmail)
        assertEquals(setOf(BANK_NOTIFICATION, EMAIL), tx.evidence)
        assertEquals(at(10, 12), tx.timestampMillis) // the e-mail states the same exact time
        assertNull(tx.earliestMillis)
    }

    @Test fun `1b card push then e-mail 6 min later - one record named by the card shop`() {
        val p = Phone()
        p.receive(georgeCard(at(11, 8), "LIDL", "214,00"))
        assertTrue(p.rows.single().awaitingEmail)
        p.receive(cardMail(at(11, 14), "LIDL", "214,00"))
        val tx = p.rows.single()
        assertEquals(-21_400L, tx.amountCzkMinor)
        assertEquals("LIDL", tx.merchant)
        assertEquals(2L, tx.categoryId)
        assertFalse(tx.awaitingEmail)
        assertEquals(setOf(BANK_NOTIFICATION, EMAIL), tx.evidence)
        assertEquals(TransactionSource.EMAIL, tx.nameSource)
        // The push fixed the exact time; the date-only e-mail does not turn it into a range.
        assertEquals(at(11, 8), tx.timestampMillis)
        assertNull(tx.earliestMillis)
    }

    // ───────────── 2. e-mail first, push later (phone was off) ─────────────

    @Test fun `2a transfer e-mail first, late push keeps the e-mail's name and category`() {
        val p = Phone()
        p.receive(transferMail(at(10, 18), "padel 3. 12."))
        val mailOnly = p.rows.single()
        assertFalse(mailOnly.awaitingEmail)
        assertEquals("Padel", mailOnly.merchant)

        p.receive(georgeTransfer(at(10, 40), "400,00"))
        assertEquals("one record expected, got ${p.rows}", 1, p.rows.size)
        val tx = p.rows.single()
        assertEquals("Padel", tx.merchant)
        assertEquals("padel 3. 12.", tx.description)
        assertEquals("5550001/9997", tx.counterpartyAccount)
        assertEquals(5L, tx.categoryId)
        assertFalse(tx.awaitingEmail)
        assertEquals(setOf(BANK_NOTIFICATION, EMAIL), tx.evidence)
        assertEquals(at(10, 12), tx.timestampMillis)
    }

    @Test fun `2b card e-mail first, late push keeps the e-mail's name`() {
        val p = Phone()
        p.receive(cardMail(at(11, 14), "LIDL", "214,00"))
        p.receive(georgeCard(at(11, 50), "LIDL", "214,00"))
        val tx = p.rows.single()
        assertEquals("LIDL", tx.merchant)
        assertEquals(2L, tx.categoryId)
        assertFalse(tx.awaitingEmail)
        assertEquals(setOf(BANK_NOTIFICATION, EMAIL), tx.evidence)
    }

    // ───────────── 3. e-mail hours late ─────────────

    @Test fun `3a card e-mail the same evening still fuses into the push`() {
        val p = Phone()
        p.receive(georgeCard(at(12, 0), "LIDL", "214,00"))
        p.receive(cardMail(at(20, 30), "LIDL", "214,00"))
        val tx = p.rows.single()
        assertEquals(setOf(BANK_NOTIFICATION, EMAIL), tx.evidence)
        assertFalse(tx.awaitingEmail)
        assertEquals(at(12, 0), tx.timestampMillis)
        assertNull(tx.earliestMillis)
    }

    @Test fun `3b card e-mail the next morning (date only) still fuses into the push`() {
        val p = Phone()
        p.receive(georgeCard(at(21, 30), "LIDL", "214,00"))
        // Received after midnight, but the e-mail states the payment's date: 20. 11.
        p.receive(cardMail(at(8, 5, day = 21), "LIDL", "214,00"))
        val tx = p.rows.single()
        assertEquals(setOf(BANK_NOTIFICATION, EMAIL), tx.evidence)
        assertFalse(tx.awaitingEmail)
        assertEquals(at(21, 30), tx.timestampMillis)
        assertNull(tx.earliestMillis)
    }

    @Test fun `3c transfer e-mail 5 h later still fuses into the push`() {
        val p = Phone()
        p.receive(georgeTransfer(at(10, 12), "400,00"))
        p.receive(transferMail(at(15, 12), "padel 3. 12."))
        val tx = p.rows.single()
        assertEquals("Padel", tx.merchant)
        assertEquals(5L, tx.categoryId)
        assertFalse(tx.awaitingEmail)
        assertEquals(setOf(BANK_NOTIFICATION, EMAIL), tx.evidence)
        assertEquals(at(10, 12), tx.timestampMillis)
    }

    // ───────────── 4. the e-mail never arrives ─────────────

    @Test fun `4 without an e-mail the push stays final after the wait`() {
        val p = Phone()
        p.receive(georgeTransfer(at(10, 12), "400,00"))
        val tx = p.rows.single()
        assertEquals("Odešla platba", tx.merchant)
        assertEquals(setOf(BANK_NOTIFICATION), tx.evidence)
        assertTrue(tx.awaitingEmail)
        val wait = EmailFirst.DEFAULT_WAIT_MS
        assertTrue(EmailFirst.isWaiting(tx, at(10, 12) + wait - 60_000, wait))
        assertFalse(EmailFirst.isExpired(tx, at(10, 12) + wait - 60_000, wait))
        assertFalse(EmailFirst.isWaiting(tx, at(10, 12) + wait, wait))
        assertTrue(EmailFirst.isExpired(tx, at(10, 12) + wait, wait))
        assertTrue(EmailFirst.isExpired(tx, at(23, 0), wait))
        // Unrelated later traffic does not touch it.
        p.receive(georgeCard(at(14, 0), "LIDL", "50,00"))
        assertEquals(setOf(BANK_NOTIFICATION), p.rows.first().evidence)
        assertEquals("Odešla platba", p.rows.first().merchant)
    }

    // ───────────── 5. two identical real payments ─────────────

    private fun assertTwoEnrichedOnce(p: Phone) {
        val rows = p.on(cs)
        assertEquals(p.rows.toString(), 2, p.rows.size)
        rows.forEach {
            assertEquals(-8_900L, it.amountCzkMinor)
            assertEquals(setOf(BANK_NOTIFICATION, EMAIL), it.evidence)
            assertFalse(it.awaitingEmail)
            assertEquals("FAKE BAKERY 42", it.merchant)
            // push + its content key + exactly ONE e-mail fingerprint
            assertEquals(it.fingerprints.toString(), 3, it.fingerprints.size)
        }
        assertTrue(rows[0].fingerprints.intersect(rows[1].fingerprints).isEmpty())
    }

    @Test fun `5 two identical payments minutes apart - each record is enriched exactly once`() {
        val push1 = georgeCard(at(12, 0), "FAKE BAKERY 42", "89,00")
        val push2 = georgeCard(at(12, 10), "FAKE BAKERY 42", "89,00")
        val mail1 = cardMail(at(12, 6), "FAKE BAKERY 42", "89,00")
        val mail2 = cardMail(at(12, 16), "FAKE BAKERY 42", "89,00")
        val orders = mapOf(
            "P1 M1 P2 M2" to listOf(push1, mail1, push2, mail2),
            "P1 P2 M1 M2" to listOf(push1, push2, mail1, mail2),
            "P1 P2 M2 M1" to listOf(push1, push2, mail2, mail1),
            "M1 P1 M2 P2" to listOf(mail1, push1.copy(receivedAtMillis = at(12, 7)), mail2, push2.copy(receivedAtMillis = at(12, 17))),
        )
        for ((label, msgs) in orders) {
            val p = Phone()
            msgs.forEach(p::receive)
            try {
                assertTwoEnrichedOnce(p)
            } catch (e: AssertionError) {
                throw AssertionError("order $label: ${e.message}", e)
            }
        }
    }

    // ───────────── 6. own-account transfer ČS -> Revolut ─────────────

    private fun revolutTopUp(t: Long, amount: String) =
        IncomingMessage(Channel.NOTIFICATION, revolutApp, "r$t", t, "Dobití", "Přidali jsme $amount Kč z karty ••1111")

    private fun assertTopUpPair(p: Phone) {
        assertEquals(p.rows.toString(), 2, p.rows.size)
        val out = p.on(cs).single()
        val into = p.on(revolut).single()
        assertEquals(-30_000L, out.amountCzkMinor)
        assertEquals(30_000L, into.amountCzkMinor)
        assertEquals(9L, out.categoryId)
        assertEquals(9L, into.categoryId)
    }

    @Test fun `6a card top-up - George push first, Revolut push, then the e-mail`() {
        val p = Phone()
        p.receive(georgeCard(at(9, 0), "Revolut**5472*", "300,00"))
        p.receive(revolutTopUp(at(9, 0, s = 20), "300,00"))
        p.receive(cardMail(at(9, 6), "Revolut**5472*", "300,00"))
        assertTopUpPair(p)
        assertFalse(p.on(cs).single().awaitingEmail)
        assertTrue(EMAIL in p.on(cs).single().evidence)
    }

    @Test fun `6b card top-up - Revolut push first`() {
        val p = Phone()
        p.receive(revolutTopUp(at(9, 0), "300,00"))
        p.receive(georgeCard(at(9, 0, s = 20), "Revolut**5472*", "300,00"))
        p.receive(cardMail(at(9, 6), "Revolut**5472*", "300,00"))
        assertTopUpPair(p)
    }

    @Test fun `6c card top-up - the e-mail first, both pushes later`() {
        val p = Phone()
        p.receive(cardMail(at(9, 6), "Revolut**5472*", "300,00"))
        p.receive(georgeCard(at(9, 7), "Revolut**5472*", "300,00"))
        p.receive(revolutTopUp(at(9, 8), "300,00"))
        assertTopUpPair(p)
    }

    @Test fun `6d card top-up - only the George push and the Revolut push`() {
        val p = Phone()
        p.receive(georgeCard(at(9, 0), "Revolut**5472*", "300,00"))
        p.receive(revolutTopUp(at(9, 0, s = 20), "300,00"))
        assertTopUpPair(p)
        assertTrue(p.on(cs).single().awaitingEmail) // ČS still owes its e-mail
        assertFalse(p.on(revolut).single().awaitingEmail) // Revolut mode OFF
    }

    // ───────────── 7. Wallet + George + e-mail in all orders ─────────────

    private fun walletScenario(brand: String, shop: String, expectedName: String, offsets: List<Long>) {
        val failures = mutableListOf<String>()
        for (order in permutations(listOf("wallet", "push", "mail"))) {
            val p = Phone()
            order.forEachIndexed { i, kind ->
                val t = at(15, 26) + offsets[i]
                p.receive(
                    when (kind) {
                        "wallet" -> walletCard(t, brand, "58,40")
                        "push" -> georgeCard(t, shop, "58,40")
                        else -> cardMail(t, shop, "58,40")
                    },
                )
            }
            val tx = p.rows.singleOrNull()
            val problem = when {
                tx == null -> "${p.rows.size} records instead of 1: " + p.rows.joinToString { "${it.merchant}${it.evidence}" }
                tx.accountId != cs || tx.amountCzkMinor != -5_840L -> "wrong account/amount: $tx"
                tx.merchant != expectedName -> "name '${tx.merchant}' instead of '$expectedName'"
                EMAIL !in tx.evidence || tx.awaitingEmail -> "e-mail not fused / still waiting: $tx"
                else -> null
            }
            if (problem != null) failures += "order $order: $problem"
        }
        assertTrue("failing orders: " + failures.joinToString(" || "), failures.isEmpty())
    }

    /** Realistic delays: Wallet and the push within seconds, the e-mail ~6 min later. */
    private val realistic = listOf(0L, 30_000L, 6 * 60_000L)

    /** Everything within 3 minutes: names that disagree can only be linked by the "same moment" rule. */
    private val tight = listOf(0L, 30_000L, 100_000L)

    @Test fun `7a Wallet brand of the same shop beats the e-mail descriptor in every order`() =
        walletScenario("Very Goodies", "NYX*VeryGoodiesas", "Very Goodies", realistic)

    @Test fun `7b a Wallet name of another spelling that is not the same shop loses to the e-mail`() =
        walletScenario("Golden Dragon", "FAKE BAKERY 42", "FAKE BAKERY 42", tight)

    @Test fun `7c a generic Wallet title loses to the e-mail shop`() =
        walletScenario("Platba kartou", "FAKE BAKERY 42", "FAKE BAKERY 42", realistic)

    // ───────────── 8. manual edit before the e-mail ─────────────

    @Test fun `8 a category you locked survives the e-mail, the name and description still improve`() {
        val p = Phone()
        p.receive(georgeTransfer(at(10, 12), "400,00"))
        p.rows[0] = p.rows[0].copy(categoryId = 7L, confidence = 1.0, note = "set by you", categoryLocked = true)
        p.receive(transferMail(at(10, 18), "padel 3. 12."))
        val tx = p.rows.single()
        assertEquals(7L, tx.categoryId)
        assertEquals(1.0, tx.confidence, 0.0)
        assertEquals("set by you", tx.note)
        assertTrue(tx.categoryLocked)
        assertEquals("Padel", tx.merchant)
        assertEquals("padel 3. 12.", tx.description)
        assertEquals("5550001/9997", tx.counterpartyAccount)
        assertFalse(tx.awaitingEmail)
    }

    // ───────────── 9. REGRESSION: two different 400 Kč payments on one day ─────────────

    private fun assertTwoDistinctPayments(p: Phone) {
        val rows = p.on(cs)
        assertEquals(p.rows.toString(), 2, p.rows.size)
        rows.forEach { assertEquals(-40_000L, it.amountCzkMinor) }
        val pushRecord = rows.single { BANK_NOTIFICATION in it.evidence }
        assertNull("the push record keeps its exact time: ${p.rows}", pushRecord.earliestMillis)
        assertEquals(at(10, 12), pushRecord.timestampMillis)
        assertEquals("the push must fuse the TRANSFER e-mail: ${p.rows}", "5550001/9997", pushRecord.counterpartyAccount)
        assertEquals("fake note", pushRecord.description)
        val shopRecord = rows.single { it.id != pushRecord.id }
        assertTrue("the card payment is its own record: ${p.rows}", shopRecord.merchant.contains("FAKESHOP", ignoreCase = true))
        assertNull(shopRecord.counterpartyAccount)
    }

    @Test fun `9a REGRESSION a date-only card e-mail must not eat the push of a different 400 Kc transfer`() {
        val p = Phone()
        p.receive(georgeTransfer(at(10, 12), "400,00"))
        p.receive(cardMail(at(10, 14), "NYX*FAKESHOP.", "400,00"))
        p.receive(transferMail(at(10, 18), "fake note"))
        assertTwoDistinctPayments(p)
    }

    @Test fun `9b REGRESSION the card e-mail processed before the push must not eat it either`() {
        val p = Phone()
        p.receive(cardMail(at(10, 10), "NYX*FAKESHOP.", "400,00"))
        p.receive(georgeTransfer(at(10, 12), "400,00"))
        p.receive(transferMail(at(10, 18), "fake note"))
        assertTwoDistinctPayments(p)
    }

    // ───────────── 10. REGRESSION: possible duplicates ─────────────

    private fun tx(id: Long, t: Long, amount: Long, merchant: String, evidence: Set<TransactionSource>, account: Long = cs) = Transaction(
        id = id, timestampMillis = t, amountMinor = amount, merchant = merchant, accountId = account,
        source = evidence.first(), evidence = evidence, fingerprint = "f$id", sourcePackage = "p$id",
    )

    @Test fun `10a REGRESSION an e-mail-only record next to an app plus e-mail record is a possible duplicate`() {
        val rows = listOf(
            tx(1, at(12, 58), -3_000, "SKOLNI BUFET 1", setOf(EMAIL)),
            tx(2, at(12, 58), -3_000, "SKOLNI BUFET 1", setOf(BANK_NOTIFICATION, EMAIL)),
        )
        val pair = DuplicateRepair.suspects(rows, zone).singleOrNull()
        assertTrue("expected the pair to be offered, got ${DuplicateRepair.suspects(rows, zone)}", pair != null)
        assertEquals(setOf(1L, 2L), setOf(pair!!.first.id, pair.second.id))
    }

    @Test fun `10b records more than 3 minutes apart or with incompatible shops are not paired`() {
        val farApart = listOf(
            tx(1, at(12, 58), -3_000, "SKOLNI BUFET 1", setOf(EMAIL)),
            tx(2, at(13, 2), -3_000, "SKOLNI BUFET 1", setOf(BANK_NOTIFICATION, EMAIL)),
        )
        assertTrue(DuplicateRepair.suspects(farApart, zone).isEmpty())
        val otherShop = listOf(
            tx(1, at(12, 58), -3_000, "SKOLNI BUFET 1", setOf(EMAIL)),
            tx(2, at(12, 58), -3_000, "Albert", setOf(BANK_NOTIFICATION, EMAIL)),
        )
        assertTrue(DuplicateRepair.suspects(otherShop, zone).isEmpty())
        val otherAccount = listOf(
            tx(1, at(12, 58), -3_000, "SKOLNI BUFET 1", setOf(EMAIL), account = cs),
            tx(2, at(12, 58), -3_000, "SKOLNI BUFET 1", setOf(BANK_NOTIFICATION, EMAIL), account = kb),
        )
        assertTrue(DuplicateRepair.suspects(otherAccount, zone).isEmpty())
    }
}
