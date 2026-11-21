package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Re-reading stored e-mails for records captured before e-mail first (fake data only). */
class ReenricherTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val ids = mapOf(CategoryKeys.FUN to 5L, CategoryKeys.RENT to 7L, CategoryKeys.INTERNAL_TRANSFER to 9L, CategoryKeys.GROCERIES to 2L)
    private val cs = Account(1, "ČS", AccountType.BANK, 0, 0, ownIdentifiers = listOf("1234567890"), cardIdentifiers = listOf("1111"))
    private val ctx = IngestContext(1L, emptyList(), listOf(cs), ids, emptyList(), null, zone)
    private val at = LocalDateTime.of(2026, 11, 20, 10, 12).atZone(zone).toInstant().toEpochMilli()

    private val transferMail = IncomingMessage(
        Channel.EMAIL, "mail:info@bank.example", "<t1@mail>", at + 6 * 60_000, "Odešla platba",
        """Dobrý den, pane Nováku,
z účtu 1234567890/9999 odešla platba ve výši 400,00 Kč.
Informace o transakci
Datum a čas platby: 20. 11. 2026 10:12
Směr platby: odchozí
Z účtu: 1234567890/9999
Na účet: 5550001/9997
Konstantní symbol: 0308
Zpráva pro příjemce: padel 3. 12.""",
    )

    /** What 1.2.0 stored: the push's generic title won the merge. */
    private val legacy = Transaction(
        id = 12, timestampMillis = at, amountMinor = -40_000, merchant = "Odešla platba", accountId = 1,
        source = TransactionSource.BANK_NOTIFICATION, confidence = 0.0,
        evidence = setOf(TransactionSource.BANK_NOTIFICATION, TransactionSource.EMAIL),
        fingerprint = "push-fp", fingerprints = setOf("push-fp", Reenricher.fingerprintOf(transferMail)),
    )

    @Test fun `a stored generic name gets the e-mail's name, message, account, symbols and category`() {
        val r = Reenricher.enrich(legacy, transferMail, ctx)!!
        assertEquals("Padel", r.merchant)
        assertEquals("padel 3. 12.", r.description)
        assertEquals("5550001/9997", r.counterpartyAccount)
        assertEquals("KS 308", r.symbols)
        assertEquals(5L, r.categoryId)
        assertEquals(TransactionSource.EMAIL, r.nameSource)
        // Never touches money, time, account, evidence or fingerprints.
        assertEquals(legacy.amountMinor, r.amountMinor)
        assertEquals(legacy.timestampMillis, r.timestampMillis)
        assertEquals(legacy.accountId, r.accountId)
        assertEquals(legacy.evidence, r.evidence)
        assertEquals(legacy.fingerprints, r.fingerprints)
    }

    @Test fun `a category you picked stays`() {
        val chosen = legacy.copy(categoryId = 7, confidence = 1.0, note = "set by you")
        val r = Reenricher.enrich(chosen, transferMail, ctx)!!
        assertEquals(7L, r.categoryId)
        assertEquals("set by you", r.note)
        assertEquals("Padel", r.merchant)
    }

    @Test fun `running it twice changes nothing the second time`() {
        val once = Reenricher.enrich(legacy, transferMail, ctx)!!
        assertNull(Reenricher.enrich(once, transferMail, ctx))
    }

    @Test fun `an e-mail of another amount or a record without e-mail evidence is left alone`() {
        assertNull(Reenricher.enrich(legacy.copy(amountMinor = -50_000, amountCzkMinor = -50_000), transferMail, ctx))
        assertNull(Reenricher.enrich(legacy.copy(evidence = setOf(TransactionSource.BANK_NOTIFICATION)), transferMail, ctx))
    }

    @Test fun `a transfer between your accounts keeps its name`() {
        val own = legacy.copy(merchant = "Transfer to KB", categoryId = 9, confidence = 1.0, note = "transfer between your accounts")
        val r = Reenricher.enrich(own, transferMail, ctx)!!
        assertEquals("Transfer to KB", r.merchant)
        assertEquals(9L, r.categoryId)
        assertEquals("padel 3. 12.", r.description)
    }

    @Test fun `a truncated capture-log copy still names a card payment`() {
        val card = IncomingMessage(
            Channel.EMAIL, "mail:info@bank.example", "<c1@mail>", at, "Platba kartou u FAKESPORT s.r.o.",
            "Dobrý den, pane Nováku,\npotvrzujeme platbu kartou 1111 ve výši 356,00 Kč ze dne 20. 11. 2026 u FAKESPORT s.r.o..",
        )
        val stored = legacy.copy(merchant = "💳 FAKESPORT", amountMinor = -35_600, amountCzkMinor = -35_600, nameSource = TransactionSource.BANK_NOTIFICATION)
        val r = Reenricher.enrich(stored, card, ctx)!!
        assertEquals("FAKESPORT s.r.o.", r.merchant)
        assertTrue(r.description.isEmpty())
    }
}
