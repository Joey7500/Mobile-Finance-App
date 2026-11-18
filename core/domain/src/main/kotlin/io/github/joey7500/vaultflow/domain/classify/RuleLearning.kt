package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.model.RuleOrigin

/**
 * Learning from your manual corrections.
 *
 * - With "always" ticked: a USER rule, trusted immediately (and it replaces a
 *   learned rule for the same merchant).
 * - Without "always": a LEARNED rule. The first correction only makes the next
 *   payment a *suggestion* to review; once you filed the merchant under the
 *   same category twice it is trusted. Changing your mind restarts the count.
 * - A one-off correction never overrides one of your "always" rules.
 */
object RuleLearning {
    /** A learned rule is trusted from this many consistent corrections on. */
    const val TRUSTED_HITS = 2

    /** Confidence of a learned rule: a suggestion after one correction, trusted from two. */
    fun confidence(hits: Int): Double = when {
        hits <= 1 -> 0.65
        hits == 2 -> 0.85
        hits == 3 -> 0.9
        else -> 0.95
    }

    fun isTrusted(rule: ClassificationRule): Boolean =
        rule.origin == RuleOrigin.USER || rule.hits >= TRUSTED_HITS

    /**
     * The rule to store after you assigned [categoryId] to a payment from
     * [merchant], or null when nothing changes. The returned rule keeps the id
     * of the rule it replaces (0 = new).
     */
    fun onManualAssignment(
        rules: List<ClassificationRule>,
        merchant: String,
        categoryId: Long,
        always: Boolean,
        nowMillis: Long,
    ): ClassificationRule? {
        val pattern = Classifier.learnedPattern(merchant) ?: return null
        val existing = rules.firstOrNull { it.pattern == pattern }
        if (always) {
            if (existing?.origin == RuleOrigin.USER && existing.categoryId == categoryId) return null
            return ClassificationRule(
                id = existing?.id ?: 0, pattern = pattern, match = RuleMatch.CONTAINS, categoryId = categoryId,
                createdAtMillis = existing?.createdAtMillis ?: nowMillis, origin = RuleOrigin.USER,
                hits = maxOf(existing?.hits ?: 1, 1),
            )
        }
        return when {
            // Your explicit rule wins over a one-off correction.
            existing?.origin == RuleOrigin.USER -> null
            existing != null && existing.categoryId == categoryId -> existing.copy(hits = existing.hits + 1)
            existing != null -> existing.copy(categoryId = categoryId, hits = 1, createdAtMillis = nowMillis)
            else -> ClassificationRule(0, pattern, RuleMatch.CONTAINS, categoryId, nowMillis, RuleOrigin.LEARNED, hits = 1)
        }
    }
}
