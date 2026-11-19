package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionSource.BANK_NOTIFICATION
import io.github.joey7500.vaultflow.domain.model.TransactionSource.EMAIL
import io.github.joey7500.vaultflow.domain.model.TransactionSource.GOOGLE_WALLET
import io.github.joey7500.vaultflow.domain.model.TransactionSource.INFERRED
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One-time clean-up of duplicates stored by versions before 1.2.0. Fake data only. */
class DuplicateRepairV2Test {
    private val zone = ZoneId.of("Europe/Prague")
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun tx(id: Long, t: Long, amount: Long, merchant: String, evidence: Set<TransactionSource>, account: Long = 1, fp: String = "f$id") = Transaction(
        id = id, timestampMillis = t, amountMinor = amount, merchant = merchant, accountId = account,
        source = evidence.first(), evidence = evidence, fingerprint = fp, sourcePackage = "p$id",
    )

    @Test fun `one message stored twice is folded into the first record`() {
        val rows = listOf(
            tx(1, at(10, 10), -8_900, "Starbucks", setOf(BANK_NOTIFICATION, EMAIL), fp = "same"),
            tx(2, at(10, 13), -8_900, "STARBUCKS", setOf(EMAIL), fp = "same"),
            tx(3, at(10, 12), -8_900, "Starbucks", setOf(BANK_NOTIFICATION)),
        )
        val fix = DuplicateRepair.findProvable(rows).single()
        assertEquals(1L, fix.keep.id)
        assertEquals(listOf(2L), fix.remove.map { it.id })
    }

    @Test fun `a chained mirror counterpart is removed next to its origin`() {
        val rows = listOf(
            tx(1, at(10, 23, 8), 30_000, "Dobití", setOf(BANK_NOTIFICATION, INFERRED), account = 3, fp = "R"),
            tx(2, at(10, 23, 8), -30_000, "Transfer to Revolut", setOf(INFERRED, BANK_NOTIFICATION, EMAIL), account = 1, fp = "R:mirror"),
            tx(3, at(10, 23, 8), 30_000, "Transfer from ČS", setOf(INFERRED), account = 3, fp = "R:mirror:mirror"),
        )
        val fix = DuplicateRepair.findProvable(rows).single()
        assertEquals(1L, fix.keep.id)
        assertEquals(listOf(3L), fix.remove.map { it.id })
    }

    @Test fun `two real identical payments are never touched`() {
        val rows = listOf(
            tx(1, at(10, 9), -8_900, "Starbucks", setOf(BANK_NOTIFICATION, EMAIL)),
            tx(2, at(10, 9, 20), -8_900, "Starbucks", setOf(BANK_NOTIFICATION, EMAIL)),
            tx(3, at(10, 9, 40), -8_900, "Starbucks", setOf(GOOGLE_WALLET, BANK_NOTIFICATION)),
        )
        assertTrue(DuplicateRepair.findProvable(rows).isEmpty())
        assertTrue(DuplicateRepair.suspects(rows, zone).isEmpty())
    }

    @Test fun `an expected counterpart with a single mirror suffix is never removed`() {
        val rows = listOf(
            tx(1, at(10, 17), -1_000, "Transfer to KB", setOf(EMAIL), account = 1, fp = "T"),
            tx(2, at(10, 17), 1_000, "Transfer from ČS", setOf(INFERRED), account = 2, fp = "T:mirror"),
        )
        assertTrue(DuplicateRepair.findProvable(rows).isEmpty())
    }

    @Test fun `late e-mail duplicate is offered as a possible duplicate, not merged`() {
        val rows = listOf(
            tx(1, at(10, 10), -8_900, "Starbucks", setOf(BANK_NOTIFICATION, GOOGLE_WALLET)),
            tx(2, at(10, 13, 24), -8_900, "STARBUCKS", setOf(EMAIL)),
        )
        assertTrue(DuplicateRepair.findProvable(rows).isEmpty())
        val (push, mail) = DuplicateRepair.suspects(rows, zone).single()
        assertEquals(1L, push.id)
        assertEquals(2L, mail.id)
    }

    @Test fun `ambiguous pairs, other days and other shops are not suspects`() {
        val twoPushes = listOf(
            tx(1, at(10, 10), -8_900, "Starbucks", setOf(BANK_NOTIFICATION)),
            tx(2, at(10, 11), -8_900, "Starbucks", setOf(BANK_NOTIFICATION)),
            tx(3, at(10, 15), -8_900, "STARBUCKS", setOf(EMAIL)),
        )
        assertTrue(DuplicateRepair.suspects(twoPushes, zone).isEmpty())
        val otherDay = listOf(tx(1, at(10, 23), -8_900, "Starbucks", setOf(BANK_NOTIFICATION)), tx(2, at(11, 8), -8_900, "STARBUCKS", setOf(EMAIL)))
        assertTrue(DuplicateRepair.suspects(otherDay, zone).isEmpty())
        val otherShop = listOf(tx(1, at(10, 10), -8_900, "Starbucks", setOf(BANK_NOTIFICATION)), tx(2, at(10, 14), -8_900, "LIDL", setOf(EMAIL)))
        assertTrue(DuplicateRepair.suspects(otherShop, zone).isEmpty())
    }
}
