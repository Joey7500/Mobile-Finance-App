package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.classify.ClassificationTier.CONTEXT
import io.github.joey7500.vaultflow.domain.classify.ClassificationTier.DICTIONARY
import io.github.joey7500.vaultflow.domain.classify.ClassificationTier.LEARNED_RULE
import io.github.joey7500.vaultflow.domain.classify.ClassificationTier.NONE
import io.github.joey7500.vaultflow.domain.classify.ClassificationTier.USER_RULE
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.DefaultCategories
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.model.RuleOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Priority, normalisation, dictionary and context rules of the classifier. All merchants are fake or generic. */
class SmartClassifierTest {
    private val none = Classifier(emptyList())
    private fun out(merchant: String, raw: String = "") = none.classify(merchant, raw, -250_00)
    private fun inc(merchant: String, raw: String = "", amount: Long = 500_00) = none.classify(merchant, raw, amount)

    private fun user(id: Long, pattern: String, cat: Long, match: RuleMatch = RuleMatch.CONTAINS) =
        ClassificationRule(id, pattern, match, cat, 0, RuleOrigin.USER)
    private fun learned(id: Long, pattern: String, cat: Long, hits: Int) =
        ClassificationRule(id, pattern, RuleMatch.CONTAINS, cat, 0, RuleOrigin.LEARNED, hits)

    // ── normalised merchants reach the dictionary ──

    @Test fun `processor prefixes and branch suffixes do not hide a known brand`() {
        listOf("NYX*Lidl 0123 Brno", "SUMUP *ALBERT PRAHA 5", "Kaufland Česká republika v.o.s.", "ZABKA T4711 PRAHA").forEach {
            val c = out(it)
            assertEquals(it, CategoryKeys.GROCERIES, c.categoryKey)
            assertTrue(it, c.isConfident)
        }
    }

    @Test fun `paypal netflix is a subscription`() {
        assertEquals(CategoryKeys.SUBSCRIPTIONS, out("PAYPAL *NETFLIX.COM").categoryKey)
    }

    @Test fun `google prefix keeps the service`() {
        assertEquals(CategoryKeys.SUBSCRIPTIONS, out("Google *YouTube Premium").categoryKey)
        assertEquals(CategoryKeys.FUN, out("Google *Google Play").categoryKey)
    }

    @Test fun `brand beats generic keyword`() {
        // "COSTA COFFEE" is a brand (eating out); "HOTEL" alone would be a holiday keyword.
        assertEquals(CategoryKeys.GROCERIES, out("Albert Hotel Lobby").categoryKey)
        assertEquals(DICTIONARY, out("Costa Coffee").tier)
    }

    @Test fun `generic keywords per category`() {
        assertEquals(CategoryKeys.OTHER_EXPENSES, out("Lékárna U Zlatého Hada").categoryKey)
        assertEquals(CategoryKeys.EATING_OUT, out("Restaurace Na Rohu").categoryKey)
        assertEquals(CategoryKeys.TRAVELLING, out("Parkování Centrum").categoryKey)
        assertEquals(CategoryKeys.HOLIDAY, out("Penzion Na Kopci").categoryKey)
        assertEquals(CategoryKeys.FUN, out("Kino Svět").categoryKey)
        assertEquals(CategoryKeys.GROCERIES, out("Potraviny U Nádraží").categoryKey)
        assertTrue(out("Restaurace Na Rohu").isConfident)
    }

    @Test fun `whole-word keywords do not fire inside other words`() {
        assertNull(out("RENTAL CARS FAKE").categoryKey?.takeIf { it == CategoryKeys.RENT })
        assertEquals(CategoryKeys.RENT, out("Platba", "Nájem 11/2026").categoryKey)
        assertNull(out("COOPERATIVA FAKE").categoryKey)
        assertNull(out("BARVY LAKY FAKE").categoryKey)
        assertNull(out("TRANSACTION FAKE").categoryKey)
    }

    @Test fun `weak hints are suggestions, not certain`() {
        val bar = out("Bar U Fakeho")
        assertEquals(CategoryKeys.EATING_OUT, bar.categoryKey)
        assertTrue(bar.isSuggestion)
        assertFalse(bar.isConfident)
        assertTrue(out("AMAZON MKTPLACE").isSuggestion)
    }

    @Test fun `brand only found in the message text counts a bit less`() {
        val c = out("Platba kartou", "Platba kartou 250 Kč u obchodníka LIDL")
        assertEquals(CategoryKeys.GROCERIES, c.categoryKey)
        assertEquals(0.92 - CategoryDictionary.RAW_TEXT_PENALTY, c.confidence, 1e-9)
    }

    @Test fun `short brands are not matched in free message text`() {
        assertNull(out("Fake Merchant", "dm text bez obchodníka").categoryKey)
    }

    // ── priority ──

    @Test fun `user rule beats learned rule beats dictionary`() {
        val rules = listOf(user(1, "ALBERT", 50), learned(2, "ALBERT", 60, hits = 5))
        val c = Classifier(rules).classify("Albert Brno", amountMinor = -100_00)
        assertEquals(50L, c.categoryId)
        assertEquals(USER_RULE, c.tier)
        assertEquals(1.0, c.confidence, 0.0)

        val l = Classifier(listOf(learned(2, "ALBERT", 60, hits = 5))).classify("Albert Brno", amountMinor = -100_00)
        assertEquals(60L, l.categoryId)
        assertEquals(LEARNED_RULE, l.tier)
    }

    @Test fun `exact user rule beats a longer contains rule`() {
        val rules = listOf(user(1, "FAKE SHOP", 10, RuleMatch.EXACT), user(2, "FAKE SHOP EXTRA", 20))
        assertEquals(10L, Classifier(rules).classify("Fake Shop").categoryId)
    }

