package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.model.RuleOrigin
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How classifier results end up on captured transactions. Fake accounts and merchants only. */
class ClassificationPipelineTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val ids = mapOf(
        CategoryKeys.GROCERIES to 2L, CategoryKeys.EATING_OUT to 3L, CategoryKeys.UNCERTAIN to 4L,
        CategoryKeys.OTHER_EXPENSES to 8L, CategoryKeys.INTERNAL_TRANSFER to 9L, CategoryKeys.SALARY to 11L,
    )
    private val kinds = mapOf(2L to FlowKind.EXPENSE, 3L to FlowKind.EXPENSE, 8L to FlowKind.EXPENSE, 9L to FlowKind.TRANSFER, 11L to FlowKind.INCOME)
    private val bank = Account(1, "Fake Bank", AccountType.BANK, 0, 0, ownIdentifiers = listOf("1234567890"), cardIdentifiers = listOf("1111"))
    private val wallet = NotificationParser.GOOGLE_WALLET_PACKAGE
    private val bankApp = "com.example.fakebank"

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 12, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ctx(recent: List<Transaction> = emptyList(), rules: List<ClassificationRule> = emptyList()) =
        IngestContext(1L, rules, listOf(bank), ids, recent, null, zone, kinds)
    private fun notif(pkg: String, at: Long, title: String, text: String) = IncomingMessage(Channel.NOTIFICATION, pkg, "n$at$pkg", at, title, text)

    @Test fun `confident brand is categorised as before`() {
        val tx = IngestionPipeline.process(notif(wallet, at(9, 0), "NYX*Lidl 0123 Brno", "120,00 Kč"), ctx()).inserts.single()
        assertEquals(2L, tx.categoryId)
        assertTrue(tx.confidence >= 0.8)
    }

    @Test fun `weak guess keeps its category but low confidence and a check note`() {
        val tx = IngestionPipeline.process(notif(wallet, at(22, 0), "Bar U Fakeho", "90,00 Kč"), ctx()).inserts.single()
        assertEquals(3L, tx.categoryId)
        assertTrue(tx.confidence > 0.0 && tx.confidence < 0.8)
        assertTrue(tx.note, tx.note.startsWith("check: "))
    }

    @Test fun `nothing matched stays uncategorised`() {
        val tx = IngestionPipeline.process(notif(wallet, at(9, 0), "Xyz Fake Merchant", "120,00 Kč"), ctx()).inserts.single()
        assertNull(tx.categoryId)
        assertEquals(0.0, tx.confidence, 0.0)
    }

    @Test fun `learned rule after one correction arrives as a suggestion`() {
        val rules = listOf(ClassificationRule(1, "XYZ FAKE MERCHANT", RuleMatch.CONTAINS, 3, 0, RuleOrigin.LEARNED, 1))
        val tx = IngestionPipeline.process(notif(wallet, at(9, 0), "Xyz Fake Merchant", "120,00 Kč"), ctx(rules = rules)).inserts.single()
        assertEquals(3L, tx.categoryId)
        assertTrue(tx.confidence < 0.8)
    }

    @Test fun `a later more confident report upgrades a suggestion`() {
        val first = Transaction(
            id = 5, timestampMillis = at(12, 0), amountMinor = -9_000, merchant = "Bar U Fakeho", accountId = 1,
            categoryId = 3, confidence = 0.65, source = TransactionSource.GOOGLE_WALLET, sourcePackage = wallet, note = "check: built-in: BAR",
        )
        val incoming = first.copy(id = 0, categoryId = 2, confidence = 0.92, source = TransactionSource.BANK_NOTIFICATION, sourcePackage = bankApp, evidence = setOf(TransactionSource.BANK_NOTIFICATION))
        val merged = Deduplicator.merge(first, incoming)
        assertEquals(2L, merged.categoryId)
        assertEquals(0.92, merged.confidence, 0.0)
    }

    @Test fun `a less confident report never downgrades a category`() {
        val first = Transaction(id = 5, timestampMillis = at(12, 0), amountMinor = -9_000, merchant = "Lidl", accountId = 1, categoryId = 2, confidence = 0.92, source = TransactionSource.GOOGLE_WALLET)
        val incoming = first.copy(id = 0, categoryId = 3, confidence = 0.65, source = TransactionSource.EMAIL, evidence = setOf(TransactionSource.EMAIL))
        val merged = Deduplicator.merge(first, incoming)
        assertEquals(2L, merged.categoryId)
        assertEquals(0.92, merged.confidence, 0.0)
    }

    @Test fun `your own choice survives any later report`() {
        val mine = Transaction(id = 5, timestampMillis = at(12, 0), amountMinor = -9_000, merchant = "Lidl", accountId = 1, categoryId = 8, confidence = 1.0, note = "set by you", source = TransactionSource.GOOGLE_WALLET)
        val incoming = mine.copy(id = 0, categoryId = 2, confidence = 0.92, note = "", source = TransactionSource.EMAIL, evidence = setOf(TransactionSource.EMAIL))
        assertEquals(8L, Deduplicator.merge(mine, incoming).categoryId)
    }

    @Test fun `an uncategorised report does not erase a category`() {
        val first = Transaction(id = 5, timestampMillis = at(12, 0), amountMinor = -9_000, merchant = "Lidl", accountId = 1, categoryId = 2, confidence = 0.92, source = TransactionSource.GOOGLE_WALLET)
        val incoming = first.copy(id = 0, categoryId = null, confidence = 0.0, source = TransactionSource.EMAIL, evidence = setOf(TransactionSource.EMAIL))
        val merged = Deduplicator.merge(first, incoming)
        assertEquals(2L, merged.categoryId)
        assertEquals(0.92, merged.confidence, 0.0)
    }
}
