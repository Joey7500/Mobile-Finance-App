package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structure of real Česká spořitelna e-mails (wording copied, every number,
 * name and card FAKE: accounts 1234567890/9999 and 5550001/9997, card 1111).
 */
class PaymentDetailsParserTest {
    private val zone = ZoneId.of("Europe/Prague")

    private fun cardMail(amount: String, date: String, merchant: String) = """Dobrý den, pane Nováku,
potvrzujeme platbu kartou 1111 ve výši $amount Kč ze dne $date u $merchant.
Na účtu 1234567890 je k dispozici 18 204,50 Kč (včetně kontokorentu 1 000,00 Kč).
Česká spořitelna
Bezpečí Vašich peněz je pro nás na prvním místě. Sledujte náš web!"""

    private val outgoingMail = """Dobrý den, pane Nováku,
z účtu 1234567890/9999 odešla platba ve výši 400,00 Kč.
Na účtu zůstává k dispozici 19 880,40 Kč (včetně kontokorentu 1 000,00 Kč).
Vaše Česká spořitelna
Informace o transakci
Datum a čas platby: 20. 11. 2026 10:12
Směr platby: odchozí
Z účtu: 1234567890/9999
Na účet: 5550001/9997
Částka v měně transakce: 400,00 Kč
Částka v měně účtu: 400,00 Kč
Konstantní symbol: 0
Zpráva pro příjemce: padel 3. 12."""

    private fun parse(subject: String, body: String, channel: Channel = Channel.EMAIL) =
        BankMessageParser.parse("mail:info@bank.example", channel, subject, body, zone)!!

    @Test fun `card e-mail - shop from the subject keeps its legal form`() {
        val msg = parse("Platba kartou u FAKESPORT s.r.o.", cardMail("356,00", "20. 11. 2026", "FAKESPORT s.r.o."))
        val d = msg.details
        assertEquals(BankMessageKind.CARD_PAYMENT, d.kind)
        assertEquals("FAKESPORT s.r.o.", d.merchant)
        assertNull(d.counterpartyAccount)
        assertEquals(-35_600L, msg.transaction!!.amountMinor)
        assertEquals(listOf("1111"), msg.cardDigits)
    }

    @Test fun `card e-mail - shop from the sentence when the subject is generic`() {
        assertEquals("FAKESPORT s.r.o.", parse("Platba kartou", cardMail("356,00", "20. 11. 2026", "FAKESPORT s.r.o.")).details.merchant)
        assertEquals("SKOLNI BUFET 1", parse("Platba kartou", cardMail("30,00", "18. 11. 2026", "SKOLNI BUFET 1")).details.merchant)
        assertEquals("NYX*FAKEFOODsro", parse("Platba kartou", cardMail("58,40", "20. 11. 2026", "NYX*FAKEFOODsro")).details.merchant)
    }

    @Test fun `card e-mail - the shop's thank-you is not part of its name`() {
        assertEquals("Lidl", parse("Platba kartou u Lidl dekuje za nakup", cardMail("84,30", "19. 11. 2026", "Lidl dekuje za nakup")).details.merchant)
        assertEquals("Lidl", parse("Platba kartou", cardMail("84,30", "19. 11. 2026", "Lidl dekuje za nakup")).details.merchant)
    }

    @Test fun `card e-mail - a trailing city is the location`() {
        val d = parse("Platba kartou u DECATHLON OLOMOUC", cardMail("689,00", "17. 11. 2026", "DECATHLON OLOMOUC")).details
        assertEquals("DECATHLON OLOMOUC", d.merchant)
        assertEquals("Olomouc", d.location)
        assertNull(parse("Platba kartou u Hospoda U Lipy 2", cardMail("214,00", "20. 11. 2026", "Hospoda U Lipy 2")).details.location)
    }

