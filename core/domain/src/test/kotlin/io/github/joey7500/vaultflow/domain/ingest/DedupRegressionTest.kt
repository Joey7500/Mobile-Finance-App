package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.ingest.Sim.CS
import io.github.joey7500.vaultflow.domain.ingest.Sim.MIN
import io.github.joey7500.vaultflow.domain.ingest.Sim.REVOLUT
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bugs found by DedupPropertyTest, each reduced to its smallest message sequence. Fake data only. */
class DedupRegressionTest {
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 10 + day, h, m).atZone(Sim.zone).toInstant().toEpochMilli()

    @Test fun `a card payment at a shop never confirms the expected counterpart of a top-up`() {
        val phone = Sim.Phone()
        phone.receive(Sim.revolutTopUp(at(0, 15, 19), 30_000, "rt1")) // Revolut first: ČS -300 expected
        phone.receive(Sim.georgeCard(at(0, 16, 50), "Restaurace na Purkynce", 30_000, "g-shop"))
        phone.receive(Sim.georgeCard(at(0, 15, 19), "Revolut**5472*", 30_000, "g-topup"))
        assertEquals(2, phone.count(CS))
        assertEquals(-60_000L, phone.sum(CS))
        assertEquals(1, phone.count(REVOLUT))
    }

    @Test fun `a top-up already paired with its counterpart is not claimed by the next top-up`() {
        val phone = Sim.Phone()
        phone.receive(Sim.revolutTopUp(at(0, 11, 58), 30_000, "rt1"))
        phone.receive(Sim.georgeCard(at(2, 23, 6), "Revolut**5472*", 30_000, "g2"))
        assertEquals(2, phone.count(REVOLUT))
        assertEquals(60_000L, phone.sum(REVOLUT))
    }

    @Test fun `a wallet re-post at the moment of another shop's same-amount payment folds by name`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 7, 27), "STARBUCKS", 8_900, "g1"))
        phone.receive(Sim.walletCard(at(0, 7, 27), "Starbucks", 8_900, "w1"))
        phone.receive(Sim.georgeCard(at(0, 7, 55), "Restaurace na Purkynce", 8_900, "g2"))
        phone.receive(Sim.walletCard(at(0, 7, 55), "Restaurace Na Purkyňce", 8_900, "w2"))
        phone.receive(Sim.walletCard(at(0, 7, 57), "Starbucks", 8_900, "w1-repost")) // 2 min after g2!
        phone.receive(Sim.walletCard(at(0, 8, 15), "Restaurace Na Purkyňce", 8_900, "w2-repost"))
        assertEquals(2, phone.count(CS))
        assertTrue(phone.rows.any { it.merchant.contains("Purky") })
    }

    @Test fun `identical payments on both sides of midnight keep their own e-mails`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 23, 58), "Restaurace na Purkynce", 8_900, "g1"))
        phone.receive(Sim.georgeCard(at(1, 0, 19), "Restaurace na Purkynce", 8_900, "g2"))
        phone.receive(Sim.csCardMail(at(1, 2, 43), at(1, 0, 19), "Restaurace na Purkynce", 8_900, "m2"))
        phone.receive(Sim.csCardMail(at(1, 5, 41), at(0, 23, 58), "Restaurace na Purkynce", 8_900, "m1"))
        assertEquals(2, phone.count(CS))
        assertTrue(phone.rows.all { TransactionSource.EMAIL in it.evidence })
    }

    @Test fun `phone off - late push first, the e-mail from the evening before fetched afterwards`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(1, 7, 0), "STARBUCKS", 8_900, "g-late")) // payment was 22:40 yesterday
        phone.receive(Sim.csCardMail(at(0, 22, 46), at(0, 22, 40), "STARBUCKS", 8_900, "m1"))
        assertEquals(1, phone.count(CS))
    }

    @Test fun `a bank app re-posting the same notification is one payment`() {
        val phone = Sim.Phone()
        val first = Sim.georgeCard(at(0, 9, 0), "STARBUCKS", 8_900, "george-42|1")
        phone.receive(first)
        phone.receive(first.copy(messageKey = "george-42|2", receivedAtMillis = first.receivedAtMillis + MIN)) // same id, new `when`
        assertEquals(1, phone.count(CS))
    }

    @Test fun `two identical coffees with separate notifications stay two`() {
        val phone = Sim.Phone()
        phone.receive(Sim.georgeCard(at(0, 9, 0), "STARBUCKS", 8_900, "george-42|1"))
        phone.receive(Sim.georgeCard(at(0, 9, 1), "STARBUCKS", 8_900, "george-43|2"))
        assertEquals(2, phone.count(CS))
    }
}
