package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.model.RuleOrigin
import io.github.joey7500.vaultflow.domain.ingest.EmailFirst
import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/** Which step of the classifier produced a result (shown as the reason, used in tests). */
enum class ClassificationTier { OWN_ACCOUNT, USER_RULE, LEARNED_RULE, DICTIONARY, CONTEXT, NONE }

data class Classification(
    /** Resolved category id when a rule matched. */
    val categoryId: Long?,
    /** Category key when a built-in rule matched (resolved to an id by the caller). */
    val categoryKey: String?,
    val confidence: Double,
    val reason: String,
    val tier: ClassificationTier = ClassificationTier.NONE,
) {
    private val hasCategory: Boolean get() = categoryId != null || categoryKey != null

    val isConfident: Boolean get() = confidence >= Classifier.CONFIDENCE_THRESHOLD && hasCategory

    /** A guess worth applying, but marked so you can review it quickly. */
    val isSuggestion: Boolean get() = hasCategory && confidence > 0.0 && !isConfident

    companion object {
        val UNKNOWN = Classification(null, null, 0.0, "no rule matched")
    }
}

/**
 * Fully on-device classifier (no transaction ever leaves the phone). Priority:
 *
 * 1. Your own accounts -> Internal transfer.
 * 2. Your "always" rules (exact before contains, longest pattern first).
 * 3. Rules learned from your corrections (trusted after two, a suggestion after one).
 * 4. Built-in Czech dictionary: brands > keywords > weak hints.
 * 5. Context: refunds, cash withdrawals, a large payment from a company, cash accounts.
 *
 * Merchant names are compared in their raw and canonical form ([MerchantNormalizer]),
 * so "NYX*Albert 0123 Brno" and "ALBERT" are the same shop. The money direction
 * matters: income keywords only apply to money in, and money coming back from a
 * shop is a refund in that shop's category. Results below [CONFIDENCE_THRESHOLD]
 * are suggestions to review; nothing matched = Uncertain.
 */
