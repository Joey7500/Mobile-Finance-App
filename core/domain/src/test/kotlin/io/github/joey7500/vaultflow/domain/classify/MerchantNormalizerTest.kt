package io.github.joey7500.vaultflow.domain.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantNormalizerTest {
    private fun c(s: String) = MerchantNormalizer.canonical(s)

    @Test fun `card processor prefixes are removed`() {
        assertEquals("VERYGOODIES", c("NYX*VeryGoodies"))
        assertEquals("KAVARNA U LIPY", c("SumUp *Kavárna U Lípy"))
        assertEquals("KAVARNA U LIPY", c("SUMUP  Kavarna U Lipy"))
        assertEquals("NETFLIX", c("PAYPAL *NETFLIX.COM"))
        assertEquals("BISTRO NA ROHU", c("ZTL*Bistro Na Rohu"))
        assertEquals("BISTRO NA ROHU", c("ZETTLE_Bistro Na Rohu"))
        assertEquals("OBCHOD", c("GOPAY *OBCHOD"))
        assertEquals("FAKE ESHOP", c("COMGATE*FAKE ESHOP"))
        assertEquals("YOUTUBE PREMIUM", c("Google *YouTube Premium"))
        assertEquals("FAKEBAR", c("SQ *FAKEBAR"))
    }

    @Test fun `stacked prefixes are removed too`() {
        assertEquals("FAKE STORE", c("PAYPAL *SUMUP *Fake Store"))
    }

    @Test fun `store and terminal numbers are dropped, short alphanumerics kept`() {
        assertEquals("ALBERT", c("ALBERT 0123"))
        assertEquals("ZABKA", c("ZABKA T4711"))
        assertEquals("O2", c("O2"))
        assertEquals("BOLT", c("BOLT.EU/O/2311"))
    }

    @Test fun `trailing city, country and legal form are dropped`() {
        assertEquals("ALBERT", c("ALBERT 0123 BRNO CZ"))
        assertEquals("LIDL", c("Lidl Česká republika v.o.s."))
        assertEquals("DPP", c("DPP PRAHA 7"))
        assertEquals("FAKE TRADING", c("Fake Trading s.r.o."))
        assertEquals("FAKE TRADING", c("FAKE TRADING SPOL. S R.O."))
        assertEquals("T MOBILE", c("T-Mobile Czech Republic a.s."))
        assertEquals("KAVARNA POD MOSTEM", c("Kavarna Pod Mostem Ceske Budejovice"))
        assertEquals("FAKE GMBH SHOP", c("Fake GmbH Shop Wien AT"))
    }

    @Test fun `diacritics and case do not matter`() {
        assertEquals(c("KAVARNA POD LIPOU"), c("Kavárna pod Lípou"))
        assertEquals(c("ZABKA"), c("žabka"))
    }

    @Test fun `apostrophes and trailing single letters`() {
        assertEquals("MC DONALD", c("McDonald's".replace("Mc", "Mc ")))
        assertEquals("MC DONALD", c("MC DONALD'S 042"))
    }

    @Test fun `web domains lose their www and tld`() {
        assertEquals("ALZA", c("www.alza.cz"))
        assertEquals("FAKESHOP", c("FAKESHOP.EU"))
    }

    @Test fun `a name that is only a city or only noise is never emptied`() {
        assertEquals("PRAHA", c("Praha"))
        assertEquals("BRNO", c("BRNO 0012"))
        assertEquals("12345", c("12345"))
        assertTrue(c("CZ").isNotBlank())
        assertEquals("", c("   "))
    }

    @Test fun `the same shop spelled by different sources has one canonical name`() {
        val spellings = listOf("NYX*Albert 0123 Brno", "ALBERT 0123 BRNO CZ", "Albert", "SUMUP *ALBERT BRNO", "albert s.r.o.")
        assertEquals(setOf("ALBERT"), spellings.map(::c).toSet())
    }
}
