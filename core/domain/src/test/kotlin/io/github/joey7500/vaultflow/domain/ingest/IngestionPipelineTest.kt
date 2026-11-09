package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.fx.FxRates
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionPipelineTest {
    private val ids = mapOf(CategoryKeys.GROCERIES to 2L, CategoryKeys.UNCERTAIN to 4L, CategoryKeys.SUBSCRIPTIONS to 6L)
    private val fx = FxRates(null, mapOf("EUR" to 24.3))
    private fun ctx(recent: List<io.github.joey7500.vaultflow.domain.model.Transaction> = emptyList()) =
        IngestContext(accountId = 1, rules = emptyList(), ownAccountIdentifiers = emptyList(), categoryIdByKey = ids, recentTransactions = recent, fx = fx)

    @Test fun `wallet grocery purchase is inserted and categorised`() {
        val d = IngestionPipeline.process(NotificationParser.GOOGLE_WALLET_PACKAGE, "k1", 1_000, "Lidl", "180,00 Kč", ctx())
        val tx = (d as IngestDecision.Insert).transaction
        assertEquals(-18_000L, tx.amountCzkMinor)
        assertEquals(2L, tx.categoryId)
        assertEquals(TransactionSource.GOOGLE_WALLET, tx.source)
    }

    @Test fun `unknown merchant is left uncategorised`() {
        val d = IngestionPipeline.process("com.example.bank", "k1", 1_000, "Platba kartou", "Platba 450 Kč u obchodníka GOPAY *OBCHOD", ctx())
        assertNull((d as IngestDecision.Insert).transaction.categoryId)
    }

    @Test fun `bank notification after wallet merges`() {
        val first = (IngestionPipeline.process(NotificationParser.GOOGLE_WALLET_PACKAGE, "k1", 1_000, "Lidl", "180,00 Kč", ctx()) as IngestDecision.Insert)
            .transaction.copy(id = 10)
        val d = IngestionPipeline.process("com.example.bank", "k2", 9_000, "Platba kartou", "Platba kartou 180,00 Kč u obchodníka LIDL CZ", ctx(listOf(first)))
        assertTrue(d is IngestDecision.Merge)
        assertEquals(10L, (d as IngestDecision.Merge).existing.id)
    }

    @Test fun `exact repost is skipped`() {
        val first = (IngestionPipeline.process(NotificationParser.GOOGLE_WALLET_PACKAGE, "k1", 1_000, "Lidl", "180,00 Kč", ctx()) as IngestDecision.Insert)
            .transaction
        val d = IngestionPipeline.process(NotificationParser.GOOGLE_WALLET_PACKAGE, "k1", 1_000, "Lidl", "180,00 Kč", ctx(listOf(first)))
        assertTrue(d is IngestDecision.Skip)
    }

    @Test fun `foreign currency is converted and pending`() {
        val d = IngestionPipeline.process(NotificationParser.GOOGLE_WALLET_PACKAGE, "k1", 1_000, "Spotify", "€10.00 · Visa", ctx())
        val tx = (d as IngestDecision.Insert).transaction
        assertEquals(-24_300L, tx.amountCzkMinor)
        assertEquals(TransactionStatus.PENDING, tx.status)
        assertEquals(6L, tx.categoryId)
    }

    @Test fun `otp is skipped`() {
        assertTrue(IngestionPipeline.process("com.example.bank", "k", 1, "Ověření", "Kód pro platbu 300 Kč: 123456", ctx()) is IngestDecision.Skip)
    }

    @Test fun `card digits route the payment to the right account`() {
        val c = ctx().copy(accountId = 1, cardAccounts = mapOf("3953" to 7L, "1111" to 8L))
        val d = IngestionPipeline.process("cz.csas.georgego", "k", 1, "VUT PRODEJNA 1", "72,90 CZK paid by card 3953.", c)
        assertEquals(7L, (d as IngestDecision.Insert).transaction.accountId)
    }

    @Test fun `unknown card falls back to the app's account`() {
        val c = ctx().copy(accountId = 1, cardAccounts = mapOf("1111" to 8L))
        val d = IngestionPipeline.process("cz.csas.georgego", "k", 1, "VUT PRODEJNA 1", "72,90 CZK paid by card 3953.", c)
        assertEquals(1L, (d as IngestDecision.Insert).transaction.accountId)
    }

    @Test fun `card digits must be a standalone number`() {
        assertNull(resolveCardAccount("ref 139530 paid", mapOf("3953" to 7L)))
    }
}
