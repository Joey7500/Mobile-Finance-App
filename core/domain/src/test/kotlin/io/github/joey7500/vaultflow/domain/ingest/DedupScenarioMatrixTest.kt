package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.ingest.Sim.CS
import io.github.joey7500.vaultflow.domain.ingest.Sim.KB
import io.github.joey7500.vaultflow.domain.ingest.Sim.MIN
import io.github.joey7500.vaultflow.domain.ingest.Sim.REVOLUT
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario matrix for capture de-duplication, replayed through the real pipeline
 * with [Sim.Phone]. Every test asserts the CORRECT behaviour. FAKE data only.
 * Day 0 is a Friday (2026-03-06); day 3 is the following Monday.
 */
class DedupScenarioMatrixTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.of(2026, 3, 6).plusDays(day.toLong()), java.time.LocalTime.of(hour, minute))
            .atZone(Sim.zone).toInstant().toEpochMilli()

    // ───────────── F1/F5: date-only e-mails arriving late ─────────────

    @Test
    fun `date-only card e-mail received 3,5 hours after the payment still merges with the George push`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 10), "STARBUCKS", 8_900, "g1"))
        phone.receive(Sim.csCardMail(at(0, 13, 30), at(0, 10), "STARBUCKS", 8_900, "m1"))
        assertEquals(1, phone.count(CS))
        assertEquals(setOf(TransactionSource.BANK_NOTIFICATION, TransactionSource.EMAIL), phone.rows.single().evidence)
    }

    @Test
    fun `phone off - e-mail for a 22h payment arrives first and the late George push the next morning folds into it`() {
        val phone = Sim.Phone()
        // e-mail states the payment day, received at 22:10 (George was missed, phone off)
        phone.receive(Sim.csCardMail(at(0, 22, 10), at(0, 22), "STARBUCKS", 8_900, "m1"))
        // phone back on: George push is posted at 07:00 next day
        phone.receive(Sim.georgeCard(at(1, 7), "STARBUCKS", 8_900, "g1"))
        assertEquals(1, phone.count(CS))
    }

    @Test
    fun `e-mail for a 23h50 payment received next morning at 8h merges with the George push`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 23, 50), "STARBUCKS", 8_900, "g1"))
        // states yesterday's date, received the next morning
        phone.receive(Sim.csCardMail(at(1, 8), at(0, 23, 50), "STARBUCKS", 8_900, "m1"))
        assertEquals(1, phone.count(CS))
        assertTrue(TransactionSource.EMAIL in phone.rows.single().evidence)
    }

    @Test
    fun `two identical coffees with late date-only e-mails stay two rows each confirmed by one e-mail`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 10), "STARBUCKS", 8_900, "g1"))
        phone.receive(Sim.georgeCard(at(0, 11, 30), "STARBUCKS", 8_900, "g2"))
        phone.receive(Sim.csCardMail(at(0, 12, 36), at(0, 10), "STARBUCKS", 8_900, "m1"))
        phone.receive(Sim.csCardMail(at(0, 14, 6), at(0, 11, 30), "STARBUCKS", 8_900, "m2"))
        assertEquals(2, phone.count(CS))
        assertEquals(2, phone.rows.count { TransactionSource.EMAIL in it.evidence })
        assertTrue(phone.rows.all { TransactionSource.BANK_NOTIFICATION in it.evidence })
    }

    // ───────────── F2: replays ─────────────

    @Test
    fun `same card e-mail replayed after the processed list was lost does not duplicate`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 10), "STARBUCKS", 8_900, "g1"))
        val mail = Sim.csCardMail(at(0, 10, 6), at(0, 10), "STARBUCKS", 8_900, "m1")
        phone.receive(mail)
        phone.forgetProcessedMails()
        phone.receive(mail)
        assertEquals(1, phone.count(CS))
    }

    @Test
    fun `the exact same George notification replayed after a Wallet plus George merge does not duplicate`() {
        val phone = Sim.Phone()
        phone.receive(Sim.walletCard(at(0, 10), "Albert", 18_000, "w1"))
        val george = Sim.georgeCard(at(0, 10, 1), "ALBERT 0123", 18_000, "g1")
        phone.receive(george)
        phone.receive(george)
        assertEquals(1, phone.count(CS))
    }

    @Test
    fun `transfer e-mails replayed after the processed list was lost keep one debit and one credit`() {
        val phone = Sim.Phone()
        val out = Sim.csTransferMail(at(0, 17, 2), at(0, 17), 500_000, "m1")
        phone.receive(out)
        phone.receive(Sim.kbIncomingMail(at(0, 18, 5), at(0, 18), 500_000, "k1"))
        phone.forgetProcessedMails()
        phone.receive(out)
        assertEquals(-500_000L, phone.sum(CS))
        assertEquals(500_000L, phone.sum(KB))
        assertEquals(2, phone.rows.size)
    }

    // ───────────── F3: mirror chain ─────────────

    @Test
    fun `Revolut top-up reported as Revolut push then George push then CS e-mail yields one debit and one credit`() {
        val phone = Sim.Phone()
        phone.receive(Sim.revolutTopUp(at(0, 10), 30_000, "rt1"))
        phone.receive(Sim.georgeCard(at(0, 10, 1), "Revolut**5472*", 30_000, "g1"))
        phone.receive(Sim.csCardMail(at(0, 10, 8), at(0, 10), "Revolut**5472*", 30_000, "m1"))
        assertEquals(-30_000L, phone.sum(CS))
        assertEquals(30_000L, phone.sum(REVOLUT))
        assertEquals(2, phone.rows.size)
    }

    @Test
    fun `KB incoming e-mail first then the CS transfer e-mail credit KB once and debit CS once`() {
        val phone = Sim.Phone()
        phone.receive(Sim.kbIncomingMail(at(0, 18, 5), at(0, 18), 500_000, "k1"))
        phone.receive(Sim.csTransferMail(at(0, 18, 20), at(0, 17), 500_000, "m1"))
        assertEquals(500_000L, phone.sum(KB))
        assertEquals(1, phone.count(KB))
        assertEquals(-500_000L, phone.sum(CS))
        assertEquals(1, phone.count(CS))
        assertEquals(2, phone.rows.size)
    }

    // ───────────── F6: slow interbank clearing ─────────────

    @Test
    fun `transfer credited 73 hours later over a weekend still yields two rows with KB plus the amount`() {
        val phone = Sim.Phone()
        phone.receive(Sim.csTransferMail(at(0, 17, 2), at(0, 17), 500_000, "m1"))
        phone.receive(Sim.kbIncomingMail(at(3, 18, 5), at(3, 18), 500_000, "k1")) // Monday 18:00 = +73 h
        assertEquals(2, phone.rows.size)
        assertEquals(500_000L, phone.sum(KB))
        assertEquals(-500_000L, phone.sum(CS))
    }

    // ───────────── F7: stranger credit of the same amount ─────────────

    @Test
    fun `a same-amount credit from a stranger account is not swallowed as the transfer counterpart`() {
        val phone = Sim.Phone()
        phone.receive(Sim.csTransferMail(at(0, 17, 2), at(0, 17), 1_000, "m1")) // CS -> KB, 10 Kc
        val stranger = IncomingMessage(
            Channel.EMAIL, Sim.KB_MAIL, "<k0@mail>", at(0, 18, 5), "Příchozí platba",
            "Na účet 7654321/9998 byla připsána platba 10,00 Kč z účtu 5550001/9997. Datum: ${Sim.dateTime(at(0, 18))}",
        )
        phone.receive(stranger)
        phone.receive(Sim.kbIncomingMail(at(0, 19, 35), at(0, 19, 30), 1_000, "k1")) // the real confirmation
        assertEquals(2, phone.count(KB))
        assertEquals(2_000L, phone.sum(KB))
        // only the real transfer row is an internal transfer; the stranger's row must not carry category 9
        assertEquals(1, phone.rows.count { it.accountId == KB && it.categoryId == 9L })
    }

    // ───────────── F9: Wallet vs bank, different shops, same amount ─────────────

    @Test
    fun `Wallet shop and George shop with the same amount 5 minutes apart but different merchants are two payments`() {
        val phone = Sim.Phone()
        phone.receive(Sim.walletCard(at(0, 10), "SHELL 4021 PRAHA", 30_000, "w1"))
        phone.receive(Sim.georgeCard(at(0, 10, 5), "ALBERT PRAHA 5", 30_000, "g1"))
        assertEquals(2, phone.count(CS))
    }

    // ───────────── F10: Wallet with a foreign card ─────────────

    // Since 1.2.0: a Wallet report naming a card that is stored on NO account is another card, so it
    // never folds into a payment of an account whose cards are known. Its own re-posts still fold.
    @Test
    fun `Wallet payment with a card stored on no account is a different payment from the George one`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 10), "STARBUCKS", 8_900, "g1"))
        phone.receive(Sim.walletCard(at(0, 10, 30), "Starbucks", 8_900, "w1", digits = "9876"))
        assertEquals(2, phone.rows.size)
    }

    // ───────────── Controls: must PASS today and stay green ─────────────

    @Test
    fun `CONTROL two identical coffees with George and a 6 minute late e-mail each stay two rows`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 10), "STARBUCKS", 8_900, "g1"))
        phone.receive(Sim.csCardMail(at(0, 10, 6), at(0, 10), "STARBUCKS", 8_900, "m1"))
        phone.receive(Sim.georgeCard(at(0, 11, 30), "STARBUCKS", 8_900, "g2"))
        phone.receive(Sim.csCardMail(at(0, 11, 36), at(0, 11, 30), "STARBUCKS", 8_900, "m2"))
        assertEquals(2, phone.count(CS))
    }

    @Test
    fun `CONTROL Wallet re-posted three times within 90 minutes plus George and e-mail is one row`() {
        val phone = Sim.Phone()
        phone.receive(Sim.walletCard(at(0, 10), "Albert", 18_000, "w1"))
        phone.receive(Sim.georgeCard(at(0, 10, 1), "ALBERT 0123", 18_000, "g1"))
        phone.receive(Sim.walletCard(at(0, 10) + 20 * MIN, "Albert", 18_000, "w2"))
        phone.receive(Sim.csCardMail(at(0, 10, 7), at(0, 10), "ALBERT 0123", 18_000, "m1"))
        phone.receive(Sim.walletCard(at(0, 10) + 45 * MIN, "Albert", 18_000, "w3"))
        assertEquals(1, phone.count(CS))
    }
}
