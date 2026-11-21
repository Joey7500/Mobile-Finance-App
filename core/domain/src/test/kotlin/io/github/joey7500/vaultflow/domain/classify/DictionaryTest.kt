package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.ingest.GenericNames
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.CLOTHING
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.DRUGSTORE
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.EATING_OUT
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.FUN
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.GIFTS
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.GROCERIES
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.HOLIDAY
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.INVESTMENTS
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.OTHER_EXPENSES
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.RENT
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.SALARY
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.SUBSCRIPTIONS
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.TRAVELLING
import io.github.joey7500.vaultflow.domain.model.DefaultCategories
import io.github.joey7500.vaultflow.domain.text.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in dictionary (data files in resources/.../classify/dictionary): integrity, realistic
 * card-terminal names in Czechia, France, Germany and online, traps, and payments abroad.
 * Shop names are public chains; no personal data.
 */
class DictionaryTest {
    private val c = Classifier(emptyList())

    /** Category the dictionary itself gives (by type of shop), ignoring the abroad rule. */
    private fun byType(merchant: String): String? {
        val texts = listOf(MerchantNormalizer.canonical(merchant), TextNormalizer.merchantKey(merchant)).distinct()
        return CategoryDictionary.matchOut(texts, "")?.entry?.categoryKey
    }

    private fun out(merchant: String, currency: String = "CZK") = c.classify(merchant, amountMinor = -10_000, currency = currency)

    // ───────────── integrity ─────────────

    @Test fun `the data files load and are big`() {
        assertTrue(CategoryDictionary.entries.size.toString(), CategoryDictionary.entries.size >= 1_700)
        val files = CategoryDictionary.entries.groupBy { it.source }
        listOf("shops_cz.tsv", "shops_fr.tsv", "shops_de.tsv", "shops_intl.tsv", "words.tsv", "income.tsv", "messages.tsv")
            .forEach { assertTrue(it, (files[it]?.size ?: 0) > 30) }
    }

    @Test fun `every entry points at a default category, new ones included`() {
        val keys = DefaultCategories.all.map { it.key }.toSet()
        assertTrue(CLOTHING in keys && DRUGSTORE in keys)
        CategoryDictionary.entries.forEach { assertTrue(it.keyword, it.categoryKey in keys) }
    }

    @Test fun `no name is listed in two categories within one list`() {
        val conflicts = CategoryDictionary.entries
            .groupBy { Triple(it.keyword, it.kind, it.direction) }
            .filterValues { list -> list.map { it.categoryKey }.distinct().size > 1 }
        assertTrue(conflicts.keys.toString(), conflicts.isEmpty())
    }

    @Test fun `no shop name is bank boilerplate, flags are consistent`() {
        CategoryDictionary.entries.filter { it.kind == CategoryDictionary.Kind.SHOP }.forEach { e ->
            assertFalse("generic: ${e.keyword}", GenericNames.isGeneric(e.keyword))
            assertFalse("abroad and online: ${e.keyword}", e.abroad && e.online)
            if (e.personName) assertTrue("first names must be whole words: ${e.keyword}", e.wholeWord)
        }
    }

    @Test fun `ordinary bank e-mail and notification text never names a shop`() {
        val texts = listOf(
            "Dobrý den, pane Nováku, potvrzujeme platbu kartou 1111 ve výši 214,00 Kč ze dne 20. 11. 2026 u XYZ 0815. " +
                "Na účtu 1234567890 je k dispozici 18 204,50 Kč (včetně kontokorentu 1 000,00 Kč). Česká spořitelna " +
                "Bezpečí Vašich peněz je pro nás na prvním místě. Sledujte náš web!",
            "z účtu 1234567890/9999 odešla platba ve výši 400,00 Kč. Na účtu zůstává k dispozici 19 880,40 Kč. " +
                "Vaše Česká spořitelna Informace o transakci Datum a čas platby: 20. 11. 2026 10:12 Směr platby: odchozí " +
                "Z účtu: 1234567890/9999 Na účet: 5550001/9997 Částka v měně transakce: 400,00 Kč Konstantní symbol: 0",
            "dostupný zůstatek na Vašem účtu 1234567890 k 21.11.2026 00:59 je včetně kontokorentu 18 204,50 Kč.",
            "Na účet 7654321/9998 byla připsána platba 10,00 Kč z účtu 1234567890/9999.",
            "180,00 Kč kartou Visa ••1111", "Zaplaceno 12,50 € Apple Pay", "72,90 CZK paid by card 1111.",
        )
        texts.forEach { raw ->
            val r = c.classify("XYZ 0815", raw, amountMinor = -10_000)
            assertNull("matched in: $raw -> ${r.reason}", r.categoryKey)
        }
    }

