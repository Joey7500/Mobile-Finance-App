package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/**
 * Built-in knowledge of shops, companies and words: Czech, French, German and
 * international brands, plus general words in Czech, English, French and German.
 * Used only when none of your own or learned rules matched.
 *
 * The entries live in plain text files next to this class
 * (`resources/.../classify/dictionary/`, format described at the top of each file),
 * so adding a shop is a one-line change:
 * - BRAND: a specific chain or service ("LIDL", "E.LECLERC"). Confident.
 * - KEYWORD: a word that names the kind of shop ("LEKARNA", "BOULANGERIE"). Confident.
 * - WEAK: a hint that is often right but should be checked ("BAR", "AMAZON"). Suggestion only.
 *
 * Flags: `whole` (whole word only), `abroad` (exists only abroad: a payment there was
 * made abroad), `online` (paid from anywhere: a foreign currency is no sign of travel),
 * `person` (also a first name: ignored for payments to a person).
 */
object CategoryDictionary {

    enum class Strength(val confidence: Double) { BRAND(0.92), KEYWORD(0.82), WEAK(0.65) }

    /** Which money direction an entry applies to. */
    enum class Direction { OUT, IN }

    /** Which list an entry belongs to: shop names, income words, or words of a payment message. */
    enum class Kind { SHOP, INCOME, MESSAGE }

    data class Entry(
        val keyword: String,
        val categoryKey: String,
        val strength: Strength,
        val direction: Direction = Direction.OUT,
        /** Must be a whole word ("RENT" must not match "RENTAL"). Keywords of 3 letters or less always are. */
        val wholeWord: Boolean = false,
        val kind: Kind = Kind.SHOP,
        /** Exists only abroad (E.Leclerc, SNCF, Rewe): a payment there was made abroad. */
        val abroad: Boolean = false,
        /** Paid from anywhere (Netflix, Steam, Trading 212): a foreign currency or country is no sign of travel. */
        val online: Boolean = false,
        /** Also a first name (Albert, Paul, Penny): never applied to a payment to a person. */
        val personName: Boolean = false,
        /** Data file the entry came from. */
        val source: String = "",
    ) {
        /** Must the keyword end where a word ends? (Always for keywords of 3 letters or less.) */
        internal val mustEndWord: Boolean = wholeWord || keyword.length <= 3

        /** Does [text] contain the keyword at the start of a word (and, if required, ending a word)? */
        internal fun matches(text: String): Boolean {
            var from = text.indexOf(keyword)
            while (from >= 0) {
                if (matchesAt(text, from)) return true
                from = text.indexOf(keyword, from + 1)
            }
            return false
        }

        internal fun matchesAt(text: String, start: Int): Boolean {
            if (start > 0 && isWordChar(text[start - 1])) return false
            if (!text.startsWith(keyword, start)) return false
            val end = start + keyword.length
            return !mustEndWord || end >= text.length || !isWordChar(text[end])
        }
    }

    /**
     * Fast lookup: entries grouped by their first 1-3 characters. A text is scanned once, word
     * start by word start, and only entries starting with the same letters are tried. Same result
     * as trying every entry, but ~100x faster with a dictionary of thousands of entries.
     */
    private class Index(entries: List<Entry>) {
        private val byPrefix: Map<String, List<Entry>> = entries.groupBy { it.keyword.take(3) }

        /** Every entry found in [text]. */
        fun find(text: String): List<Entry> {
            val found = mutableListOf<Entry>()
            for (start in text.indices) {
                if (start > 0 && isWordChar(text[start - 1])) continue
                for (len in 1..3) {
                    if (start + len > text.length) break
                    byPrefix[text.substring(start, start + len)]?.forEach { e ->
                        if (e.keyword.length >= len && (len == 3 || e.keyword.length == len) && e.matchesAt(text, start)) found += e
                    }
                }
            }
            return found
        }
    }

    /**
     * Priority of entries: BRAND before KEYWORD before WEAK, then the longer (more specific) name.
     * A brand that is also a name (Müller, Paul) ranks below a word naming the kind of shop:
     * "BÄCKEREI MÜLLER" is a bakery, not the drugstore.
     */
    private val PRIORITY = compareBy<Entry>(
        { if (it.personName && it.strength == Strength.BRAND) 3 else it.strength.ordinal * 2 },
        { -it.keyword.length },
    )

    data class Match(val entry: Entry, val confidence: Double, val where: String)

    /** Category keys the data files may use. */
    private val KNOWN_KEYS = setOf(
        CategoryKeys.SALARY, CategoryKeys.GIFTS, CategoryKeys.OTHER_INCOME, CategoryKeys.RENT, CategoryKeys.SUBSCRIPTIONS,
        CategoryKeys.GROCERIES, CategoryKeys.EATING_OUT, CategoryKeys.TRAVELLING, CategoryKeys.FUN, CategoryKeys.HOLIDAY,
        CategoryKeys.CLOTHING, CategoryKeys.DRUGSTORE, CategoryKeys.OTHER_EXPENSES, CategoryKeys.INVESTMENTS,
    )

    /** Data files, in load order; the file decides the list (shops, income words, payment-message words). */
    private val FILES = listOf(
        "shops_cz.tsv" to Kind.SHOP,
        "shops_fr.tsv" to Kind.SHOP,
        "shops_de.tsv" to Kind.SHOP,
        "shops_intl.tsv" to Kind.SHOP,
        "words.tsv" to Kind.SHOP,
        "income.tsv" to Kind.INCOME,
        "messages.tsv" to Kind.MESSAGE,
    )

    /** Every entry of every data file. */
    val entries: List<Entry> = FILES.flatMap { (file, kind) -> load(file, kind) }

