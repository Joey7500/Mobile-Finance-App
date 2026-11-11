package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.text.TextNormalizer

data class Classification(
    /** Resolved category id when a user rule matched. */
    val categoryId: Long?,
    /** Category key when a built-in rule matched (resolved to an id by the caller). */
    val categoryKey: String?,
    val confidence: Double,
    val reason: String,
) {
    val isConfident: Boolean get() = confidence >= Classifier.CONFIDENCE_THRESHOLD && (categoryId != null || categoryKey != null)

    companion object {
        val UNKNOWN = Classification(null, null, 0.0, "no rule matched")
    }
}

/**
 * Three-tier, fully on-device classifier (no transaction ever leaves the phone):
 *
 * 1. Your own accounts  -> Internal transfer (excluded from income/expense).
 * 2. Your learned rules -> the category you chose ("always classify X as ...").
 * 3. Built-in Czech merchant dictionary -> a sensible default.
 *
 * Anything else, or anything below [CONFIDENCE_THRESHOLD], goes to Uncertain.
 */
class Classifier(
    rules: List<ClassificationRule>,
    ownAccountIdentifiers: List<String> = emptyList(),
) {
    private val exactRules = rules.filter { it.match == RuleMatch.EXACT }.associateBy { it.pattern }
    private val containsRules = rules.filter { it.match == RuleMatch.CONTAINS && it.pattern.length >= 3 }
        .sortedByDescending { it.pattern.length }
    private val ownIds = ownAccountIdentifiers
        .map { id -> id.filter(Char::isLetterOrDigit).uppercase() }
        .filter { it.length >= 4 }

    fun classify(merchant: String, rawText: String = "", amountMinor: Long = -1): Classification {
        val compactText = (merchant + " " + rawText).uppercase().filter(Char::isLetterOrDigit)
        if (ownIds.any { compactText.contains(it) }) {
            return Classification(null, CategoryKeys.INTERNAL_TRANSFER, 1.0, "mentions one of your own accounts")
        }

        val key = TextNormalizer.merchantKey(merchant)
        exactRules[key]?.let { return Classification(it.categoryId, null, 1.0, "your rule: \"${it.pattern}\"") }
        containsRules.firstOrNull { key.contains(it.pattern) }?.let {
            return Classification(it.categoryId, null, 1.0, "your rule: \"${it.pattern}\"")
        }

        val haystack = key + " " + TextNormalizer.merchantKey(rawText)
        val builtIn = if (amountMinor >= 0) BuiltInMerchants.matchIncome(haystack) else BuiltInMerchants.matchExpense(haystack)
        if (builtIn != null) {
            return Classification(null, builtIn.second, BuiltInMerchants.CONFIDENCE, "built-in: ${builtIn.first}")
        }
        return Classification.UNKNOWN
    }

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.8

        /**
         * Pattern for an "always classify like this" rule learned from a manual
         * assignment. Tokens containing digits (store numbers, dates, card
         * fragments) are dropped so "ALBERT 0123 BRNO" learns "ALBERT BRNO".
         */
        fun learnedPattern(merchant: String): String? {
            val key = TextNormalizer.merchantKey(merchant)
            // "Payment from 1234567/9999" (e.g. your friend's share of the rent): learn the ACCOUNT.
            ACCOUNT.find(key)?.let { return it.value }
            val tokens = key.split(' ').filter { it.isNotBlank() && it.none(Char::isDigit) }
            if (tokens.all { it in GENERIC_WORDS }) return null // never learn "PAYMENT FROM"
            val pattern = tokens.joinToString(" ").trim()
            return pattern.takeIf { it.length >= 3 }
        }

        private val ACCOUNT = Regex("""(?:\d{1,6}-)?\d{4,10}/\d{4}""")
        private val GENERIC_WORDS = setOf(
            "PAYMENT", "FROM", "TO", "TRANSFER", "PLATBA", "PRICHOZI", "ODCHOZI", "PREVOD", "NA", "Z", "UCET", "UCTU",
            "KARTOU", "CARD", "PAID", "INCOME", "EXPENSE",
        )
    }
}

/** Default merchant dictionary for the Czech market. Order matters: specific before generic. */
object BuiltInMerchants {
    const val CONFIDENCE = 0.9