    // ───────────── realistic terminal names, by type of shop ─────────────

    private val czech = mapOf(
        "ALBERT 0123 BRNO" to GROCERIES, "Albert Hypermarket Praha" to GROCERIES, "LIDL DEKUJE ZA NAKUP" to GROCERIES,
        "KAUFLAND DECIN" to GROCERIES, "BILLA ATRIUM FLORA" to GROCERIES, "PENNY MARKET 1234" to GROCERIES,
        "TESCO EXPRESS PRAHA 2" to GROCERIES, "GLOBUS BRNO" to GROCERIES, "ZABKA 12345" to GROCERIES, "Žabka" to GROCERIES,
        "ROHLIK.CZ" to GROCERIES, "KOSIK.CZ" to GROCERIES, "COOP DISKONT" to GROCERIES, "MAKRO CASH & CARRY" to GROCERIES,
        "PEKARNA KABAT" to GROCERIES, "PEKARSTVI U MOSTU" to GROCERIES, "VECERKA NONSTOP" to GROCERIES,
        "REZNICTVI A UZENARSTVI" to GROCERIES, "VINOTEKA U SUDU" to GROCERIES, "KNUSPR" to GROCERIES,
        "MCDONALDS 123" to EATING_OUT, "KFC BRNO" to EATING_OUT, "BURGER KING" to EATING_OUT, "STARBUCKS" to EATING_OUT,
        "COSTA COFFEE" to EATING_OUT, "BAGETERIE BOULEVARD" to EATING_OUT, "UGO FRESH BAR" to EATING_OUT,
        "RESTAURACE U ZLATEHO TYGRA" to EATING_OUT, "HOSPODA NA ROHU" to EATING_OUT, "PIVOVAR U TRI RUZI" to EATING_OUT,
        "KAVARNA SLAVIA" to EATING_OUT, "CUKRARNA SAFRAN" to EATING_OUT, "PIZZERIE LA STRADA" to EATING_OUT,
        "SKOLNI JIDELNA" to EATING_OUT, "MENZA KOLEJ" to EATING_OUT, "WOLT" to EATING_OUT, "FOODORA" to EATING_OUT,
        "BOLT FOOD" to EATING_OUT, "DAMEJIDLO.CZ" to EATING_OUT, "PHO VIET" to EATING_OUT, "KEBAB HOUSE" to EATING_OUT,
        "IKEA RESTAURACE" to EATING_OUT, "OHNIVY KURE" to EATING_OUT, "BISTRO 4" to EATING_OUT, "CAFE AU LAIT" to EATING_OUT,
        "BOLT.EU" to TRAVELLING, "UBER *TRIP" to TRAVELLING, "LIFTAGO" to TRAVELLING, "SHELL 4021" to TRAVELLING,
        "OMV 2345 BRNO" to TRAVELLING, "MOL CERPACI STANICE" to TRAVELLING, "ORLEN BENZINA" to TRAVELLING,
        "EUROOIL" to TRAVELLING, "TANK ONO" to TRAVELLING, "CESKE DRAHY" to TRAVELLING, "CD.CZ" to TRAVELLING,
        "REGIOJET" to TRAVELLING, "LEO EXPRESS" to TRAVELLING, "FLIXBUS" to TRAVELLING, "DPMB" to TRAVELLING,
        "DPP PRAHA" to TRAVELLING, "LITACKA" to TRAVELLING, "IDS JMK" to TRAVELLING, "EDALNICE.CZ" to TRAVELLING,
        "PARKOVANI BRNO" to TRAVELLING, "AUTOMYCKA WASH" to TRAVELLING, "REKOLA" to TRAVELLING, "NEXTBIKE" to TRAVELLING,
        "AUTOSERVIS NOVAK" to TRAVELLING, "VIANOR" to TRAVELLING, "IONITY" to TRAVELLING,
        "DECATHLON" to FUN, "SPORTISIMO" to FUN, "INTERSPORT OLOMOUC" to FUN, "A3 SPORT" to FUN,
        "CINEMA CITY" to FUN, "CINESTAR" to FUN, "KINO SVETOZOR" to FUN, "NARODNI DIVADLO" to FUN, "ALZA.CZ" to FUN,
        "DATART" to FUN, "CZC.CZ" to FUN, "GOOUT" to FUN, "TICKETPORTAL" to FUN, "KNIHY DOBROVSKY" to FUN,
        "LUXOR" to FUN, "FITNESS 24" to FUN, "SQUASH CENTRUM" to FUN, "PADEL ARENA" to FUN, "BOULDERBAR" to FUN,
        "AQUAPARK ZLIN" to FUN, "SAUNA CLUB" to FUN, "BOWLING BAR" to FUN, "SKIAREAL SPINDLERUV MLYN" to FUN,
        "SAZKA" to FUN, "TIPSPORT" to FUN, "LEGO STORE" to FUN, "ZOO PRAHA" to FUN,
        "VODAFONE CZ" to SUBSCRIPTIONS, "T-MOBILE CZECH" to SUBSCRIPTIONS, "O2 CZECH REPUBLIC" to SUBSCRIPTIONS,
        "VOYO" to SUBSCRIPTIONS, "ONEPLAY" to SUBSCRIPTIONS, "SKYLINK" to SUBSCRIPTIONS,
        "CEZ PRODEJ" to RENT, "E.ON ENERGIE" to RENT, "PRAZSKA ENERGETIKA" to RENT, "INNOGY" to RENT,
        "BRNENSKE VODARNY" to RENT, "SVJ DOMOV" to RENT,
        "DM DROGERIE MARKT" to DRUGSTORE, "ROSSMANN" to DRUGSTORE, "TETA DROGERIE" to DRUGSTORE, "DR.MAX" to DRUGSTORE,
        "LEKARNA BENU" to DRUGSTORE, "Lékárna U Zlatého Hada" to DRUGSTORE, "NOTINO.CZ" to DRUGSTORE,
        "SEPHORA" to DRUGSTORE, "DOUGLAS" to DRUGSTORE, "PILULKA.CZ" to DRUGSTORE, "MANUFAKTURA" to DRUGSTORE,
        "H&M" to CLOTHING, "ZARA" to CLOTHING, "RESERVED" to CLOTHING, "C&A" to CLOTHING, "PRIMARK" to CLOTHING,
        "DEICHMANN" to CLOTHING, "CCC OBUV" to CLOTHING, "BATA" to CLOTHING, "NEW YORKER" to CLOTHING,
        "LINDEX" to CLOTHING, "TAKKO FASHION" to CLOTHING, "SINSAY" to CLOTHING, "BERSHKA" to CLOTHING,
        "UNIQLO" to CLOTHING, "HUMANIC" to CLOTHING, "TK MAXX" to CLOTHING, "ALPINE PRO" to CLOTHING,
        "TENISKY.CZ" to CLOTHING, "ZOOT.CZ" to CLOTHING,
        "IKEA" to OTHER_EXPENSES, "HORNBACH" to OTHER_EXPENSES, "OBI" to OTHER_EXPENSES, "JYSK" to OTHER_EXPENSES,
        "CESKA POSTA" to OTHER_EXPENSES, "ZASILKOVNA" to OTHER_EXPENSES, "KADERNICTVI EVA" to OTHER_EXPENSES,
        "BARBERSHOP" to OTHER_EXPENSES, "ZUBNI ORDINACE" to OTHER_EXPENSES, "KOOPERATIVA" to OTHER_EXPENSES,
        "KVETINARSTVI" to OTHER_EXPENSES, "ZVERIMEX" to OTHER_EXPENSES,
        "TRADING 212" to INVESTMENTS, "XTB" to INVESTMENTS, "ANYCOIN" to INVESTMENTS, "MODRA PYRAMIDA" to INVESTMENTS,
        "INVIA.CZ" to HOLIDAY, "CEDOK" to HOLIDAY, "SMARTWINGS" to HOLIDAY, "HOTEL PUPP" to HOLIDAY,
        "PENZION NA KOPCI" to HOLIDAY, "KEMP ZDAR" to HOLIDAY,
    )

