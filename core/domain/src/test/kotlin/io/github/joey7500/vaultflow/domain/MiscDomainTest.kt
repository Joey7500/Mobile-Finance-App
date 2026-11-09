package io.github.joey7500.vaultflow.domain

import io.github.joey7500.vaultflow.domain.backup.BackupCrypto
import io.github.joey7500.vaultflow.domain.backup.BackupException
import io.github.joey7500.vaultflow.domain.demo.DemoData
import io.github.joey7500.vaultflow.domain.fx.CnbRatesParser
import io.github.joey7500.vaultflow.domain.model.ManualAsset
import io.github.joey7500.vaultflow.domain.model.ManualAssetKind
import io.github.joey7500.vaultflow.domain.wealth.GoldMath
import io.github.joey7500.vaultflow.domain.wealth.NetWorthCalculator
import io.github.joey7500.vaultflow.domain.wealth.Pillar
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiscDomainTest {

    @Test fun `cnb czech feed`() {
        val text = """
            07.11.2026 #216
            země|měna|množství|kód|kurz
            EMU|euro|1|EUR|24,345
            Japonsko|jen|100|JPY|15,120
            USA|dolar|1|USD|21,400
        """.trimIndent()
        val fx = CnbRatesParser.parse(text)
        assertEquals(LocalDate.of(2026, 11, 7), fx.date)
        assertEquals(24.345, fx.rate("EUR")!!, 1e-9)
        assertEquals(0.1512, fx.rate("JPY")!!, 1e-9)
        assertEquals(-26_755L, fx.toCzkMinor(-1_099, "EUR"))
        assertEquals(1.0, fx.rate("CZK")!!, 0.0)
    }

    @Test fun `cnb english feed`() {
        val text = "07 Nov 2026 #216\nCountry|Currency|Amount|Code|Rate\nUSA|dollar|1|USD|21.400\n"
        val fx = CnbRatesParser.parse(text)
        assertEquals(LocalDate.of(2026, 11, 7), fx.date)
        assertEquals(21.4, fx.rate("USD")!!, 1e-9)
    }

    @Test fun `gold value`() {
        val perGram = GoldMath.czkPerGram(usdPerOunce = 3_110.34768, czkPerUsd = 20.0)
        assertEquals(2_000.0, perGram, 1e-6)
        assertEquals(20_000_00L, GoldMath.valueMinor(10.0, perGram))
    }

    @Test fun `net worth pillars and shares`() {
        val nw = NetWorthCalculator.compute(
            trading212Minor = 100_000_00,
            goldMinor = 50_000_00,
            bankBalancesMinor = 50_000_00,
            manualAssets = listOf(ManualAsset(1, "Stavebko", ManualAssetKind.BUILDING_SAVINGS, 200_000_00, 0)),
        )
        assertEquals(400_000_00L, nw.totalMinor)
        assertEquals(0.5, nw.share(Pillar.BUILDING_SAVINGS), 1e-9)
    }

    @Test fun `backup round trip`() {
        val data = "{\"hello\":\"vault\"}".toByteArray()
        val blob = BackupCrypto.encrypt(data, "correct horse battery".toCharArray())
        assertTrue(blob.startsWith("VFB1:"))
        assertArrayEquals(data, BackupCrypto.decrypt(blob, "correct horse battery".toCharArray()))
    }

    @Test fun `backup wrong password fails cleanly`() {
        val blob = BackupCrypto.encrypt("x".toByteArray(), "correct horse battery".toCharArray())
        try {
            BackupCrypto.decrypt(blob, "wrong password!!".toCharArray())
            fail("expected BackupException")
        } catch (e: BackupException) {
            assertNotNull(e.message)
        }
    }

    @Test fun `backup rejects short password`() {
        try {
            BackupCrypto.encrypt("x".toByteArray(), "short".toCharArray())
            fail("expected BackupException")
        } catch (_: BackupException) {
        }
    }

    @Test fun `money formatting`() {
        assertEquals("12 345 Kč", Money.format(12_345_00))
        assertEquals("−1 234,50 Kč", Money.format(-1_234_50, showDecimals = true))
        assertEquals(123_45L, Money.ofUnits(123.45))
    }

    @Test fun `demo data is deterministic and stays in the past`() {
        val today = LocalDate.of(2026, 11, 9)
        val a = DemoData.transactions(today)
        val b = DemoData.transactions(today)
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
        assertTrue(a.all { !it.date.isAfter(today) })
    }
}
