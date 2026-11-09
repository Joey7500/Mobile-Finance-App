package io.github.joey7500.vaultflow.domain.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {
    private val bank = "com.example.bank"
    private val wallet = NotificationParser.GOOGLE_WALLET_PACKAGE

    @Test fun `google wallet tap to pay`() {
        val p = NotificationParser.parse(wallet, "ALBERT HYPERMARKET", "245,00 Kč · Visa ••1234")!!
        assertEquals(-24_500L, p.amountMinor)
        assertEquals("ALBERT HYPERMARKET", p.merchant)
        assertTrue(p.isFromWallet)
    }

    @Test fun `wallet in english with currency first`() {
        val p = NotificationParser.parse(wallet, "Starbucks", "CZK 89.00 with Mastercard")!!
        assertEquals(-8_900L, p.amountMinor)
        assertEquals("Starbucks", p.merchant)
    }

    @Test fun `bank card payment with merchant`() {
        val p = NotificationParser.parse(bank, "Platba kartou", "Platba kartou 1 299,00 Kč u obchodníka ALZA.CZ dne 06.11.")!!
        assertEquals(-129_900L, p.amountMinor)
        assertEquals("ALZA.CZ", p.merchant)
    }

    @Test fun `incoming salary is positive`() {
        val p = NotificationParser.parse(bank, "Příchozí platba", "Na účet vám přišla platba 45 000,00 Kč od DEMO EMPLOYER S.R.O.")!!
        assertEquals(4_500_000L, p.amountMinor)
        assertTrue(p.merchant.contains("DEMO EMPLOYER"))
    }

    @Test fun `balance in the same notification is captured separately`() {
        val p = NotificationParser.parse(bank, "Odchozí platba", "Odchozí platba 500 Kč. Disponibilní zůstatek 10 250,30 Kč")!!
        assertEquals(-50_000L, p.amountMinor)
        assertEquals(1_025_030L, p.mentionedBalanceMinor)
    }

    @Test fun `3-D secure confirmation is ignored`() {
        assertNull(NotificationParser.parse(bank, "Potvrďte platbu", "Potvrďte platbu 1 299 Kč u ALZA.CZ v aplikaci"))
    }

    @Test fun `declined payment is ignored`() {
        assertNull(NotificationParser.parse(bank, "Platba zamítnuta", "Platba 450 Kč byla zamítnuta"))
    }

    @Test fun `card transaction word does not trigger promo filter`() {
        assertNotNull(NotificationParser.parse(bank, "Transakce kartou", "Transakce kartou 120 Kč"))
    }

    @Test fun `notification without amount is ignored`() {
        assertNull(NotificationParser.parse(bank, "Nový výpis", "Váš měsíční výpis je připraven"))
    }

    @Test fun `refund is an inflow`() {
        val p = NotificationParser.parse(bank, "Vrácení platby", "Vrácení platby 399 Kč od ZALANDO")!!
        assertEquals(39_900L, p.amountMinor)
    }

    @Test fun `foreign currency keeps its currency`() {
        val p = NotificationParser.parse(wallet, "Spotify", "€10.99 · Visa")!!
        assertEquals("EUR", p.currency)
        assertEquals(-1_099L, p.amountMinor)
    }

    @Test fun `george card payment - merchant comes from the title`() {
        val p = NotificationParser.parse("cz.csas.georgego", "\uD83D\uDCB3 VUT PRODEJNA 1", "72,90 CZK paid by card 3953.")!!
        assertEquals(-7_290L, p.amountMinor)
        assertEquals("CZK", p.currency)
        assertEquals("VUT PRODEJNA 1", p.merchant)
    }

    @Test fun `generic title falls back to body text`() {
        val p = NotificationParser.parse(bank, "Platba kartou", "Platba 120 Kč Trafika Novák")!!
        assertTrue(p.merchant.contains("Trafika"))
    }
}
