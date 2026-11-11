package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {

    @Test fun `built-in grocery merchant`() {
        val c = Classifier(emptyList()).classify("ALBERT HYPERMARKET BRNO")
        assertEquals(CategoryKeys.GROCERIES, c.categoryKey)
        assertTrue(c.isConfident)
    }

    @Test fun `bolt food is eating out but bolt is travelling`() {
        assertEquals(CategoryKeys.EATING_OUT, Classifier(emptyList()).classify("Bolt Food").categoryKey)
        assertEquals(CategoryKeys.TRAVELLING, Classifier(emptyList()).classify("BOLT.EU/O/2311").categoryKey)
    }

    @Test fun `short keyword needs a word boundary`() {
        assertNull(Classifier(emptyList()).classify("MOLLY SHOP").categoryKey)
        assertEquals(CategoryKeys.TRAVELLING, Classifier(emptyList()).classify("MOL 1234 PRAHA").categoryKey)
    }

    @Test fun `diacritics are ignored`() {
        assertEquals(CategoryKeys.EATING_OUT, Classifier(emptyList()).classify("Kavárna Pod Lípou").categoryKey)
    }

    @Test fun `unknown payment processor goes to uncertain`() {
        val c = Classifier(emptyList()).classify("GOPAY *OBCHOD")
        assertFalse(c.isConfident)
    }

    @Test fun `user rule beats built-in`() {
        val rule = ClassificationRule(1, "ALBERT", RuleMatch.CONTAINS, categoryId = 99, createdAtMillis = 0)
        val c = Classifier(listOf(rule)).classify("Albert Hypermarket")
        assertEquals(99L, c.categoryId)
        assertEquals(1.0, c.confidence, 0.0)
    }

    @Test fun `longest contains rule wins`() {
        val rules = listOf(
            ClassificationRule(1, "GOPAY", RuleMatch.CONTAINS, 10, 0),
            ClassificationRule(2, "GOPAY *OBCHOD", RuleMatch.CONTAINS, 20, 0),
        )
        assertEquals(20L, Classifier(rules).classify("GOPAY *OBCHOD").categoryId)
    }

    @Test fun `own account identifier means internal transfer`() {
        val c = Classifier(emptyList(), listOf("998877")).classify("Převod", "Odchozí převod na účet 99-8877 1000 Kč")
        assertEquals(CategoryKeys.INTERNAL_TRANSFER, c.categoryKey)
    }

    @Test fun `salary keyword on inflow`() {
        assertEquals(CategoryKeys.SALARY, Classifier(emptyList()).classify("DEMO S.R.O.", "Mzda 10/2026", 4_500_000).categoryKey)
    }

    @Test fun `learned pattern drops tokens with digits`() {
        assertEquals("ALBERT BRNO", Classifier.learnedPattern("Albert 0123 Brno"))
        assertNull(Classifier.learnedPattern("12345"))
    }

    @Test fun `learning from an account-number merchant learns the account`() {
        assertEquals("1234567/9999", Classifier.learnedPattern("Payment from 1234567/9999"))
        assertNull(Classifier.learnedPattern("Payment from"))
        val rule = ClassificationRule(1, "1234567/9999", RuleMatch.CONTAINS, categoryId = 5, createdAtMillis = 0)
        assertEquals(5L, Classifier(listOf(rule)).classify("Payment from 1234567/9999", amountMinor = 700_000).categoryId)
    }
}
