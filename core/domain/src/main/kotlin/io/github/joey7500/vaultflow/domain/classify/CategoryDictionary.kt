package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.model.CategoryKeys.EATING_OUT
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.FUN
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.GIFTS
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.GROCERIES
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.HOLIDAY
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.INVESTMENTS
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.OTHER_EXPENSES
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.OTHER_INCOME
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.RENT
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.SALARY
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.SUBSCRIPTIONS
import io.github.joey7500.vaultflow.domain.model.CategoryKeys.TRAVELLING

/**
 * Built-in knowledge of the Czech market: brand names and generic keywords per
 * category. Used only when none of your own or learned rules matched.
 *
 * - BRAND: a specific chain or service ("LIDL", "NETFLIX"). Confident.
 * - KEYWORD: a word that names the kind of shop ("LEKARNA", "RESTAURACE"). Confident.
 * - WEAK: a hint that is often right but should be checked ("BAR", "AMAZON"). Suggestion only.
 */
object CategoryDictionary {

    enum class Strength(val confidence: Double) { BRAND(0.92), KEYWORD(0.82), WEAK(0.65) }

    /** Which money direction an entry applies to. */
    enum class Direction { OUT, IN }

    data class Entry(
        val keyword: String,
        val categoryKey: String,
        val strength: Strength,
        val direction: Direction = Direction.OUT,
        /** Must be a whole word ("RENT" must not match "RENTAL"). Keywords of 3 letters or less always are. */
        val wholeWord: Boolean = false,
    ) {
        internal val regex: Regex = run {
            val escaped = Regex.escape(keyword)
            val tail = if (wholeWord || keyword.length <= 3) "(?![A-Z0-9])" else ""
            Regex("(?<![A-Z0-9])$escaped$tail")
        }
    }

    data class Match(val entry: Entry, val confidence: Double, val where: String)

    // ───────────────────────── money out ─────────────────────────

    private fun brands(category: String, vararg names: String) = names.map { Entry(it, category, Strength.BRAND) }
    private fun keywords(category: String, vararg words: String) = words.map { Entry(it, category, Strength.KEYWORD) }
    private fun weak(category: String, vararg words: String) = words.map { Entry(it, category, Strength.WEAK) }
    private fun whole(category: String, strength: Strength, vararg words: String) = words.map { Entry(it, category, strength, wholeWord = true) }

