package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicatorTest {
    private val t0 = 1_700_000_000_000L

    private fun wallet(amount: Long, merchant: String, at: Long = t0, currency: String = "CZK", czk: Long = amount) = Transaction(
        timestampMillis = at, amountMinor = amount, currency = currency, amountCzkMinor = czk, merchant = merchant,
        source = TransactionSource.GOOGLE_WALLET, sourcePackage = NotificationParser.GOOGLE_WALLET_PACKAGE,
        status = if (currency == "CZK") TransactionStatus.SETTLED else TransactionStatus.PENDING, fingerprint = "w$at$amount",
    )

    private fun bank(amount: Long, merchant: String, at: Long = t0 + 8_000) = Transaction(
        timestampMillis = at, amountMinor = amount, merchant = merchant,
        source = TransactionSource.BANK_NOTIFICATION, sourcePackage = "com.example.bank", fingerprint = "b$at$amount",
    )

    @Test fun `wallet and bank report of the same purchase merge`() {
        assertTrue(Deduplicator.isDuplicate(bank(-18_000, "ALBERT 0123 PRAHA"), wallet(-18_000, "Albert")))
    }

    @Test fun `two identical coffees from the same app are NOT merged`() {
        val first = wallet(-8_900, "Starbucks")
        val second = wallet(-8_900, "Starbucks", at = t0 + 60_000)
        assertFalse(Deduplicator.isDuplicate(second, first))
    }

    @Test fun `different merchants with same amount are not merged`() {
        assertFalse(Deduplicator.isDuplicate(bank(-18_000, "SHELL BRNO"), wallet(-18_000, "Albert")))
    }

    @Test fun `outside window is not merged`() {
        assertFalse(Deduplicator.isDuplicate(bank(-18_000, "Albert", at = t0 + 11 * 60_000), wallet(-18_000, "Albert")))
    }

    @Test fun `generic bank text without merchant still merges with wallet`() {
        assertTrue(Deduplicator.isDuplicate(bank(-18_000, "Platba kartou"), wallet(-18_000, "Albert")))
    }

    @Test fun `fx estimate merges with settled czk amount within tolerance`() {
        val w = wallet(-1_099, "Spotify", currency = "EUR", czk = -26_700)
        val b = bank(-26_850, "SPOTIFY P1234")
        assertTrue(Deduplicator.isDuplicate(b, w))
        val merged = Deduplicator.merge(w, b)
        assertEquals(-26_850L, merged.amountCzkMinor)
        assertEquals(TransactionStatus.SETTLED, merged.status)
        assertEquals("Spotify", merged.merchant)
    }

    @Test fun `merge keeps the user's category`() {
        val w = wallet(-18_000, "Albert").copy(categoryId = 7)
        val merged = Deduplicator.merge(w, bank(-18_000, "ALBERT").copy(categoryId = 3))
        assertEquals(7L, merged.categoryId)
    }

    @Test fun `identical fingerprint is a duplicate`() {
        val a = wallet(-500, "X")
        assertTrue(Deduplicator.isDuplicate(a.copy(timestampMillis = t0 + 3_600_000), a))
    }
}