    private val EXPENSE: List<Pair<String, String>> = listOf(
        // Food delivery and restaurants (before ride-hailing "BOLT").
        "BOLT FOOD" to CategoryKeys.EATING_OUT, "WOLT" to CategoryKeys.EATING_OUT,
        "FOODORA" to CategoryKeys.EATING_OUT, "DAMEJIDLO" to CategoryKeys.EATING_OUT,
        "MCDONALD" to CategoryKeys.EATING_OUT, "KFC" to CategoryKeys.EATING_OUT, "BURGER KING" to CategoryKeys.EATING_OUT,
        "STARBUCKS" to CategoryKeys.EATING_OUT, "COSTA COFFEE" to CategoryKeys.EATING_OUT,
        "RESTAURACE" to CategoryKeys.EATING_OUT, "RESTAURANT" to CategoryKeys.EATING_OUT,
        "KAVARNA" to CategoryKeys.EATING_OUT, "CAFE" to CategoryKeys.EATING_OUT, "BISTRO" to CategoryKeys.EATING_OUT,
        "PIZZ" to CategoryKeys.EATING_OUT, "HOSPODA" to CategoryKeys.EATING_OUT, "PIVNICE" to CategoryKeys.EATING_OUT,

        // Groceries.
        "ALBERT" to CategoryKeys.GROCERIES, "LIDL" to CategoryKeys.GROCERIES, "KAUFLAND" to CategoryKeys.GROCERIES,
        "BILLA" to CategoryKeys.GROCERIES, "PENNY" to CategoryKeys.GROCERIES, "TESCO" to CategoryKeys.GROCERIES,
        "GLOBUS" to CategoryKeys.GROCERIES, "ROHLIK" to CategoryKeys.GROCERIES, "KOSIK" to CategoryKeys.GROCERIES,
        "COOP" to CategoryKeys.GROCERIES, "NORMA" to CategoryKeys.GROCERIES, "ZABKA" to CategoryKeys.GROCERIES,
        "MAKRO" to CategoryKeys.GROCERIES, "RESKO" to CategoryKeys.GROCERIES,

        // Subscriptions and telco.
        "NETFLIX" to CategoryKeys.SUBSCRIPTIONS, "SPOTIFY" to CategoryKeys.SUBSCRIPTIONS,
        "YOUTUBE" to CategoryKeys.SUBSCRIPTIONS, "DISNEY" to CategoryKeys.SUBSCRIPTIONS, "HBO" to CategoryKeys.SUBSCRIPTIONS,
        "MAX.COM" to CategoryKeys.SUBSCRIPTIONS, "APPLE.COM" to CategoryKeys.SUBSCRIPTIONS,
        "GOOGLE ONE" to CategoryKeys.SUBSCRIPTIONS, "ICLOUD" to CategoryKeys.SUBSCRIPTIONS,
        "OPENAI" to CategoryKeys.SUBSCRIPTIONS, "ANTHROPIC" to CategoryKeys.SUBSCRIPTIONS, "CLAUDE.AI" to CategoryKeys.SUBSCRIPTIONS,
        "VODAFONE" to CategoryKeys.SUBSCRIPTIONS, "T-MOBILE" to CategoryKeys.SUBSCRIPTIONS, "O2 CZECH" to CategoryKeys.SUBSCRIPTIONS,

        // Holiday (before generic transport).
        "BOOKING.COM" to CategoryKeys.HOLIDAY, "AIRBNB" to CategoryKeys.HOLIDAY, "RYANAIR" to CategoryKeys.HOLIDAY,
        "WIZZ" to CategoryKeys.HOLIDAY, "SMARTWINGS" to CategoryKeys.HOLIDAY, "EASYJET" to CategoryKeys.HOLIDAY,

        // Travelling / mobility.
        "BOLT" to CategoryKeys.TRAVELLING, "UBER" to CategoryKeys.TRAVELLING, "LIFTAGO" to CategoryKeys.TRAVELLING,
        "SHELL" to CategoryKeys.TRAVELLING, "OMV" to CategoryKeys.TRAVELLING, "MOL" to CategoryKeys.TRAVELLING,
        "BENZINA" to CategoryKeys.TRAVELLING, "ORLEN" to CategoryKeys.TRAVELLING, "EUROOIL" to CategoryKeys.TRAVELLING,
        "CESKE DRAHY" to CategoryKeys.TRAVELLING, "CD.CZ" to CategoryKeys.TRAVELLING, "REGIOJET" to CategoryKeys.TRAVELLING,
        "LEO EXPRESS" to CategoryKeys.TRAVELLING, "FLIXBUS" to CategoryKeys.TRAVELLING, "DPP" to CategoryKeys.TRAVELLING,
        "LITACKA" to CategoryKeys.TRAVELLING, "IDOS" to CategoryKeys.TRAVELLING, "PARKOVANI" to CategoryKeys.TRAVELLING,
        "PARKING" to CategoryKeys.TRAVELLING, "EDALNICE" to CategoryKeys.TRAVELLING,

        // Fun & hobbies.
        "DECATHLON" to CategoryKeys.FUN, "SPORTISIMO" to CategoryKeys.FUN, "STEAM" to CategoryKeys.FUN,
        "PLAYSTATION" to CategoryKeys.FUN, "NINTENDO" to CategoryKeys.FUN, "CINEMA CITY" to CategoryKeys.FUN,
        "KINO" to CategoryKeys.FUN, "ALZA" to CategoryKeys.FUN, "DATART" to CategoryKeys.FUN, "CZC" to CategoryKeys.FUN,

        // Investing.
        "TRADING 212" to CategoryKeys.INVESTMENTS, "TRADING212" to CategoryKeys.INVESTMENTS,
        "XTB" to CategoryKeys.INVESTMENTS, "PORTU" to CategoryKeys.INVESTMENTS, "FONDEE" to CategoryKeys.INVESTMENTS,

        // Other everyday spend.
        "DR.MAX" to CategoryKeys.OTHER_EXPENSES, "DR MAX" to CategoryKeys.OTHER_EXPENSES, "BENU" to CategoryKeys.OTHER_EXPENSES,
        "LEKARNA" to CategoryKeys.OTHER_EXPENSES, "DM DROGERIE" to CategoryKeys.OTHER_EXPENSES, "ROSSMANN" to CategoryKeys.OTHER_EXPENSES,
        "TETA" to CategoryKeys.OTHER_EXPENSES, "IKEA" to CategoryKeys.OTHER_EXPENSES, "HORNBACH" to CategoryKeys.OTHER_EXPENSES,
        "OBI" to CategoryKeys.OTHER_EXPENSES, "PEPCO" to CategoryKeys.OTHER_EXPENSES, "H&M" to CategoryKeys.OTHER_EXPENSES,
        "ZARA" to CategoryKeys.OTHER_EXPENSES,
    )