class Classifier(
    rules: List<ClassificationRule>,
    ownAccountIdentifiers: List<String> = emptyList(),
    /** Kind of each category id, so a rule that contradicts the money direction is only a suggestion. */
    private val categoryKinds: Map<Long, FlowKind> = emptyMap(),
) {
    private class CompiledRule(val rule: ClassificationRule, val canonicalPattern: String)

    private fun compile(list: List<ClassificationRule>): Pair<List<CompiledRule>, List<CompiledRule>> {
        val exact = list.filter { it.match == RuleMatch.EXACT }.map { CompiledRule(it, canonicalPattern(it.pattern)) }
        val contains = list.filter { it.match == RuleMatch.CONTAINS && it.pattern.length >= 3 }
            .sortedByDescending { it.pattern.length }
            .map { CompiledRule(it, canonicalPattern(it.pattern)) }
        return exact to contains
    }

    private val userRules = compile(rules.filter { it.origin == RuleOrigin.USER })
    private val learnedRules = compile(rules.filter { it.origin == RuleOrigin.LEARNED })

    private val ownIds = ownAccountIdentifiers
        .map { id -> id.filter(Char::isLetterOrDigit).uppercase() }
        .filter { it.length >= 4 }

    /**
     * [amountMinor] >= 0 is money in. [accountType] is the account the payment
     * was booked to, when known. [description] is the payment message from the bank
     * e-mail ("padel 3. 12."), [counterpartyAccount] the other party's account.
     *
     * Order: your own accounts, your rules (name, then counterparty account, then message
     * words), keywords in the payment message, learned rules (same order), the built-in
     * dictionary on the name, then context.
     */
    fun classify(
        merchant: String,
        rawText: String = "",
        amountMinor: Long = -1,
        accountType: AccountType? = null,
        description: String = "",
        counterpartyAccount: String? = null,
    ): Classification {
        val compactText = (merchant + " " + rawText).uppercase().filter(Char::isLetterOrDigit)
        if (ownIds.any { compactText.contains(it) }) {
            return Classification(null, CategoryKeys.INTERNAL_TRANSFER, 1.0, "mentions one of your own accounts", ClassificationTier.OWN_ACCOUNT)
        }
        val incoming = amountMinor >= 0
        val key = TextNormalizer.merchantKey(merchant)
        val canon = MerchantNormalizer.canonical(merchant)
        val account = counterpartyAccount?.let(::accountKey)
        val message = TextNormalizer.merchantKey(EmailFirst.messageWords(description))

        findRule(userRules, key, canon, account, message)?.let { r ->
            return fromRule(r, 1.0, "your rule", ClassificationTier.USER_RULE, incoming)
        }
        CategoryDictionary.matchMessage(message)?.let { m ->
            return Classification(null, m.entry.categoryKey, m.confidence, "payment message: ${m.entry.keyword}", ClassificationTier.DICTIONARY)
        }
        findRule(learnedRules, key, canon, account, message)?.let { r ->
            val label = if (RuleLearning.isTrusted(r)) "learned from you (${r.hits}×)" else "you filed it here once"
            return fromRule(r, RuleLearning.confidence(r.hits), label, ClassificationTier.LEARNED_RULE, incoming)
        }

        val merchantTexts = listOfNotNull(canon, key, message.takeIf { it.length >= 3 }).distinct()
        val raw = TextNormalizer.merchantKey(rawText)
        return if (incoming) classifyIncoming(merchantTexts, raw, amountMinor) else classifyOutgoing(merchantTexts, raw, accountType)
    }

    private fun classifyOutgoing(merchantTexts: List<String>, raw: String, accountType: AccountType?): Classification {
        CategoryDictionary.matchOut(merchantTexts, raw)?.let { m ->
            return Classification(null, m.entry.categoryKey, m.confidence, "built-in: ${m.entry.keyword}", ClassificationTier.DICTIONARY)
        }
        val all = merchantTexts + raw
        if (all.any { text -> CASH_WITHDRAWAL.any { it.containsMatchIn(text) } }) {
            return Classification(null, CategoryKeys.OTHER_EXPENSES, 0.6, "cash withdrawal: check what it was for", ClassificationTier.CONTEXT)
        }
        if (accountType == AccountType.CASH) {
            return Classification(null, CategoryKeys.OTHER_EXPENSES, 0.5, "cash payment", ClassificationTier.CONTEXT)
        }
        return Classification.UNKNOWN
    }

    private fun classifyIncoming(merchantTexts: List<String>, raw: String, amountMinor: Long): Classification {
        CategoryDictionary.matchIn(merchantTexts, raw)?.let { m ->
            return Classification(null, m.entry.categoryKey, m.confidence, "built-in: ${m.entry.keyword}", ClassificationTier.DICTIONARY)
        }
        // Money back from a shop = a refund that lowers that category's spend.
        val shop = CategoryDictionary.matchOut(merchantTexts, raw)
        val refund = CategoryDictionary.isRefund(merchantTexts, raw)
        if (shop != null) {
            val confidence = if (refund) CategoryDictionary.Strength.KEYWORD.confidence else CategoryDictionary.Strength.WEAK.confidence
            return Classification(null, shop.entry.categoryKey, confidence, "refund from ${shop.entry.keyword}", ClassificationTier.CONTEXT)
        }
        if (amountMinor >= LARGE_INCOME_MINOR && merchantTexts.any { text -> COMPANY.containsMatchIn(text) } ) {
            return Classification(null, CategoryKeys.SALARY, 0.6, "large payment from a company: salary?", ClassificationTier.CONTEXT)
        }
        return Classification.UNKNOWN
    }

    private fun findRule(
        rules: Pair<List<CompiledRule>, List<CompiledRule>>,
        key: String,
        canon: String,
        account: String? = null,
        message: String = "",
    ): ClassificationRule? {
        val (exact, contains) = rules
        exact.firstOrNull { it.rule.pattern == key || it.rule.pattern == canon || it.canonicalPattern == canon }?.let { return it.rule }
        contains.firstOrNull { c ->
            key.contains(c.rule.pattern) || canon.contains(c.rule.pattern) ||
                (c.canonicalPattern.length >= 3 && canon.contains(c.canonicalPattern))
        }?.let { return it.rule }
        // A rule on a counterparty ACCOUNT ("Payment from 1234567/9999", e.g. a friend's rent share)
        // still applies once the e-mail names that payment by its message or the person.
        if (account != null) {
            (exact + contains).firstOrNull { ACCOUNT.matches(it.rule.pattern) && accountKey(it.rule.pattern) == account }?.let { return it.rule }
        }
        // Words of the payment message ("padel" in "padel 3. 12."), as whole words.
        if (message.isNotBlank()) {
            (exact + contains).firstOrNull { c ->
                val p = c.canonicalPattern
                p.length >= 4 && !ACCOUNT.containsMatchIn(p) &&
                    Regex("(?<![A-Z0-9])" + Regex.escape(p) + "(?![A-Z0-9])").containsMatchIn(message)
            }?.let { return it.rule }
        }
        return null
    }

    private fun fromRule(r: ClassificationRule, confidence: Double, label: String, tier: ClassificationTier, incoming: Boolean): Classification {
        // An income category on money going out is almost certainly not what the rule meant.
        val contradicts = !incoming && categoryKinds[r.categoryId] == FlowKind.INCOME
        return if (contradicts) {
            Classification(r.categoryId, null, minOf(confidence, 0.6), "$label: \"${r.pattern}\" (but money went out)", tier)
        } else {
            Classification(r.categoryId, null, confidence, "$label: \"${r.pattern}\"", tier)
        }
    }

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.8

        /** Incoming payments of at least 15 000 Kč from a company may be a salary. */
        const val LARGE_INCOME_MINOR = 15_000_00L

        private val CASH_WITHDRAWAL = listOf("VYBER Z BANKOMATU", "VYBER HOTOVOSTI", "BANKOMAT", "ATM", "CASH WITHDRAWAL", "VYBER")
            .map { Regex("(?<![A-Z0-9])" + Regex.escape(it) + "(?![A-Z0-9])") }

        private val COMPANY = Regex("(?<![A-Z0-9])(?:S ?R ?O|A ?S|SPOL|GMBH|LTD|INC|LLC)(?![A-Z0-9])|S\\.R\\.O|A\\.S\\.")

        /**
         * Pattern for a rule learned from a manual assignment: the canonical
         * merchant ("NYX*Albert 0123 Brno" learns "ALBERT"), without tokens that
         * contain digits. A counterparty ACCOUNT ("Payment from 1234567/9999",
         * e.g. a friend's share of the rent) is learned as the account number.
         */
        fun learnedPattern(merchant: String): String? {
            val key = TextNormalizer.merchantKey(merchant)
            ACCOUNT.find(key)?.let { return it.value }
            val tokens = MerchantNormalizer.canonical(merchant).split(' ').filter { it.isNotBlank() && it.none(Char::isDigit) }
            if (tokens.all { it in GENERIC_WORDS }) return null // never learn "PAYMENT FROM"
            val pattern = tokens.joinToString(" ").trim()
            return pattern.takeIf { it.length >= 3 }
        }

        /** Account-number patterns stay as they are; others are compared in canonical form too. */
        private fun canonicalPattern(pattern: String): String =
            if (ACCOUNT.containsMatchIn(pattern)) pattern else MerchantNormalizer.canonical(pattern)

        private val ACCOUNT = Regex("""(?:\d{1,6}-)?\d{4,10}/\d{4}""")

        /** "19-0001234567/9999" and "1234567/9999" are one account: main number without zeros + bank code. */
        fun accountKey(account: String): String {
            val main = account.substringBefore('/').substringAfter('-').filter(Char::isDigit).trimStart('0')
            val bank = account.substringAfter('/', "").filter(Char::isDigit)
            return if (bank.isEmpty()) main else "$main/$bank"
        }
        private val GENERIC_WORDS = setOf(
            "PAYMENT", "FROM", "TO", "TRANSFER", "PLATBA", "PRICHOZI", "ODCHOZI", "PREVOD", "NA", "Z", "UCET", "UCTU",
            "KARTOU", "CARD", "PAID", "INCOME", "EXPENSE",
        )
    }
}