    private val france = mapOf(
        "E.LECLERC" to GROCERIES, "CARREFOUR CITY" to GROCERIES, "CARREFOUR MARKET 1234" to GROCERIES,
        "AUCHAN SUPERMARCHE" to GROCERIES, "INTERMARCHE SUPER" to GROCERIES, "ITM ALIMENTAIRE" to GROCERIES,
        "SUPER U" to GROCERIES, "MONOPRIX" to GROCERIES, "FRANPRIX" to GROCERIES, "GEANT CASINO" to GROCERIES,
        "PICARD SURGELES" to GROCERIES, "BIOCOOP" to GROCERIES, "BOULANGERIE DUPONT" to GROCERIES,
        "BOUCHERIE MARTIN" to GROCERIES, "FROMAGERIE DU MARCHE" to GROCERIES, "EPICERIE FINE" to GROCERIES,
        "BRIOCHE DOREE" to EATING_OUT, "PAUL" to EATING_OUT, "BRASSERIE DU PORT" to EATING_OUT, "CREPERIE BRETONNE" to EATING_OUT,
        "FLUNCH" to EATING_OUT, "BUFFALO GRILL" to EATING_OUT, "COURTEPAILLE" to EATING_OUT, "AUTOGRILL" to EATING_OUT,
        "SNCF INTERNET" to TRAVELLING, "OUIGO" to TRAVELLING, "RATP" to TRAVELLING, "NAVIGO" to TRAVELLING,
        "VINCI AUTOROUTES" to TRAVELLING, "AUTOROUTES DU SUD" to TRAVELLING, "SANEF" to TRAVELLING, "APRR" to TRAVELLING,
        "PEAGE AUTOROUTE" to TRAVELLING, "TOTALENERGIES" to TRAVELLING, "ESSO" to TRAVELLING,
        "STATIONNEMENT PARIS" to TRAVELLING, "INDIGO" to TRAVELLING,
        "FNAC" to FUN, "DARTY" to FUN, "BOULANGER" to FUN, "DISNEYLAND PARIS" to FUN, "MUSEE DU LOUVRE" to FUN,
        "UGC CINE CITE" to FUN, "PATHE" to FUN, "ESF" to FUN, "REMONTEES MECANIQUES" to FUN,
        "IBIS BUDGET" to HOLIDAY, "NOVOTEL" to HOLIDAY, "AIR FRANCE" to HOLIDAY, "CAMPING LES PINS" to HOLIDAY,
        "PHARMACIE CENTRALE" to DRUGSTORE, "PARAPHARMACIE" to DRUGSTORE, "NOCIBE" to DRUGSTORE,
        "KIABI" to CLOTHING, "CELIO" to CLOTHING, "GALERIES LAFAYETTE" to CLOTHING,
        "LEROY MERLIN" to OTHER_EXPENSES, "CASTORAMA" to OTHER_EXPENSES, "TABAC DU CENTRE" to OTHER_EXPENSES,
        "LA POSTE" to OTHER_EXPENSES,
    )

