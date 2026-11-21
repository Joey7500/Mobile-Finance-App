package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.classify.Classifier
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.EmailMode
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.model.RuleOrigin
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "E-mail first": the push books the payment at once, the bank e-mail names and classifies it.
 * Wording copied from real ČS e-mails / George pushes; every number, name and card is FAKE.
 */
class EmailFirstTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val ids = mapOf(
        CategoryKeys.GROCERIES to 2L, CategoryKeys.EATING_OUT to 3L, CategoryKeys.UNCERTAIN to 4L,
        CategoryKeys.FUN to 5L, CategoryKeys.RENT to 7L, CategoryKeys.INTERNAL_TRANSFER to 9L,
    )
    private val cs = Account(1, "ČS", AccountType.BANK, 0, 0, ownIdentifiers = listOf("1234567890"), cardIdentifiers = listOf("1111"))
    private val revolut = Account(3, "Revolut", AccountType.BANK, 0, 0, transferAliases = listOf("REVOLUT"))
    private val george = "cz.example.george"
    private val csMail = "mail:info@bank.example"
    private val wallet = NotificationParser.GOOGLE_WALLET_PACKAGE

    private fun at(h: Int, m: Int, day: Int = 20) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun ctx(rows: List<Transaction>, mode: EmailMode? = EmailMode.ALL, rules: List<ClassificationRule> = emptyList()) = IngestContext(
        defaultAccountId = 1L, rules = rules, accounts = listOf(cs, revolut), categoryIdByKey = ids,
        recentTransactions = rows, fx = null, zone = zone,
        emailModeByAccount = if (mode == null) emptyMap() else mapOf(1L to mode),
    )

    private class Db {
        val rows = mutableListOf<Transaction>()
        var nextId = 1L
        fun apply(r: IngestResult) {
            r.updates.forEach { u -> rows.replaceAll { if (it.id == u.id) u else it } }
            r.inserts.forEach { rows += it.copy(id = nextId++) }
        }
    }

    private fun push(t: Long, title: String, text: String) = IncomingMessage(Channel.NOTIFICATION, george, "g$t", t, title, text)
    private fun mail(t: Long, subject: String, body: String) = IncomingMessage(Channel.EMAIL, csMail, "<$t@mail>", t, subject, body)

    private fun transferMail(message: String, amount: String = "400,00") = """Dobrý den, pane Nováku,
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
Zpráva pro příjemce: $message"""

    private fun cardMail(amount: String, merchant: String) = """Dobrý den, pane Nováku,
potvrzujeme platbu kartou 1111 ve výši $amount Kč ze dne 20. 11. 2026 u $merchant.
Na účtu 1234567890 je k dispozici 18 204,50 Kč (včetně kontokorentu 1 000,00 Kč).
Česká spořitelna"""

    @Test fun `the transfer e-mail names the push by its message and classifies it`() {
        val db = Db()
        db.apply(IngestionPipeline.process(push(at(10, 12), "Odešla platba", "400,00 Kč"), ctx(db.rows)))
        val provisional = db.rows.single()
        assertTrue(provisional.awaitingEmail)
        assertEquals(-40_000L, provisional.amountCzkMinor) // the money counts at once

        db.apply(IngestionPipeline.process(mail(at(10, 18), "Odešla platba", transferMail("padel 3. 12.")), ctx(db.rows)))
        val tx = db.rows.single()
        assertEquals("Padel", tx.merchant)
        assertEquals("padel 3. 12.", tx.description)
        assertEquals("5550001/9997", tx.counterpartyAccount)
        assertEquals(5L, tx.categoryId)
        assertTrue(tx.confidence >= Classifier.CONFIDENCE_THRESHOLD)
        assertFalse(tx.awaitingEmail)
        assertEquals(setOf(TransactionSource.BANK_NOTIFICATION, TransactionSource.EMAIL), tx.evidence)
        assertEquals(TransactionSource.EMAIL, tx.nameSource)
    }

    @Test fun `the card e-mail's shop replaces the push title`() {
        val db = Db()
        db.apply(IngestionPipeline.process(push(at(11, 8), "💳 HOSPODA U LIPY 2", "214,00 Kč platba kartou 1111."), ctx(db.rows)))
        assertTrue(db.rows.single().awaitingEmail)
        db.apply(IngestionPipeline.process(mail(at(11, 14), "Platba kartou u Hospoda U Lipy 2", cardMail("214,00", "Hospoda U Lipy 2")), ctx(db.rows)))
        val tx = db.rows.single()
        assertEquals("Hospoda U Lipy 2", tx.merchant)
        assertFalse(tx.awaitingEmail)
    }

    @Test fun `a Wallet brand of the same shop beats the terminal descriptor`() {
        val db = Db()
        db.apply(IngestionPipeline.process(IncomingMessage(Channel.NOTIFICATION, wallet, "w1", at(15, 26), "Very Goodies", "58,40 Kč kartou Visa ••1111"), ctx(db.rows)))
        db.apply(IngestionPipeline.process(mail(at(15, 32), "Platba kartou u NYX*VeryGoodiesas", cardMail("58,40", "NYX*VeryGoodiesas")), ctx(db.rows)))
        assertEquals("Very Goodies", db.rows.single().merchant)
    }

    @Test fun `a category you picked before the e-mail stays, the name still improves`() {
        val db = Db()
        db.apply(IngestionPipeline.process(push(at(10, 12), "Odešla platba", "400,00 Kč"), ctx(db.rows)))
        val chosen = db.rows.single().copy(categoryId = 7L, confidence = 1.0, note = "set by you", categoryLocked = true)
        db.rows[0] = chosen
        db.apply(IngestionPipeline.process(mail(at(10, 18), "Odešla platba", transferMail("padel 3. 12.")), ctx(db.rows)))
        val tx = db.rows.single()
        assertEquals(7L, tx.categoryId)
        assertTrue(tx.categoryLocked)
        assertEquals("set by you", tx.note)
        assertEquals("Padel", tx.merchant)
    }

    @Test fun `the e-mail never turns a confident category into a mere suggestion`() {
        val push = Transaction(
            id = 1, timestampMillis = at(18, 0), amountMinor = -18_000, merchant = "LIDL", accountId = 1, categoryId = 2,
            source = TransactionSource.BANK_NOTIFICATION, confidence = 0.92, note = "built-in: LIDL", awaitingEmail = true,
        )
        val weakMail = push.copy(id = 0, merchant = "LIDL 0815 PLUS", categoryId = 3, confidence = 0.65, note = "check: weak", source = TransactionSource.EMAIL, evidence = setOf(TransactionSource.EMAIL), awaitingEmail = false)
        val merged = Deduplicator.merge(push, weakMail)
        assertEquals(2L, merged.categoryId)
        assertEquals("built-in: LIDL", merged.note)
        assertEquals("LIDL 0815 PLUS", merged.merchant)
        // A confident e-mail category does replace the push's guess.
        val confidentMail = weakMail.copy(categoryId = 3, confidence = 0.9, note = "payment message: OBED")
        assertEquals(3L, Deduplicator.merge(push, confidentMail).categoryId)
    }

    @Test fun `who waits for an e-mail depends on the account's mode`() {
        fun waits(mode: EmailMode?, title: String, text: String) =
            IngestionPipeline.process(push(at(9, 0), title, text), ctx(emptyList(), mode)).inserts.single().awaitingEmail
        assertTrue(waits(EmailMode.ALL, "💳 LIDL", "180,00 Kč platba kartou 1111."))
        assertTrue(waits(EmailMode.ALL, "Odešla platba", "400,00 Kč"))
        assertFalse(waits(EmailMode.TRANSFERS, "💳 LIDL", "180,00 Kč platba kartou 1111."))
        assertTrue(waits(EmailMode.TRANSFERS, "Odešla platba", "400,00 Kč"))
        assertFalse(waits(EmailMode.OFF, "Odešla platba", "400,00 Kč"))
        assertFalse(waits(null, "Odešla platba", "400,00 Kč"))
        // An e-mail-only record never waits for itself.
        assertFalse(IngestionPipeline.process(mail(at(10, 18), "Odešla platba", transferMail("padel")), ctx(emptyList())).inserts.single().awaitingEmail)
    }

    @Test fun `the wait ends with the e-mail or after the timeout`() {
        val t = Transaction(timestampMillis = at(10, 0), amountMinor = -100, merchant = "x", source = TransactionSource.BANK_NOTIFICATION, awaitingEmail = true)
        val wait = EmailFirst.DEFAULT_WAIT_MS
        assertTrue(EmailFirst.isWaiting(t, at(12, 59), wait))
        assertTrue(EmailFirst.isExpired(t, at(13, 0), wait))
        val confirmed = t.copy(evidence = setOf(TransactionSource.BANK_NOTIFICATION, TransactionSource.EMAIL))
        assertTrue(EmailFirst.isExpired(confirmed, at(10, 7), wait))
        assertFalse(EmailFirst.isExpired(t.copy(awaitingEmail = false), at(23, 0), wait))
    }

    @Test fun `auto mode follows the bank whose e-mails are mapped to the account`() {
        assertEquals(EmailMode.ALL, EmailFirst.autoMode(listOf("csas.cz")))
        assertEquals(EmailMode.ALL, EmailFirst.autoMode(listOf("notifikace.csas.cz")))
        assertEquals(EmailMode.TRANSFERS, EmailFirst.autoMode(listOf("kb.cz")))
        assertEquals(EmailMode.OFF, EmailFirst.autoMode(emptyList()))
        assertEquals(EmailMode.OFF, EmailFirst.resolve(EmailMode.OFF, listOf("csas.cz")))
    }

    @Test fun `a name from the payment message drops dates, numbers and symbols`() {
        assertEquals("Padel", EmailFirst.nameFromMessage("padel 3. 12."))
        assertEquals("Nájem listopad", EmailFirst.nameFromMessage("nájem listopad 2026"))
        assertNull(EmailFirst.nameFromMessage("25. 11."))
        assertNull(EmailFirst.nameFromMessage("platba"))
    }

    @Test fun `the payment message decides the category`() {
        val c = Classifier(emptyList())
        assertEquals(CategoryKeys.FUN, c.classify("Padel", description = "padel 3. 12.").categoryKey)
        assertEquals(CategoryKeys.FUN, c.classify("Jan Novák", description = "squash út").categoryKey)
        assertEquals(CategoryKeys.EATING_OUT, c.classify("Jan Novák", description = "obědy listopad").categoryKey)
        // A friend's share of the rent coming in lowers the Rent spend.
        assertEquals(CategoryKeys.RENT, c.classify("Jan Novák", amountMinor = 650_000, description = "nájem listopad").categoryKey)
        assertEquals(0.9, c.classify("Jan Novák", description = "padel").confidence, 0.001)
    }

    @Test fun `a rule on the counterparty account still applies when the e-mail names the payment otherwise`() {
        val rentShare = ClassificationRule(1, "5550001/9997", RuleMatch.CONTAINS, 7L, 0, RuleOrigin.USER)
        val c = Classifier(listOf(rentShare))
        val r = c.classify("Jan Novák", amountMinor = 650_000, description = "listopad", counterpartyAccount = "000-5550001/9997")
        assertEquals(7L, r.categoryId)
        assertEquals(1.0, r.confidence, 0.0)
    }

    @Test fun `a learned word matches inside the payment message`() {
        val learned = ClassificationRule(1, "KROUZEK", RuleMatch.CONTAINS, 5L, 0, RuleOrigin.LEARNED, hits = 2)
        val r = Classifier(listOf(learned)).classify("Jan Novák", description = "kroužek keramiky 3/12")
        assertEquals(5L, r.categoryId)
        // Inside another word it does not count.
        assertNull(Classifier(listOf(learned)).classify("Jan Novák", description = "krouzekx").categoryId)
    }
}