    private val INCOME: List<Pair<String, String>> = listOf(
        "MZDA" to CategoryKeys.SALARY, "VYPLATA" to CategoryKeys.SALARY, "SALARY" to CategoryKeys.SALARY,
        "PAYROLL" to CategoryKeys.SALARY,
    )

    private val expenseRegex = EXPENSE.map { (k, v) -> Triple(k, v, wordRegex(k)) }
    private val incomeRegex = INCOME.map { (k, v) -> Triple(k, v, wordRegex(k)) }

    /** Returns (matched keyword, category key). */
    fun matchExpense(normalizedHaystack: String): Pair<String, String>? =
        expenseRegex.firstOrNull { it.third.containsMatchIn(normalizedHaystack) }?.let { it.first to it.second }

    fun matchIncome(normalizedHaystack: String): Pair<String, String>? =
        incomeRegex.firstOrNull { it.third.containsMatchIn(normalizedHaystack) }?.let { it.first to it.second }

    // Keyword must start at a word boundary; longer keywords may continue
    // ("PIZZ" -> "PIZZERIA"), 3-letter ones must end at a boundary ("MOL" != "MOLLY").
    private fun wordRegex(keyword: String): Regex {
        val escaped = Regex.escape(keyword)
        val tail = if (keyword.length <= 3) "(?![A-Z])" else ""
        return Regex("(?<![A-Z0-9])$escaped$tail")
    }
}
