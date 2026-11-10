package io.github.joey7500.vaultflow.domain.ingest

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankMessageParserTest {
    private val zone = ZoneId.of("Europe/Prague")

    @Test fun `account roles from and to`() {
        val m = BankMessageParser.accountMentions("Z účtu: 19-1234567890/9999\nNa účet: 7654321/9998")
        assertEquals(AccountRole.FROM, m.first { it.number == "1234567890" }.role)
        assertEquals(AccountRole.TO, m.first { it.number == "7654321" }.role)
        assertEquals("9998", m.first { it.number == "7654321" }.bankCode)
    }

    @Test fun `bare account number after the word account`() {
        val m = BankMessageParser.accountMentions("Na účtu 1234567890 je k dispozici 4700,00 Kč")
        assertEquals("1234567890", m.single().number)
        assertEquals(AccountRole.MENTION, m.single().role)
    }

    @Test fun `dates are not account numbers`() {
        assertTrue(BankMessageParser.accountMentions("Výpis za 10/2026 je připraven").isEmpty())
    }

    @Test fun `card digits in czech, english and wallet style`() {
        assertEquals(listOf("1111"), BankMessageParser.cardDigits("platbu kartou 1111 ve výši"))
        assertEquals(listOf("2222"), BankMessageParser.cardDigits("72,90 CZK paid by card 2222."))
        assertEquals(listOf("3333"), BankMessageParser.cardDigits("180,00 Kč · Visa ••3333"))
        assertTrue(BankMessageParser.cardDigits("ref 139530 paid").isEmpty())
    }

    @Test fun `exact time and date-only`() {
        val (t, d) = BankMessageParser.eventTime("Datum a čas platby: 10. 11. 2026 17:00", zone)
        assertEquals(LocalDateTime.of(2026, 11, 10, 17, 0).atZone(zone).toInstant().toEpochMilli(), t)
        assertEquals(LocalDate.of(2026, 11, 10), d)
        val (t2, d2) = BankMessageParser.eventTime("ze dne 10. 11. 2026 u Revolut", zone)
        assertNull(t2)
        assertEquals(LocalDate.of(2026, 11, 10), d2)
    }

    @Test fun `card e-mail is not mistaken for a confirmation request`() {
        val msg = BankMessageParser.parse(
            "mail:x", Channel.EMAIL, "karta",
            "potvrzujeme platbu kartou 1111 ve výši 300,00 Kč ze dne 10. 11. 2026 u Revolut**5472*.\nNa účtu 1234567890 je k dispozici 4700,00 Kč (včetně kontokorentu 1 000,00 Kč).",
            zone,
        )!!
        assertEquals(-30_000L, msg.transaction!!.amountMinor)
        assertEquals("Revolut**5472*", msg.transaction!!.merchant)
        assertEquals(470_000L, msg.availableMinor)
        assertEquals(100_000L, msg.overdraftMinor)
        assertEquals(370_000L, msg.ownFundsMinor)
        assertTrue(msg.hasExplicitMerchant)
    }

    @Test fun `please-confirm requests are still ignored`() {
        assertNull(BankMessageParser.parse("x", Channel.NOTIFICATION, "Potvrďte platbu", "Potvrďte platbu 1 299 Kč v aplikaci", zone))
    }

    @Test fun `outgoing transfer with recipient line stays outgoing`() {
        val msg = BankMessageParser.parse("mail:x", Channel.EMAIL, "Odchozí platba", "Odchozí platba 500,00 Kč. Příjemce: Jan Novák", zone)!!
        assertEquals(-50_000L, msg.transaction!!.amountMinor)
    }

    @Test fun `balance-only message has no transaction`() {
        val msg = BankMessageParser.parse("mail:x", Channel.EMAIL, "Zůstatek", "Zůstatek na účtu 1234567890/9999 k 10. 11. 2026 je 3 700,00 Kč.", zone)!!
        assertNull(msg.transaction)
        assertEquals(370_000L, msg.availableMinor)
        assertFalse(msg.availableMayIncludeOverdraft)
    }

    @Test fun `user-typed account numbers are normalised`() {
        assertEquals("1234567890", BankMessageParser.normalizeAccountNumber("19-1234567890/9999"))
        assertTrue(BankMessageParser.sameAccount("1234567890", "567890"))
        assertFalse(BankMessageParser.sameAccount("1234567890", "890"))
    }
}
