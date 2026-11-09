package io.github.joey7500.vaultflow.data.local

import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.CategoryEntity
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus
import io.github.joey7500.vaultflow.ui.components.minorToInput
import io.github.joey7500.vaultflow.ui.components.parseMoneyInput
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test fun `transaction survives a round trip through the entity`() {
        val tx = Transaction(
            id = 5, timestampMillis = 1_000, amountMinor = -1_099, currency = "EUR", amountCzkMinor = -26_755,
            merchant = "Spotify", accountId = 2, categoryId = 3, source = TransactionSource.GOOGLE_WALLET,
            status = TransactionStatus.PENDING, confidence = 0.9, note = "built-in", sourcePackage = "pkg", fingerprint = "fp",
        )
        assertEquals(tx, tx.toEntity().toDomain())
    }

    @Test fun `unknown enum values fall back safely`() {
        val c = CategoryEntity(1, null, "X", "G", "SOMETHING_NEW", 0, 0)
        assertEquals(FlowKind.EXPENSE, c.toDomain().kind)
    }

    @Test fun `own identifiers are split per line`() {
        val a = AccountEntity(1, "Main", "BANK", 0, 0, true, " 123456 \n\n 654321")
        assertEquals(listOf("123456", "654321"), a.toDomain().ownIdentifiers)
    }

    @Test fun `money input parsing`() {
        assertEquals(123_450L, parseMoneyInput("1 234,50"))
        assertEquals(500_000L, parseMoneyInput("5000 Kč"))
        assertEquals(null, parseMoneyInput(""))
        assertEquals("5000", minorToInput(500_000))
        assertEquals("12,05", minorToInput(1_205))
    }
}
