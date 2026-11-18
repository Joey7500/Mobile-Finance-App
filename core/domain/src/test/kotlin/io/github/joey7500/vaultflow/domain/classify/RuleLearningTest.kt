package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.model.RuleOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleLearningTest {
    private val now = 1_000L

    /** Applies the learning step like the repository does (upsert by pattern). */
    private fun learn(rules: List<ClassificationRule>, merchant: String, cat: Long, always: Boolean = false): List<ClassificationRule> {
        val change = RuleLearning.onManualAssignment(rules, merchant, cat, always, now) ?: return rules
        return rules.filter { it.pattern != change.pattern } + change
    }

    @Test fun `first correction creates a learned rule that only suggests`() {
        val rules = learn(emptyList(), "SUMUP *Fake Bakery 0042", 3)
        val r = rules.single()
        assertEquals("FAKE BAKERY", r.pattern)
        assertEquals(RuleOrigin.LEARNED, r.origin)
        assertEquals(1, r.hits)
        assertFalse(RuleLearning.isTrusted(r))
        assertTrue(Classifier(rules).classify("Fake Bakery", amountMinor = -50_00).isSuggestion)
    }

    @Test fun `second consistent correction makes the rule trusted`() {
        var rules = learn(emptyList(), "Fake Bakery", 3)
        rules = learn(rules, "NYX*Fake Bakery Brno", 3)
        val r = rules.single()
        assertEquals(2, r.hits)
        assertTrue(RuleLearning.isTrusted(r))
        assertTrue(Classifier(rules).classify("FAKE BAKERY 0001 PRAHA", amountMinor = -50_00).isConfident)
    }

    @Test fun `changing your mind restarts the count with the new category`() {
        var rules = learn(emptyList(), "Fake Bakery", 3)
        rules = learn(rules, "Fake Bakery", 3)
        rules = learn(rules, "Fake Bakery", 7)
        val r = rules.single()
        assertEquals(7L, r.categoryId)
        assertEquals(1, r.hits)
        assertFalse(RuleLearning.isTrusted(r))
    }

    @Test fun `always creates a user rule immediately`() {
        val r = learn(emptyList(), "Fake Bakery", 3, always = true).single()
        assertEquals(RuleOrigin.USER, r.origin)
        assertTrue(RuleLearning.isTrusted(r))
    }

    @Test fun `always upgrades a learned rule and keeps its id`() {
        val learned = ClassificationRule(42, "FAKE BAKERY", RuleMatch.CONTAINS, 3, 0, RuleOrigin.LEARNED, 1)
        val change = RuleLearning.onManualAssignment(listOf(learned), "Fake Bakery", 5, always = true, nowMillis = now)
        assertNotNull(change)
        assertEquals(42L, change!!.id)
        assertEquals(RuleOrigin.USER, change.origin)
        assertEquals(5L, change.categoryId)
    }

    @Test fun `a one-off correction never overrides your always rule`() {
        val mine = ClassificationRule(1, "FAKE BAKERY", RuleMatch.CONTAINS, 3, 0, RuleOrigin.USER)
        assertNull(RuleLearning.onManualAssignment(listOf(mine), "Fake Bakery", 9, always = false, nowMillis = now))
    }

    @Test fun `repeating an identical always rule changes nothing`() {
        val mine = ClassificationRule(1, "FAKE BAKERY", RuleMatch.CONTAINS, 3, 0, RuleOrigin.USER)
        assertNull(RuleLearning.onManualAssignment(listOf(mine), "Fake Bakery", 3, always = true, nowMillis = now))
    }

    @Test fun `generic or numeric merchants are never learned`() {
        assertNull(RuleLearning.onManualAssignment(emptyList(), "Payment from", 3, false, now))
        assertNull(RuleLearning.onManualAssignment(emptyList(), "12345", 3, false, now))
        assertNull(RuleLearning.onManualAssignment(emptyList(), "Platba kartou", 3, true, now))
    }

    @Test fun `a counterparty account is learned as the account number`() {
        val r = learn(emptyList(), "Payment from 1234567/9999", 4, always = true).single()
        assertEquals("1234567/9999", r.pattern)
    }
}