    private val germany = mapOf(
        "REWE MARKT" to GROCERIES, "EDEKA" to GROCERIES, "ALDI SUED" to GROCERIES, "NETTO MARKEN-DISCOUNT" to GROCERIES,
        "HOFER" to GROCERIES, "SPAR" to GROCERIES, "BACKEREI SCHMIDT" to GROCERIES, "BAECKEREI MUELLER" to GROCERIES,
        "METZGEREI HUBER" to GROCERIES, "NORDSEE" to EATING_OUT, "BACKWERK" to EATING_OUT, "GASTHAUS ZUR POST" to EATING_OUT,
        "BIERGARTEN" to EATING_OUT, "DB VERTRIEB GMBH" to TRAVELLING, "DEUTSCHE BAHN" to TRAVELLING, "OEBB" to TRAVELLING,
        "ARAL" to TRAVELLING, "MVG" to TRAVELLING, "ASFINAG" to TRAVELLING, "TANKSTELLE" to TRAVELLING,
        "PARKHAUS ZENTRUM" to TRAVELLING, "MEDIAMARKT" to FUN, "SATURN" to FUN, "THALIA" to FUN, "SCHWIMMBAD" to FUN,
        "BERGBAHN" to FUN, "MOTEL ONE" to HOLIDAY, "APOTHEKE AM MARKT" to DRUGSTORE, "ROSSMANN" to DRUGSTORE,
        "MUELLER" to DRUGSTORE, "BIPA" to DRUGSTORE, "PEEK & CLOPPENBURG" to CLOTHING, "FRESSNAPF" to OTHER_EXPENSES,
    )