    @Test fun `outgoing transfer e-mail - account, message, symbols and exact time`() {
        val msg = parse("Odešla platba", outgoingMail)
        val d = msg.details
        assertEquals(BankMessageKind.OUTGOING_TRANSFER, d.kind)
        assertEquals("5550001/9997", d.counterpartyAccount)
        assertEquals("padel 3. 12.", d.message)
        assertNull(d.counterpartyName) // ČS does not state the recipient's name
        assertNull(d.constantSymbol) // "0" means none
        assertEquals("", d.symbols)
        assertNull(d.merchant)
        assertEquals(-40_000L, msg.transaction!!.amountMinor)
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 12).atZone(zone).toInstant().toEpochMilli(), msg.eventTimeMillis)
        assertEquals(1_888_040L, msg.ownFundsMinor)
    }

    @Test fun `a label alone on its line takes the next line as value`() {
        val body = outgoingMail.replace("Zpráva pro příjemce: padel 3. 12.", "Zpráva pro příjemce:\npadel 3. 12.")
        assertEquals("padel 3. 12.", parse("Odešla platba", body).details.message)
        // The time on its own line ("10:12") is never mistaken for a label.
        val split = outgoingMail.replace("Datum a čas platby: 20. 11. 2026 10:12", "Datum a čas platby:\n20. 11. 2026 10:12")
        assertEquals("padel 3. 12.", parse("Odešla platba", split).details.message)
    }

    @Test fun `incoming transfer e-mail - payer name, account, message and variable symbol`() {
        val body = """Dobrý den,
na účet 1234567890/9999 přišla platba ve výši 6 500,00 Kč.
Informace o transakci
Datum a čas platby: 28. 11. 2026 08:03
Směr platby: příchozí
Z účtu: 5550001/9997
Název protiúčtu: Jan Novák
Na účet: 1234567890/9999
Variabilní symbol: 0001126
Specifický symbol: 0
Zpráva pro příjemce: nájem listopad"""
        val msg = parse("Přišla platba", body)
        val d = msg.details
        assertEquals(BankMessageKind.INCOMING_TRANSFER, d.kind)
        assertEquals(650_000L, msg.transaction!!.amountMinor)
        assertEquals("5550001/9997", d.counterpartyAccount)
        assertEquals("Jan Novák", d.counterpartyName)
        assertEquals("nájem listopad", d.message)
        assertEquals("1126", d.variableSymbol)
        assertNull(d.specificSymbol)
        assertEquals("VS 1126", d.symbols)
    }

    @Test fun `KB incoming e-mail still parses, counterparty from the account roles`() {
        val msg = parse("Příchozí platba", "Na účet 7654321/9998 byla připsána platba 10,00 Kč z účtu 1234567890/9999. Datum: 10. 11. 2026 17:30")
        assertEquals(1_000L, msg.transaction!!.amountMinor)
        assertEquals(BankMessageKind.INCOMING_TRANSFER, msg.details.kind)
        assertEquals("1234567890/9999", msg.details.counterpartyAccount)
    }

    @Test fun `daily balance e-mail - the figure includes the overdraft, it is not the overdraft`() {
        val body = """Dobrý den,
dostupný zůstatek na Vašem účtu 1234567890
k 21.11.2026 00:59 je včetně kontokorentu 18 204,50 Kč.
Vaše Česká spořitelna"""
        val msg = parse("Zůstatek na účtu", body)
        assertNull(msg.transaction)
        assertEquals(BankMessageKind.BALANCE, msg.details.kind)
        assertEquals(1_820_450L, msg.availableMinor)
        assertNull(msg.overdraftMinor)
        assertTrue(msg.availableMayIncludeOverdraft)
        assertEquals(LocalDateTime.of(2026, 11, 21, 0, 59).atZone(zone).toInstant().toEpochMilli(), msg.eventTimeMillis)
    }

    @Test fun `daily balance e-mail - own funds are the balance minus the learned overdraft`() {
        val cs = Account(1, "ČS", AccountType.BANK, 0, 0, ownIdentifiers = listOf("1234567890"), cardIdentifiers = listOf("1111"), overdraftMinor = 100_000)
        val body = "dostupný zůstatek na Vašem účtu 1234567890 k 21.11.2026 00:59 je včetně kontokorentu 18 204,50 Kč."
        val r = IngestionPipeline.process(
            IncomingMessage(Channel.EMAIL, "mail:info@bank.example", "<b1@mail>", 0L, "Zůstatek", body),
            IngestContext(1L, emptyList(), listOf(cs), emptyMap(), emptyList(), null, zone),
        )
        assertTrue(r.inserts.isEmpty())
        assertEquals(1_720_450L, r.balance!!.ownFundsMinor)
    }

    @Test fun `generic bank titles are recognised`() {
        listOf(
            "Odešla platba", "Odeslaná platba", "Přišla platba", "Příchozí platba", "Platba kartou", "Odchozí úhrada",
            "Pohyb na účtu", "Payment to 5550001/9997", "Card payment", "",
        ).forEach { assertTrue(it, GenericNames.isGeneric(it)) }
        listOf("Hospoda U Lipy 2", "Transfer to KB", "padel 3. 12.", "Lidl", "Jan Novák").forEach {
            assertFalse(it, GenericNames.isGeneric(it))
        }
    }

    @Test fun `a George push titled with boilerplate does not keep the boilerplate as its name`() {
        val p = NotificationParser.parse("cz.example.george", "Odešla platba", "400,00 Kč na účet 5550001/9997 pro Jan Novák")!!
        assertNotEquals("Odešla platba", p.merchant)
    }
}
