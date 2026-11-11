package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.fx.FxRates
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenarios modelled on real Česká spořitelna e-mails / George notifications.
 * All account numbers, cards and names are FAKE (bank codes 9999/9998).
 */
class IngestionPipelineTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val ids = mapOf(
        CategoryKeys.GROCERIES to 2L, CategoryKeys.UNCERTAIN to 4L, CategoryKeys.SUBSCRIPTIONS to 6L,
        CategoryKeys.INTERNAL_TRANSFER to 9L, CategoryKeys.EATING_OUT to 3L,
    )
    private val cs = Account(1, "ČS", AccountType.BANK, 0, 0, ownIdentifiers = listOf("1234567890"), cardIdentifiers = listOf("1111"))
    private val kb = Account(2, "KB", AccountType.BANK, 0, 0, ownIdentifiers = listOf("7654321"))
    private val revolut = Account(3, "Revolut", AccountType.BANK, 0, 0, transferAliases = listOf("REVOLUT"))
    private val accounts = listOf(cs, kb, revolut)
    private val fx = FxRates(null, mapOf("EUR" to 24.3))

    private val george = "cz.example.george"
    private val csMail = "mail:info@bank.example"
    private val kbMail = "mail:info@kb.example"
    private val wallet = NotificationParser.GOOGLE_WALLET_PACKAGE

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 10, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun ctx(recent: List<Transaction> = emptyList(), default: Long? = null) =
        IngestContext(default, emptyList(), accounts, ids, recent, fx, zone)

    private fun notif(pkg: String, at: Long, title: String, text: String, key: String = "n$at") =
        IncomingMessage(Channel.NOTIFICATION, pkg, key, at, title, text)

    private fun mail(from: String, at: Long, subject: String, body: String) =
        IncomingMessage(Channel.EMAIL, from, "<$at@mail>", at, subject, body)

    /** Simulates the repository: applies a result and assigns ids. */
    private class Db {
        val rows = mutableListOf<Transaction>()
        var nextId = 1L
        fun apply(r: IngestResult) {
            r.updates.forEach { u -> rows.replaceAll { if (it.id == u.id) u else it } }
            r.inserts.forEach { rows += it.copy(id = nextId++) }
        }
    }

    private fun cardMail(amount: String, merchant: String) = """Dobrý den, pane Nováku,
potvrzujeme platbu kartou 1111 ve výši $amount Kč ze dne 10. 11. 2026 u $merchant.
Na účtu 1234567890 je k dispozici 4700,00 Kč (včetně kontokorentu 1 000,00 Kč).
Česká spořitelna"""

    private val transferMail = """Dobrý den, pane Nováku,
z účtu 1234567890/9999 odešla platba ve výši 10,00 Kč.
Na účtu zůstává k dispozici 5000,00 Kč (včetně kontokorentu 1 000,00 Kč).
Vaše Česká spořitelna
Informace o transakci
Datum a čas platby: 10. 11. 2026 17:00
Směr platby: odchozí
Z účtu: 1234567890/9999
Na účet: 7654321/9998
Částka v měně transakce: 10,00 Kč
Částka v měně účtu: 10,00 Kč"""

    @Test fun `card e-mail top-up of Revolut is a transfer with a counterpart and a balance`() {
        val r = IngestionPipeline.process(mail(csMail, at(17, 6), "karta", cardMail("300,00", "Revolut**5472*")), ctx())
        assertEquals(2, r.inserts.size)
        val main = r.inserts[0]
        assertEquals(-30_000L, main.amountCzkMinor)
        assertEquals(1L, main.accountId)
        assertEquals(9L, main.categoryId)
        assertEquals(TransactionSource.EMAIL, main.source)
        val mirror = r.inserts[1]
        assertEquals(30_000L, mirror.amountCzkMinor)
        assertEquals(3L, mirror.accountId)
        assertEquals(TransactionSource.INFERRED, mirror.source)
        val b = r.balance!!
        assertEquals(1L, b.accountId)
        assertEquals(370_000L, b.ownFundsMinor) // 4 700 available − 1 000 overdraft
        assertEquals(100_000L, b.overdraftMinor)
    }

    @Test fun `transfer e-mail to own KB account`() {
        val r = IngestionPipeline.process(mail(csMail, at(17, 6), "platba účtem", transferMail), ctx())
        val main = r.inserts[0]
        assertEquals(-1_000L, main.amountCzkMinor)
        assertEquals(1L, main.accountId)
        assertEquals(9L, main.categoryId)
        assertEquals("Transfer to KB", main.merchant)
        assertEquals(at(17, 0), main.timestampMillis) // exact time from the e-mail, not the 6-min-late arrival
        assertEquals(2L, r.inserts[1].accountId)
        assertEquals(1_000L, r.inserts[1].amountCzkMinor)
        assertEquals(400_000L, r.balance!!.ownFundsMinor)
        assertEquals(at(17, 0), r.balance!!.asOfMillis)
    }

    @Test fun `payment to a stranger's account is not a transfer`() {
        val body = transferMail.replace("7654321/9998", "5550001/9997")
        val main = IngestionPipeline.process(mail(csMail, at(17, 6), "platba", body), ctx()).inserts.single()
        assertNull(main.categoryId)
        assertEquals("Payment to 5550001/9997", main.merchant)
    }

    @Test fun `george notification then e-mail 6 minutes later fuse into one transaction`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif(george, at(12, 52), "💳 VUT PRODEJNA 1", "72,90 CZK paid by card 1111."), ctx()))
        val r = IngestionPipeline.process(mail(csMail, at(12, 58), "karta", cardMail("72,90", "VUT PRODEJNA 1")), ctx(db.rows))
        db.apply(r)
        assertEquals(1, db.rows.size)
        val t = db.rows.single()
        assertEquals(setOf(TransactionSource.BANK_NOTIFICATION, TransactionSource.EMAIL), t.evidence)
        assertEquals("VUT PRODEJNA 1", t.merchant)
        assertEquals(1L, t.accountId)
        assertEquals(at(12, 52), t.timestampMillis)
        assertTrue(r.outcome.startsWith("merged"))
        assertNotNull(r.balance)
        assertTrue(r.balance!!.asOfMillis >= at(12, 52))
    }

    @Test fun `wallet, george and e-mail for one purchase give a single record`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif(wallet, at(9, 0), "Albert", "180,00 Kč · Visa ••1111"), ctx()))
        db.apply(IngestionPipeline.process(notif(george, at(9, 0) + 20_000, "ALBERT 0123", "180,00 CZK paid by card 1111."), ctx(db.rows)))
        db.apply(IngestionPipeline.process(mail(csMail, at(9, 6), "karta", cardMail("180,00", "ALBERT 0123")), ctx(db.rows)))
        assertEquals(1, db.rows.size)
        val t = db.rows.single()
        assertEquals(3, t.evidence.size)
        assertEquals("Albert", t.merchant) // Wallet has the nicest name
        assertEquals(2L, t.categoryId)
        assertEquals(TransactionStatus.SETTLED, t.status)
    }

    @Test fun `two identical coffees stay two transactions across all sources`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif(george, at(9, 0), "Starbucks", "89,00 CZK paid by card 1111."), ctx()))
        db.apply(IngestionPipeline.process(notif(george, at(9, 20), "Starbucks", "89,00 CZK paid by card 1111."), ctx(db.rows)))
        db.apply(IngestionPipeline.process(mail(csMail, at(9, 6), "karta", cardMail("89,00", "Starbucks")), ctx(db.rows)))
        db.apply(IngestionPipeline.process(mail(csMail, at(9, 26), "karta", cardMail("89,00", "Starbucks")), ctx(db.rows)))
        assertEquals(2, db.rows.size)
        assertTrue(db.rows.all { TransactionSource.EMAIL in it.evidence })
    }

    @Test fun `revolut app notification merges into the expected counterpart`() {
        val db = Db()
        db.apply(IngestionPipeline.process(mail(csMail, at(17, 6), "karta", cardMail("300,00", "Revolut**5472*")), ctx()))
        val r = IngestionPipeline.process(notif("com.revolut.revolut", at(17, 1), "Money added", "+300 Kč added via card"), ctx(db.rows, default = 3))
        db.apply(r)
        assertEquals(2, db.rows.size)
        val revolutSide = db.rows.single { it.accountId == 3L }
        assertTrue(TransactionSource.BANK_NOTIFICATION in revolutSide.evidence)
        assertEquals(9L, revolutSide.categoryId)
    }

    @Test fun `incoming KB e-mail confirms the transfer without duplicates`() {
        val db = Db()
        db.apply(IngestionPipeline.process(mail(csMail, at(17, 6), "platba účtem", transferMail), ctx()))
        val kbBody = "Na účet 7654321/9998 byla připsána platba 10,00 Kč z účtu 1234567890/9999. Datum: 10. 11. 2026 19:30"
        db.apply(IngestionPipeline.process(mail(kbMail, at(19, 35), "Příchozí platba", kbBody), ctx(db.rows, default = 2)))
        assertEquals(2, db.rows.size)
        val kbSide = db.rows.single { it.accountId == 2L }
        assertTrue(TransactionSource.EMAIL in kbSide.evidence)
        assertEquals(1_000L, kbSide.amountCzkMinor)
        assertEquals(TransactionStatus.SETTLED, kbSide.status)
    }

    @Test fun `daily balance e-mail is a balance observation only`() {
        val r = IngestionPipeline.process(
            mail(csMail, at(7, 0), "Zůstatek", "Zůstatek na účtu 1234567890/9999 k 10. 11. 2026 je 3 700,00 Kč."),
            ctx(),
        )
        assertTrue(r.inserts.isEmpty())
        assertEquals(1L, r.balance!!.accountId)
        assertEquals(370_000L, r.balance!!.ownFundsMinor)
    }

    @Test fun `wallet grocery purchase is inserted and categorised`() {
        val tx = IngestionPipeline.process(notif(wallet, at(10, 0), "Lidl", "180,00 Kč"), ctx()).inserts.single()
        assertEquals(-18_000L, tx.amountCzkMinor)
        assertEquals(2L, tx.categoryId)
        assertEquals(TransactionSource.GOOGLE_WALLET, tx.source)
    }

    @Test fun `unknown merchant is left uncategorised`() {
        val r = IngestionPipeline.process(notif("com.example.bank", at(10, 0), "Platba kartou", "Platba 450 Kč u obchodníka GOPAY *OBCHOD"), ctx())
        assertNull(r.inserts.single().categoryId)
    }

    @Test fun `exact repost is skipped`() {
        val db = Db()
        val m = notif(wallet, at(10, 0), "Lidl", "180,00 Kč", key = "same")
        db.apply(IngestionPipeline.process(m, ctx()))
        assertTrue(IngestionPipeline.process(m, ctx(db.rows)).isSkipped)
    }

    @Test fun `foreign currency is converted and pending`() {
        val tx = IngestionPipeline.process(notif(wallet, at(10, 0), "Spotify", "€10.00 · Visa"), ctx()).inserts.single()
        assertEquals(-24_300L, tx.amountCzkMinor)
        assertEquals(TransactionStatus.PENDING, tx.status)
        assertEquals(6L, tx.categoryId)
    }

    @Test fun `otp is skipped`() {
        assertTrue(IngestionPipeline.process(notif("com.example.bank", 1, "Ověření", "Kód pro platbu 300 Kč: 123456"), ctx()).isSkipped)
    }

    @Test fun `card digits route the payment, unknown card falls back to the sender's account`() {
        val known = IngestionPipeline.process(notif(george, 1, "VUT PRODEJNA 1", "72,90 CZK paid by card 1111."), ctx(default = 2))
        assertEquals(1L, known.inserts.single().accountId)
        val unknown = IngestionPipeline.process(notif(george, 1, "VUT PRODEJNA 1", "72,90 CZK paid by card 3333."), ctx(default = 2))
        assertEquals(2L, unknown.inserts.single().accountId)
    }

    private fun Db.balanceDelta(accountId: Long) = rows.filter { it.accountId == accountId }.sumOf { it.amountCzkMinor }

    @Test fun `two identical Revolut top-ups on the same day both reach Revolut`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif(george, at(17, 0), "\uD83D\uDCB3 Revolut**5472*", "300,00 Kč platba kartou 1111."), ctx()))
        db.apply(IngestionPipeline.process(notif(george, at(23, 8), "\uD83D\uDCB3 Revolut**5472*", "300,00 Kč platba kartou 1111."), ctx(db.rows)))
        assertEquals(-60_000L, db.balanceDelta(1))
        assertEquals(60_000L, db.balanceDelta(3))
    }

    @Test fun `george notification then ČS e-mail for a Revolut top-up keep exactly one counterpart`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif(george, at(23, 8), "\uD83D\uDCB3 Revolut**5472*", "300,00 Kč platba kartou 1111."), ctx()))
        db.apply(IngestionPipeline.process(mail(csMail, at(23, 14), "karta", cardMail("300,00", "Revolut**5472*")), ctx(db.rows)))
        assertEquals(2, db.rows.size)
        assertEquals(-30_000L, db.balanceDelta(1))
        assertEquals(30_000L, db.balanceDelta(3))
    }

    @Test fun `revolut notification naming the ČS card is booked on Revolut, not on ČS`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif(george, at(23, 8), "\uD83D\uDCB3 Revolut**5472*", "300,00 Kč platba kartou 1111."), ctx()))
        db.apply(IngestionPipeline.process(notif("com.revolut.revolut", at(23, 8) + 30_000, "Dobití", "Přidali jsme 300 Kč z karty ••1111"), ctx(db.rows, default = 3)))
        assertEquals(-30_000L, db.balanceDelta(1))
        assertEquals(30_000L, db.balanceDelta(3))
        assertEquals(2, db.rows.size)
    }

    @Test fun `revolut top-up arriving before the ČS report still balances both accounts`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif("com.revolut.revolut", at(23, 8), "Top up", "Top up of 300 CZK by card ••1111"), ctx(default = 3)))
        db.apply(IngestionPipeline.process(notif(george, at(23, 8) + 20_000, "\uD83D\uDCB3 Revolut**5472*", "300,00 Kč platba kartou 1111."), ctx(db.rows)))
        assertEquals(-30_000L, db.balanceDelta(1))
        assertEquals(30_000L, db.balanceDelta(3))
        assertTrue(db.rows.all { it.categoryId == 9L })
    }


    @Test fun `wallet and revolut report one payment with differently spelled merchants`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif("com.revolut.revolut", at(10, 4), "Very Goodies", "\uD83D\uDED2 Zaplaceno 18 Kč\nZůstatek CZK: 2 701,00 Kč"), ctx(default = 3)))
        db.apply(IngestionPipeline.process(notif(wallet, at(10, 4) + 2_000, "NYX*VeryGoodiesas", "18,00 Kč kartou Revolut Visa ••3043"), ctx(db.rows)))
        assertEquals(1, db.rows.size)
        val t = db.rows.single()
        assertEquals(-1_800L, t.amountCzkMinor)
        assertEquals(3L, t.accountId)
        assertEquals("NYX*VeryGoodiesas", t.merchant)
        assertEquals(setOf(TransactionSource.BANK_NOTIFICATION, TransactionSource.GOOGLE_WALLET), t.evidence)
    }

    @Test fun `wallet first, revolut second also merges and anchors after the payment`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif(wallet, at(10, 4), "NYX*VeryGoodiesas", "18,00 Kč kartou Revolut Visa ••3043"), ctx()))
        val r = IngestionPipeline.process(notif("com.revolut.revolut", at(10, 4) + 3_000, "Very Goodies", "Zaplaceno 18 Kč\nZůstatek CZK: 2 701,00 Kč"), ctx(db.rows, default = 3))
        db.apply(r)
        assertEquals(1, db.rows.size)
        assertEquals(270_100L, r.balance!!.ownFundsMinor)
        assertTrue(r.balance!!.asOfMillis >= db.rows.single().timestampMillis)
    }

    @Test fun `revolut card payment with EUR amount and CZK balance`() {
        val r = IngestionPipeline.process(
            notif("com.revolut.revolut", at(9, 32), "Anthropic", "Zaplaceno 532,69 Kč (21,78 €) u Anthropic\nZůstatek CZK: 2 719,31 Kč"),
            ctx(default = 3),
        )
        val tx = r.inserts.single()
        assertEquals(-53_269L, tx.amountCzkMinor)
        assertEquals(3L, tx.accountId)
        assertEquals(6L, tx.categoryId)
        assertEquals(271_931L, r.balance!!.ownFundsMinor)
    }

    @Test fun `balance of a foreign-currency pocket is not used as the CZK balance`() {
        val r = IngestionPipeline.process(notif("com.revolut.revolut", at(9, 32), "Anthropic", "Zaplaceno 21,78 € u Anthropic\nZůstatek EUR: 12,30 €"), ctx(default = 3))
        assertEquals(1, r.inserts.size)
        assertNull(r.balance)
    }

    @Test fun `two different shops with the same amount minutes apart stay separate`() {
        val db = Db()
        db.apply(IngestionPipeline.process(notif(wallet, at(10, 0), "Albert", "89,00 Kč · Visa ••1111"), ctx()))
        db.apply(IngestionPipeline.process(notif(george, at(10, 9), "Starbucks", "89,00 CZK paid by card 1111."), ctx(db.rows)))
        assertEquals(2, db.rows.size)
    }
}