    private val online = mapOf(
        "NETFLIX.COM" to SUBSCRIPTIONS, "SPOTIFY P1234" to SUBSCRIPTIONS, "APPLE.COM/BILL" to SUBSCRIPTIONS,
        "GOOGLE *YOUTUBE PREMIUM" to SUBSCRIPTIONS, "OPENAI *CHATGPT" to SUBSCRIPTIONS, "DISNEY PLUS" to SUBSCRIPTIONS,
        "MICROSOFT*365" to SUBSCRIPTIONS, "ADOBE" to SUBSCRIPTIONS, "STRAVA" to SUBSCRIPTIONS, "PATREON" to SUBSCRIPTIONS,
        "STEAMGAMES.COM" to FUN, "PLAYSTATION NETWORK" to FUN, "GOOGLE PLAY" to FUN, "NINTENDO" to FUN,
        "TEMU.COM" to OTHER_EXPENSES, "ALIEXPRESS" to OTHER_EXPENSES, "ZALANDO" to CLOTHING, "ABOUT YOU" to CLOTHING,
        "SHEIN" to CLOTHING, "VINTED" to CLOTHING, "BOOKING.COM" to HOLIDAY, "AIRBNB" to HOLIDAY, "RYANAIR" to HOLIDAY,
        "WIZZ AIR" to HOLIDAY, "EASYJET" to HOLIDAY, "UBER EATS" to EATING_OUT, "DELIVEROO" to EATING_OUT,
        "FIVE GUYS" to EATING_OUT, "SIXT" to TRAVELLING, "EUROPCAR" to TRAVELLING, "BLABLACAR" to TRAVELLING,
    )

