package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionSource.BANK_NOTIFICATION
import io.github.joey7500.vaultflow.domain.model.TransactionSource.EMAIL
import io.github.joey7500.vaultflow.domain.model.TransactionSource.GOOGLE_WALLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The duplicates stored on the evening of 11 Nov, as listed on the Budget screen. */
class DuplicateRepairTest {
    private val min = 60_000L
    private val t0 = 1_794_000_000_000L

    private fun tx(id: Long, at: Long, amount: Long, merchant: String, evidence: Set<TransactionSource>, account: Long? = 1) = Transaction(
        id = id, timestampMillis = at, amountMinor = amount, merchant = merchant, accountId = account,
        source = evidence.first(), evidence = evidence, categoryId = 3, sourcePackage = "p$id", fingerprint = "f$id",
    )

    private val stored = listOf(
        tx(1, t0, -36_000, "Restaurace na Purkynce", setOf(BANK_NOTIFICATION, GOOGLE_WALLET, EMAIL)),
        tx(2, t0 + 3 * min, -36_000, "Restaurace na Purkynce", setOf(GOOGLE_WALLET)),
        tx(3, t0 + 36 * min, -36_000, "Restaurace Na Purkyňce", setOf(GOOGLE_WALLET)),
        tx(4, t0 + 32 * min, -16_680, "Lidl dekuje za nakup", setOf(BANK_NOTIFICATION, GOOGLE_WALLET, EMAIL)),
        tx(5, t0 + 34 * min, -16_680, "Lidl dekuje za nakup", setOf(GOOGLE_WALLET)),
        tx(6, t0 - 500 * min, -1_800, "Very Goodies", setOf(BANK_NOTIFICATION), account = 3),
        tx(7, t0 - 500 * min, -1_800, "NYX*VeryGoodiesas", setOf(GOOGLE_WALLET), account = null),
        tx(8, t0 - 400 * min, -3_870, "Lidl dekuje za nakup", setOf(BANK_NOTIFICATION, EMAIL)),
    )

    @Test fun `folds every wallet-only duplicate into the bank-confirmed payment`() {
        val fixes = DuplicateRepair.find(stored)
        val removed = fixes.flatMap { it.remove }.map { it.id }.toSet()
        assertEquals(setOf(2L, 3L, 5L, 7L), removed)
        val keptIds = fixes.map { it.keep.id }.toSet()
        assertEquals(setOf(1L, 4L, 6L), keptIds)
        assertTrue(fixes.first { it.keep.id == 6L }.keep.evidence.contains(GOOGLE_WALLET))
    }

    @Test fun `different payments and a lone wallet purchase are left alone`() {
        val lone = tx(9, t0, -12_000, "Trafika", setOf(GOOGLE_WALLET))
        val other = tx(10, t0 + 5 * min, -12_000, "Pekárna", setOf(BANK_NOTIFICATION))
        assertTrue(DuplicateRepair.find(listOf(lone, other)).isEmpty())
    }
}