    private val OUT: List<Entry> = listOf(
        // Eating out (food delivery before ride-hailing: "BOLT FOOD" vs "BOLT").
        brands(
            EATING_OUT, "BOLT FOOD", "WOLT", "FOODORA", "DAMEJIDLO", "MCDONALD", "MC DONALD", "KFC", "BURGER KING",
            "STARBUCKS", "COSTA COFFEE", "SUBWAY", "BAGETERIE BOULEVARD", "PAPA JOHN", "DOMINO", "PIZZA HUT",
            "VAPIANO", "AMBIENTE", "KOLKOVNA", "POTREFENA HUSA", "BAGEL LOUNGE", "COFFEESHOP COMPANY",
            "CROSSCAFE", "DOUBLESHOT", "UGO", "MANIFESTO",
        ),
        whole(EATING_OUT, Strength.BRAND, "PAUL"),
        keywords(
            EATING_OUT, "RESTAURACE", "RESTAURANT", "RISTORANTE", "KAVARNA", "BISTRO", "PIZZ", "HOSPODA", "PIVNICE",
            "JIDELNA", "MENZA", "BUFET", "CUKRARNA", "KEBAB", "SUSHI", "RAMEN", "BURGER", "TRATTORIA", "TAVERNA",
            "GASTRO", "PHO", "PUB", "CANTINA", "KANTYNA", "OBCERSTVENI", "COFFEE", "CAFFE", "ESPRESSO",
        ),
        whole(EATING_OUT, Strength.KEYWORD, "CAFE"),
        whole(EATING_OUT, Strength.WEAK, "BAR"),

        // Groceries.
        brands(
            GROCERIES, "ALBERT", "LIDL", "KAUFLAND", "BILLA", "PENNY", "TESCO", "GLOBUS", "ROHLIK", "KOSIK",
            "ZABKA", "TERNO", "JEDNOTA", "HRUSKA", "BRNENKA", "FLOP", "RESKO", "CBA", "MAKRO", "TRAVEL FREE",
        ),
        whole(GROCERIES, Strength.BRAND, "COOP", "NORMA"),
        keywords(GROCERIES, "POTRAVINY", "VECERKA", "SUPERMARKET", "HYPERMARKET", "ZELENINA", "REZNICTVI", "MASNA", "PEKARNA", "DELIKATESY", "MINIMARKET"),
        weak(GROCERIES, "MARKET"),

        // Subscriptions and telco.
        brands(
            SUBSCRIPTIONS, "NETFLIX", "SPOTIFY", "YOUTUBE", "DISNEY", "HBO", "MAX.COM", "APPLE.COM", "ICLOUD",
            "GOOGLE ONE", "OPENAI", "CHATGPT", "ANTHROPIC", "CLAUDE.AI", "VODAFONE", "T-MOBILE", "T MOBILE",
            "O2 CZECH", "O2 CZ", "TESCO MOBILE", "NORDVPN", "PROTON", "MICROSOFT", "ADOBE", "CANVA", "DROPBOX",
            "AUDIBLE", "STORYTEL", "VOYO", "ONEPLAY", "SKYLINK", "AMAZON PRIME", "PRIME VIDEO", "DUOLINGO",
            "GITHUB", "PATREON", "DEEZER", "TIDAL", "NOTION", "LINKEDIN",
        ),
        keywords(SUBSCRIPTIONS, "PREDPLATNE", "SUBSCRIPTION"),

        // Holiday (before generic transport).
        brands(
            HOLIDAY, "BOOKING.COM", "BOOKING", "AIRBNB", "RYANAIR", "WIZZ", "SMARTWINGS", "EASYJET", "LUFTHANSA",
            "CSA", "INVIA", "CEDOK", "FISCHER", "EXIM TOURS", "BLUE STYLE", "KIWI.COM", "EXPEDIA", "AGODA", "HOSTELWORLD",
        ),
        keywords(HOLIDAY, "HOTEL", "HOSTEL", "PENZION", "APARTMA", "KEMP"),
        weak(HOLIDAY, "LETISTE", "AIRPORT"),

        // Travelling / mobility.
        brands(
            TRAVELLING, "BOLT", "UBER", "LIFTAGO", "SHELL", "OMV", "MOL", "BENZINA", "ORLEN", "EUROOIL", "TANK ONO",
            "CESKE DRAHY", "CD.CZ", "REGIOJET", "LEO EXPRESS", "FLIXBUS", "ARRIVA", "DPP", "DPMB", "DPO", "PID",
            "LITACKA", "IDOS", "EDALNICE", "REKOLA", "NEXTBIKE", "SHARE NOW", "HOPPYGO",
        ),
        whole(TRAVELLING, Strength.BRAND, "LIME", "TIER"),
        keywords(TRAVELLING, "PARKOVANI", "PARKING", "DALNICNI", "CERPACI STANICE", "TAXI", "AUTOMYCKA", "JIZDENK"),

        // Fun & hobbies.
        brands(
            FUN, "DECATHLON", "SPORTISIMO", "STEAM", "PLAYSTATION", "NINTENDO", "XBOX", "CINEMA CITY", "CINESTAR",
            "ALZA", "DATART", "CZC", "GOOUT", "TICKETPORTAL", "TICKETMASTER", "TICKETSTREAM", "MULTISPORT",
            "LUXOR", "KNIHY DOBROVSKY", "MEGABOOKS", "MARTINUS", "GOOGLE PLAY", "EPIC GAMES",
        ),
        keywords(FUN, "KINO", "DIVADLO", "MUZEUM", "ZOO", "AQUAPARK", "FITNESS", "GYM", "BOULDER", "LEZECK", "KNIHKUPECTVI", "KONCERT", "VSTUPENK"),

        // Investing and building savings.
        brands(
            INVESTMENTS, "TRADING 212", "TRADING212", "XTB", "PORTU", "FONDEE", "ANYCOIN", "COINMATE", "BINANCE",
            "COINBASE", "DEGIRO", "INTERACTIVE BROKERS", "AMUNDI", "CONSEQ", "PATRIA",
        ),
        keywords(INVESTMENTS, "STAVEBNI SPORENI"),
        weak(INVESTMENTS, "PENZIJNI"),

        // Rent and housing costs.
        keywords(RENT, "NAJEMNE", "NAJEM", "NAJMU", "FOND OPRAV", "ZALOHY NA SLUZBY"),
        whole(RENT, Strength.KEYWORD, "RENT", "SVJ"),

        // Other everyday spend.
        brands(
            OTHER_EXPENSES, "DR.MAX", "DR MAX", "BENU", "DM DROGERIE", "ROSSMANN", "IKEA", "HORNBACH", "OBI",
            "BAUHAUS", "MOUNTFIELD", "UNI HOBBY", "JYSK", "PEPCO", "KIK", "TEDI", "H&M", "ZARA", "RESERVED",
            "C&A", "PRIMARK", "DEICHMANN", "CCC", "BATA", "NEW YORKER", "LINDEX", "ABOUT YOU", "ZALANDO", "TEMU",
            "SHEIN", "ALIEXPRESS", "NOTINO", "DOUGLAS", "SEPHORA", "CESKA POSTA", "ZASILKOVNA", "PPL", "DPD", "GLS",
        ),
        whole(OTHER_EXPENSES, Strength.BRAND, "DM", "TETA", "ACTION"),
        keywords(OTHER_EXPENSES, "LEKARNA", "DROGERIE", "KADERNICTVI", "BARBER", "OPTIKA", "ZUBNI", "POPLATEK", "POJISTENI", "POJISTOVNA", "PAPIRNICTVI"),
        weak(OTHER_EXPENSES, "AMAZON", "MALL.CZ"),
    ).flatten()