    @Test fun `old rules with a city still match other spellings of that shop`() {
        // A rule learned by an older version ("ALBERT BRNO") matches "NYX*Albert 0099 Brno CZ".
        val c = Classifier(listOf(user(1, "ALBERT BRNO", 77))).classify("NYX*Albert 0099 Brno CZ", amountMinor = -1)
        assertEquals(77L, c.categoryId)
    }

    @Test fun `learned rule after one correction is only a suggestion`() {
        val c = Classifier(listOf(learned(1, "FAKE BAKERY", 3, hits = 1))).classify("Fake Bakery", amountMinor = -80_00)
        assertEquals(3L, c.categoryId)
        assertTrue(c.isSuggestion)
    }

    @Test fun `learned rule after two consistent corrections is trusted`() {
        val c = Classifier(listOf(learned(1, "FAKE BAKERY", 3, hits = 2))).classify("SUMUP *Fake Bakery", amountMinor = -80_00)
        assertEquals(3L, c.categoryId)
        assertTrue(c.isConfident)
    }

    @Test fun `learned confidence grows with repetition`() {
        assertTrue(RuleLearning.confidence(1) < Classifier.CONFIDENCE_THRESHOLD)
        assertTrue(RuleLearning.confidence(2) >= Classifier.CONFIDENCE_THRESHOLD)
        assertTrue(RuleLearning.confidence(3) > RuleLearning.confidence(2))
        assertTrue(RuleLearning.confidence(9) <= 0.95)
    }

    // ── direction and amount context ──

    @Test fun `income keywords only apply to money in`() {
        assertEquals(CategoryKeys.SALARY, inc("FAKE EMPLOYER S.R.O.", "Mzda 10/2026", 40_000_00).categoryKey)
        assertEquals(CategoryKeys.OTHER_INCOME, inc("Fake Banka", "Připsaný úrok").categoryKey)
        assertEquals(CategoryKeys.GIFTS, inc("Jan Fake", "Dárek k narozeninám").categoryKey)
        assertNull(out("Fake Payroll Services", "").categoryKey?.takeIf { it == CategoryKeys.SALARY })
    }

    @Test fun `plat as a word is salary but platba is not`() {
        assertEquals(CategoryKeys.SALARY, inc("Fake Firma", "plat za říjen", 30_000_00).categoryKey)
        assertNull(inc("Fake Firma", "platba", 300_00).categoryKey)
    }

    @Test fun `money back from a shop is a refund in that shop's category`() {
        val refund = inc("Alza", "Vrácení peněz za objednávku")
        assertEquals(CategoryKeys.FUN, refund.categoryKey)
        assertTrue(refund.isConfident)
        assertEquals(CONTEXT, refund.tier)

        val noWord = inc("Alza")
        assertEquals(CategoryKeys.FUN, noWord.categoryKey)
        assertTrue(noWord.isSuggestion)
    }

    @Test fun `tax refund is income, not a shop refund`() {
        assertEquals(CategoryKeys.OTHER_INCOME, inc("Finanční úřad", "Vratka daně 2025").categoryKey)
    }

    @Test fun `large payment from a company is suggested as salary`() {
        val c = inc("FAKE INDUSTRIES S.R.O.", "Příchozí platba", 42_000_00)
        assertEquals(CategoryKeys.SALARY, c.categoryKey)
        assertTrue(c.isSuggestion)
        assertNull(inc("FAKE INDUSTRIES S.R.O.", "Příchozí platba", 2_000_00).categoryKey)
        assertNull(inc("Jan Fake", "Příchozí platba", 42_000_00).categoryKey)
    }

    @Test fun `cash withdrawal is a suggestion to review`() {
        val c = out("Výběr z bankomatu", "Výběr z bankomatu 2000 Kč")
        assertEquals(CategoryKeys.OTHER_EXPENSES, c.categoryKey)
        assertTrue(c.isSuggestion)
    }

    @Test fun `unknown spending from a cash account is suggested as other`() {
        assertTrue(none.classify("Trh U Fakeho", amountMinor = -100_00, accountType = AccountType.CASH).isSuggestion)
        assertEquals(NONE, none.classify("Trh U Fakeho", amountMinor = -100_00, accountType = AccountType.BANK).tier)
    }

    @Test fun `rule to an income category on money going out is downgraded to a suggestion`() {
        val rules = listOf(user(1, "1234567/9999", 5))
        val kinds = mapOf(5L to FlowKind.INCOME, 6L to FlowKind.EXPENSE)
        val outgoing = Classifier(rules, categoryKinds = kinds).classify("Payment to 1234567/9999", amountMinor = -7_000_00)
        assertTrue(outgoing.isSuggestion)
        val incoming = Classifier(rules, categoryKinds = kinds).classify("Payment from 1234567/9999", amountMinor = 7_000_00)
        assertTrue(incoming.isConfident)
    }

    @Test fun `unknown merchant stays unknown`() {
        val c = out("Xyz Fake Merchant 42")
        assertEquals(NONE, c.tier)
        assertFalse(c.isSuggestion)
        assertFalse(c.isConfident)
    }

    // ── dictionary sanity ──

    @Test fun `every dictionary entry points at a default category`() {
        val keys = DefaultCategories.all.map { it.key }.toSet()
        CategoryDictionary.entries.forEach { assertTrue(it.keyword, it.categoryKey in keys) }
    }

    @Test fun `no keyword is listed twice with different categories`() {
        val conflicts = CategoryDictionary.entries
            .groupBy { it.keyword to it.direction }
            .filterValues { list -> list.map { it.categoryKey }.distinct().size > 1 }
        assertTrue(conflicts.keys.toString(), conflicts.isEmpty())
    }
}