    private fun check(table: Map<String, String>) {
        val wrong = table.mapNotNull { (merchant, expected) ->
            val got = byType(merchant)
            if (got == expected) null else "$merchant: expected $expected, got $got"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun `czech shops land in their category`() = check(czech)

    @Test fun `french shops land in their category`() = check(france)

    @Test fun `german and austrian shops land in their category`() = check(germany)

    @Test fun `online services land in their category`() = check(online)

    @Test fun `czech shops paid at home are never holiday`() {
        val wrong = czech.filterValues { it != HOLIDAY }.keys.filter { out(it).categoryKey == HOLIDAY }
        assertTrue(wrong.toString(), wrong.isEmpty())
    }

    // ───────────── traps ─────────────

    @Test fun `words inside other words do not match`() {
        assertNotEquals(EATING_OUT, byType("BARVY LAKY"))
        assertNotEquals(GROCERIES, byType("SPARKASSE"))
        assertNotEquals(INVESTMENTS, byType("LISBOA PORTUGAL"))
        assertNotEquals(FUN, byType("BOULANGERIE DU COIN"))
        assertNotEquals(GROCERIES, byType("COOPER VISION"))
        assertNotEquals(GROCERIES, byType("CORAL TRAVEL"))
        assertEquals(GROCERIES, byType("COOP"))
        assertEquals(CLOTHING, byType("TENISKY NIKE"))
        assertEquals(FUN, byType("TENIS KLUB"))
    }

    @Test fun `a first name in a transfer to a person is not the shop`() {
        val toFriend = c.classify("Albert Novák", amountMinor = -50_000, counterpartyAccount = "5550001/9997")
        assertNotEquals(GROCERIES, toFriend.categoryKey)
        assertNotEquals(EATING_OUT, c.classify("Paul Martin", amountMinor = -20_000, counterpartyAccount = "5550001/9997").categoryKey)
        // The same name on a card payment is the shop.
        assertEquals(GROCERIES, out("ALBERT 0123 BRNO").categoryKey)
        assertEquals(EATING_OUT, out("PAUL PRAHA").categoryKey)
    }

    // ───────────── abroad -> Holiday ─────────────

    @Test fun `a shop that exists only abroad is holiday even in czech crowns`() {
        listOf("E.LECLERC", "CARREFOUR CITY 1234", "SNCF INTERNET", "VINCI AUTOROUTES", "REWE MARKT", "BOULANGERIE DUPONT",
            "PHARMACIE CENTRALE", "PEAGE A6", "DB VERTRIEB GMBH", "APOTHEKE AM MARKT", "FNAC").forEach { m ->
            val r = out(m)
            assertEquals(m, HOLIDAY, r.categoryKey)
            assertTrue(m, r.isConfident)
            assertTrue(r.reason, r.reason.startsWith("paid abroad"))
        }
    }

    @Test fun `a known shop paid in euros is abroad`() {
        listOf("LIDL", "RESTAURANT LE PETIT ZINC", "MCDONALDS", "DECATHLON", "IBIS BUDGET", "SHELL").forEach { m ->
            val r = out(m, "EUR")
            assertEquals(m, HOLIDAY, r.categoryKey)
            assertTrue(m, r.isConfident)
        }
        // At home the same shops keep their type.
        assertEquals(GROCERIES, out("LIDL").categoryKey)
        assertEquals(EATING_OUT, out("RESTAURANT LE PETIT ZINC").categoryKey)
    }

    @Test fun `an unknown shop with one hint is only a holiday suggestion`() {
        val r = out("LE PETIT ZINC", "EUR")
        assertEquals(HOLIDAY, r.categoryKey)
        assertTrue(r.isSuggestion)
        val city = out("CHEZ MARCEL LYON")
        assertEquals(HOLIDAY, city.categoryKey)
        assertTrue(city.isSuggestion)
        // Two hints: town + country code.
        assertTrue(out("CHEZ MARCEL LYON FR").isConfident)
        assertNull(out("LE PETIT ZINC").categoryKey)
    }

    @Test fun `online services paid in euros are not holiday`() {
        mapOf(
            "NETFLIX.COM NL" to SUBSCRIPTIONS, "SPOTIFY SE" to SUBSCRIPTIONS, "STEAMGAMES.COM" to FUN,
            "TRADING 212" to INVESTMENTS, "TEMU.COM" to OTHER_EXPENSES, "ZALANDO SE" to CLOTHING,
        ).forEach { (m, cat) -> assertEquals(m, cat, out(m, "EUR").categoryKey) }
        assertNotEquals(HOLIDAY, out("AMZN MKTP DE", "EUR").categoryKey)
    }

    @Test fun `a czech town or legal form keeps a payment at home`() {
        assertEquals(EATING_OUT, out("CAFE LOUVRE PRAHA").categoryKey)
        assertEquals(HOLIDAY, out("HOTEL PARIS PRAHA").categoryKey) // a hotel, not abroad
        assertFalse(out("HOTEL PARIS PRAHA").reason.startsWith("paid abroad"))
        assertNull(out("NYX*FAKEFOODSRO").categoryKey)
    }

    @Test fun `a transfer to someone's account is never treated as abroad`() {
        val r = c.classify("Jean Dupont", amountMinor = -50_000, counterpartyAccount = "FR7630006000011234567890189", currency = "EUR")
        assertNotEquals(HOLIDAY, r.categoryKey)
    }

    // ───────────── payment messages and income in four languages ─────────────

    @Test fun `payment messages in czech, english, french and german`() {
        mapOf(
            "padel" to FUN, "fotbal hala" to FUN, "Fußball Training" to FUN, "escalade" to FUN, "ski" to FUN,
            "obědy" to EATING_OUT, "lunch" to EATING_OUT, "déjeuner" to EATING_OUT, "Mittagessen" to EATING_OUT,
            "nájem listopad" to RENT, "loyer décembre" to RENT, "Miete November" to RENT, "rent" to RENT,
            "benzín" to TRAVELLING, "péage" to TRAVELLING, "Tanken" to TRAVELLING,
            "dovolená Chorvatsko" to HOLIDAY, "vacances" to HOLIDAY, "Urlaub" to HOLIDAY,
            "léky" to DRUGSTORE, "boty" to CLOTHING, "Kleidung" to CLOTHING, "nákup" to GROCERIES,
        ).forEach { (msg, cat) -> assertEquals(msg, cat, c.classify("Jan Novák", description = msg, counterpartyAccount = "5550001/9997").categoryKey) }
    }

    @Test fun `income words in four languages`() {
        assertEquals(SALARY, c.classify("Firma s.r.o.", "Mzda 11/2026", amountMinor = 4_500_000).categoryKey)
        assertEquals(SALARY, c.classify("ACME GMBH", "Gehalt November", amountMinor = 4_500_000).categoryKey)
        assertEquals(SALARY, c.classify("ACME SARL", "Salaire novembre", amountMinor = 4_500_000).categoryKey)
        assertEquals(GIFTS, c.classify("Jan Novák", "Geschenk", amountMinor = 100_000).categoryKey)
        assertEquals(GIFTS, c.classify("Jan Novák", "dárek k narozeninám", amountMinor = 100_000).categoryKey)
    }
}