    // ───────────────────────── money in ─────────────────────────

    private val IN: List<Entry> = listOf(
        listOf("MZDA", "MZDY", "VYPLATA", "SALARY", "PAYROLL").map { Entry(it, SALARY, Strength.BRAND, Direction.IN) },
        listOf(Entry("PLAT", SALARY, Strength.KEYWORD, Direction.IN, wholeWord = true)),
        listOf("VRATKA DANE", "PREPLATEK", "UROK", "INTEREST", "CASHBACK", "DIVIDEND").map { Entry(it, OTHER_INCOME, Strength.KEYWORD, Direction.IN) },
        listOf("DAREK", "DAR", "GIFT", "NAROZENIN").map { Entry(it, GIFTS, Strength.KEYWORD, Direction.IN) },
        listOf("VANOCE", "SVATB").map { Entry(it, GIFTS, Strength.WEAK, Direction.IN) },
    ).flatten()

    /** Words that make an incoming payment a refund of an earlier purchase. */
    private val REFUND = listOf("VRACENI", "VRACENA", "VRACENO", "VRATKA", "REFUND", "DOBROPIS", "STORNO", "REKLAMACE", "RETURN", "CHARGEBACK")
        .map { Entry(it, OTHER_EXPENSES, Strength.KEYWORD, Direction.IN) }

    /** Raw notification text is noisier than the merchant name: a match there counts a bit less. */
    const val RAW_TEXT_PENALTY = 0.07

    private val outOrdered = OUT.sortedWith(compareBy<Entry>({ it.strength.ordinal }, { -it.keyword.length }))
    private val inOrdered = IN.sortedWith(compareBy<Entry>({ it.strength.ordinal }, { -it.keyword.length }))

    /**
     * Best entry for a payment. [merchantTexts] are the canonical and raw merchant
     * keys, [rawText] the normalised notification / e-mail text.
     */
    fun matchOut(merchantTexts: List<String>, rawText: String): Match? {
        for (e in outOrdered) {
            if (merchantTexts.any { e.regex.containsMatchIn(it) }) return Match(e, e.strength.confidence, "merchant")
        }
        // Raw text: only longer words (short brands like "DM" or "PID" appear in ordinary text by accident).
        for (e in outOrdered) {
            if (e.keyword.length >= 4 && e.regex.containsMatchIn(rawText)) {
                return Match(e, e.strength.confidence - RAW_TEXT_PENALTY, "message text")
            }
        }
        return null
    }

    /** Income keywords are usually in the message body ("Mzda 10/2026"), so it counts fully. */
    fun matchIn(merchantTexts: List<String>, rawText: String): Match? {
        val texts = merchantTexts + rawText
        for (e in inOrdered) {
            if (texts.any { e.regex.containsMatchIn(it) }) return Match(e, e.strength.confidence, "keyword")
        }
        return null
    }

    fun isRefund(merchantTexts: List<String>, rawText: String): Boolean {
        val texts = merchantTexts + rawText
        return REFUND.any { e -> texts.any { e.regex.containsMatchIn(it) } }
    }

    /** All entries, for tests and the "how it decides" help. */
    val entries: List<Entry> get() = OUT + IN
}