    private val shops = entries.filter { it.kind == Kind.SHOP }
    private val shopIndex = Index(shops)
    private val rawTextIndex = Index(shops.filter { !it.personName && it.keyword.length >= 4 })
    private val incomeIndex = Index(entries.filter { it.kind == Kind.INCOME })
    private val messageIndex = Index(entries.filter { it.kind == Kind.MESSAGE })

    /** Words that make an incoming payment a refund of an earlier purchase. */
    private val REFUND = listOf(
        "VRACENI", "VRACENA", "VRACENO", "VRATKA", "REFUND", "DOBROPIS", "STORNO", "REKLAMACE", "RETURN", "CHARGEBACK",
        "REMBOURSEMENT", "AVOIR", "RUCKERSTATTUNG", "RUECKERSTATTUNG", "GUTSCHRIFT", "ERSTATTUNG",
    ).map { Entry(it, CategoryKeys.OTHER_EXPENSES, Strength.KEYWORD, Direction.IN) }

    /** Raw notification text is noisier than the merchant name: a match there counts a bit less. */
    const val RAW_TEXT_PENALTY = 0.07

    /** Confidence of a keyword found in the payer's own payment message. */
    const val MESSAGE_CONFIDENCE = 0.9

    /**
     * Best entry for a payment. [merchantTexts] are the canonical and raw merchant
     * keys, [rawText] the normalised notification / e-mail text. [toPerson]: a transfer
     * to someone's account, where a first name ("Albert") is not the supermarket.
     */
    fun matchOut(merchantTexts: List<String>, rawText: String, toPerson: Boolean = false): Match? {
        merchantTexts.flatMap(shopIndex::find)
            .filter { !(toPerson && it.personName) }
            .minWithOrNull(PRIORITY)
            ?.let { return Match(it, it.strength.confidence, "merchant") }
        // Raw text: only longer words (short brands like "DM" or "PID" appear in ordinary text by accident).
        return rawTextIndex.find(rawText).minWithOrNull(PRIORITY)
            ?.let { Match(it, it.strength.confidence - RAW_TEXT_PENALTY, "message text") }
    }

    /** Income keywords are usually in the message body ("Mzda 10/2026"), so it counts fully. */
    fun matchIn(merchantTexts: List<String>, rawText: String): Match? {
        val e = (merchantTexts + rawText).flatMap(incomeIndex::find).minWithOrNull(PRIORITY) ?: return null
        return Match(e, e.strength.confidence, "keyword")
    }

    /** A keyword in the payment message ([messageKey]: normalised, without dates and numbers). */
    fun matchMessage(messageKey: String): Match? {
        if (messageKey.isBlank()) return null
        val e = messageIndex.find(messageKey).maxByOrNull { it.keyword.length } ?: return null
        return Match(e, MESSAGE_CONFIDENCE, "payment message")
    }

    fun isRefund(merchantTexts: List<String>, rawText: String): Boolean {
        val texts = merchantTexts + rawText
        return REFUND.any { e -> texts.any(e::matches) }
    }

    /** A shop that exists only abroad ("E.LECLERC", "SNCF", "REWE", "BOULANGERIE"), if the name is one. */
    fun abroadShop(merchantTexts: List<String>): Entry? =
        merchantTexts.flatMap(shopIndex::find).filter { it.abroad }.minWithOrNull(PRIORITY)

    /** An online service paid from anywhere (Netflix, Steam, a broker). */
    fun isOnlineService(merchantTexts: List<String>): Boolean =
        merchantTexts.any { text -> shopIndex.find(text).any { it.online } }

    private fun isWordChar(c: Char): Boolean = c in 'A'..'Z' || c in '0'..'9'

    // ───────────────────────── data files ─────────────────────────

    private fun load(file: String, kind: Kind): List<Entry> {
        val stream = CategoryDictionary::class.java.getResourceAsStream("dictionary/$file")
            ?: error("dictionary file missing: $file")
        val out = mutableListOf<Entry>()
        var header: Header? = null
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { i, rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                if (line.startsWith("@")) {
                    header = parseHeader(line, "$file:${i + 1}")
                    return@forEachIndexed
                }
                val h = header ?: error("$file:${i + 1}: keywords before the first @category line")
                line.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { word ->
                    val keyword = TextNormalizer.merchantKey(word).replace(Regex("\\s+"), " ").trim()
                    if (keyword.isEmpty()) return@forEach
                    out += Entry(
                        keyword = keyword,
                        categoryKey = h.category,
                        strength = h.strength,
                        direction = if (kind == Kind.INCOME) Direction.IN else Direction.OUT,
                        wholeWord = h.whole,
                        kind = kind,
                        abroad = h.abroad,
                        online = h.online,
                        personName = h.person,
                        source = file,
                    )
                }
            }
        }
        return out
    }

    private class Header(
        val category: String,
        val strength: Strength,
        val whole: Boolean,
        val abroad: Boolean,
        val online: Boolean,
        val person: Boolean,
    )

    /** "@groceries BRAND whole abroad" */
    private fun parseHeader(line: String, where: String): Header {
        val parts = line.removePrefix("@").trim().split(Regex("\\s+"))
        val category = parts.getOrNull(0) ?: error("$where: missing category")
        require(category in KNOWN_KEYS) { "$where: unknown category '$category'" }
        val strength = parts.getOrNull(1)?.let { s -> Strength.entries.firstOrNull { it.name == s.uppercase() } }
            ?: error("$where: strength must be BRAND, KEYWORD or WEAK")
        val flags = parts.drop(2).map { it.lowercase() }.toSet()
        val unknown = flags - setOf("whole", "abroad", "online", "person")
        require(unknown.isEmpty()) { "$where: unknown flag(s) $unknown" }
        return Header(category, strength, "whole" in flags, "abroad" in flags, "online" in flags, "person" in flags)
    }
}
