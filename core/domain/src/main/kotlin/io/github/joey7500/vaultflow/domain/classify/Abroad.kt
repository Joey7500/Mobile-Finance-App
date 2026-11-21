package io.github.joey7500.vaultflow.domain.classify

import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/**
 * Was a card payment made abroad? Everything paid abroad is booked as Holiday.
 *
 * - **Sure:** a shop that exists only abroad (E.Leclerc, SNCF, Rewe, a "boulangerie", a
 *   "péage"), or two independent hints together.
 * - **Hints:** a foreign currency (Wallet and Revolut report "12,50 €"), a foreign country code
 *   or name ending the shop name ("… PARIS FR"), a foreign town, a foreign legal form (SARL, GmbH).
 *   A single hint is only a suggestion (shown under Quick review), and only when nothing else
 *   classified the payment confidently - except a foreign currency at a shop the app knows
 *   (a restaurant, Lidl, a pharmacy): a physical shop paid in euros is abroad (see Classifier).
 * - **Never:** an online service paid from anywhere (Netflix, Steam, a broker, Temu), or a shop
 *   name that places it at home (a Czech town, "CZ").
 */
object Abroad {

    data class Verdict(
        val confident: Boolean,
        val reason: String,
        /** Paid in a foreign currency (one of the hints). */
        val foreignCurrency: Boolean = false,
    )

    fun check(merchant: String, currency: String, merchantTexts: List<String>): Verdict? {
        if (CategoryDictionary.isOnlineService(merchantTexts)) return null
        val tokens = TextNormalizer.merchantKey(merchant).split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val text = " " + tokens.joinToString(" ") + " "
        if (isHome(tokens, text)) return null

        val hints = mutableListOf<String>()
        val foreignCurrency = currency.isNotBlank() && currency != "CZK"
        if (foreignCurrency) hints += "paid in $currency"
        foreignCountry(tokens, text)?.let { hints += it }
        FOREIGN_CITIES.firstOrNull { text.contains(" $it ") }?.let { hints += it.lowercase().replaceFirstChar(Char::uppercase) }
        // A Czech or Slovak legal form says nothing about where you paid, but it cancels a weak hint.
        val czechCompany = CZECH_LEGAL.any { text.contains(" $it ") } || tokens.lastOrNull()?.endsWith("SRO") == true
        FOREIGN_LEGAL.firstOrNull { text.contains(" $it ") }?.let { hints += it }

        CategoryDictionary.abroadShop(merchantTexts)?.let { shop ->
            return Verdict(true, (listOf(shop.keyword) + hints).joinToString(", "), foreignCurrency)
        }
        if (czechCompany) return null
        return when {
            hints.size >= 2 -> Verdict(true, hints.joinToString(", "), foreignCurrency)
            hints.size == 1 -> Verdict(false, hints.single(), foreignCurrency)
            else -> null
        }
    }

    private fun isHome(tokens: List<String>, text: String): Boolean =
        tokens.size >= 2 && tokens.last() in HOME_CODES ||
            HOME_NAMES.any { text.contains(" $it ") } ||
            CZECH_CITIES.any { text.contains(" $it ") }

    /** "… PARIS FR", "… FRANCE": a country code as the last word, or a country name anywhere. */
    private fun foreignCountry(tokens: List<String>, text: String): String? {
        if (tokens.size >= 2 && tokens.last() in COUNTRY_CODES) return tokens.last()
        return COUNTRY_NAMES.firstOrNull { text.contains(" $it ") }
    }

    private val HOME_CODES = setOf("CZ", "CZE")
    private val HOME_NAMES = listOf("CESKA REPUBLIKA", "CZECH REPUBLIC", "CZECHIA")

    private val CZECH_LEGAL = listOf("S R O", "SRO", "SPOL S R O", "A S", "V O S", "Z S")
    private val FOREIGN_LEGAL = listOf("SARL", "EURL", "GMBH", "SRL", "KFT", "SP Z O O")

    private val COUNTRY_CODES = setOf(
        "FR", "FRA", "DE", "DEU", "AT", "AUT", "IT", "ITA", "ES", "ESP", "PL", "POL", "SK", "SVK", "HU", "HUN",
        "HR", "HRV", "SI", "SVN", "NL", "NLD", "BE", "BEL", "CH", "CHE", "GB", "GBR", "UK", "PT", "PRT", "GR", "GRC",
        "DK", "DNK", "SE", "SWE", "NO", "NOR", "LU", "LUX", "IE", "IRL", "US", "USA",
    )

    private val COUNTRY_NAMES = listOf(
        "FRANCE", "FRANKREICH", "GERMANY", "DEUTSCHLAND", "ALLEMAGNE", "AUSTRIA", "OSTERREICH", "OESTERREICH", "AUTRICHE",
        "ITALY", "ITALIA", "ITALIE", "SPAIN", "ESPANA", "ESPAGNE", "POLAND", "POLSKA", "SLOVAKIA", "SLOVENSKO", "HUNGARY",
        "MAGYARORSZAG", "CROATIA", "HRVATSKA", "SLOVENIA", "SLOVENIJA", "NETHERLANDS", "NEDERLAND", "BELGIUM", "BELGIQUE",
        "SWITZERLAND", "SCHWEIZ", "SUISSE", "PORTUGAL", "GREECE", "DENMARK", "DANMARK", "SWEDEN", "SVERIGE", "NORWAY",
        "NORGE", "LUXEMBOURG", "IRELAND", "UNITED KINGDOM",
    )

    /** Towns of the owner's country: a shop "… PRAHA" is at home even if its name sounds foreign. */
    private val CZECH_CITIES = listOf(
        "PRAHA", "PRAGUE", "BRNO", "OSTRAVA", "PLZEN", "LIBEREC", "OLOMOUC", "CESKE BUDEJOVICE", "HRADEC KRALOVE",
        "PARDUBICE", "USTI NAD LABEM", "ZLIN", "HAVIROV", "KLADNO", "OPAVA", "FRYDEK MISTEK", "KARVINA", "JIHLAVA",
        "TEPLICE", "DECIN", "KARLOVY VARY", "CHOMUTOV", "JABLONEC NAD NISOU", "MLADA BOLESLAV", "PROSTEJOV", "PREROV",
        "TREBIC", "CESKA LIPA", "TABOR", "ZNOJMO", "KOLIN", "PISEK", "KROMERIZ", "VYSKOV", "BLANSKO", "BRECLAV",
        "HODONIN", "UHERSKE HRADISTE", "TRINEC", "CHEB", "SOKOLOV", "TRUTNOV", "MIKULOV", "TELC", "KUTNA HORA",
        "CESKY KRUMLOV", "LITOMERICE", "KROMERIZ", "SPINDLERUV MLYN", "HARRACHOV", "PEC POD SNEZKOU",
    )

    /** Towns abroad (not ones that are ordinary English words, like Nice or Tours). */
    private val FOREIGN_CITIES = listOf(
        // France
        "PARIS", "LYON", "MARSEILLE", "BORDEAUX", "TOULOUSE", "NANTES", "STRASBOURG", "LILLE", "MONTPELLIER", "RENNES",
        "GRENOBLE", "ANNECY", "CHAMONIX", "AVIGNON", "CANNES", "ANTIBES", "BIARRITZ", "LA ROCHELLE", "DIJON", "REIMS",
        "ROUEN", "ORLEANS", "METZ", "COLMAR", "MULHOUSE", "PERPIGNAN", "NIMES", "AIX EN PROVENCE", "TOULON", "BREST",
        "CAEN", "LE HAVRE", "VERSAILLES", "SAINT MALO", "ST MALO", "BEAUNE", "ARCACHON", "CHAMBERY", "VAL THORENS",
        "TIGNES", "VAL D ISERE", "COURCHEVEL", "MERIBEL", "LES ARCS", "LA PLAGNE", "MEGEVE", "MORZINE",
        "ALPE D HUEZ", "LES DEUX ALPES", "BRIANCON", "SAINT TROPEZ", "MENTON", "AJACCIO", "BASTIA", "CARCASSONNE",
        "AMIENS", "CLERMONT FERRAND", "LIMOGES", "POITIERS", "ANGERS", "LE MANS", "BESANCON", "LORIENT", "VANNES",
        "QUIMPER", "MONACO", "CHARTRES", "BAYONNE", "PERIGUEUX", "SARLAT",
        // Germany, Austria
        "MUNCHEN", "MUENCHEN", "MUNICH", "BERLIN", "HAMBURG", "KOLN", "KOELN", "COLOGNE", "FRANKFURT", "STUTTGART",
        "DUSSELDORF", "DUESSELDORF", "DRESDEN", "LEIPZIG", "NURNBERG", "NUERNBERG", "NUREMBERG", "REGENSBURG",
        "PASSAU", "AUGSBURG", "BAYREUTH", "HANNOVER", "BREMEN", "CHEMNITZ", "ZITTAU", "GORLITZ", "GOERLITZ",
        "WIEN", "VIENNA", "SALZBURG", "LINZ", "GRAZ", "INNSBRUCK", "KLAGENFURT", "VILLACH", "BREGENZ", "ZELL AM SEE",
        "SCHLADMING", "KITZBUHEL", "KITZBUEHEL", "ISCHGL", "SOLDEN", "SOELDEN", "MAYRHOFEN", "SAALBACH",
        // elsewhere in Europe
        "BRATISLAVA", "KOSICE", "ZILINA", "POPRAD", "BUDAPEST", "KRAKOW", "WROCLAW", "WARSZAWA", "WARSAW", "KATOWICE",
        "GDANSK", "LJUBLJANA", "ZAGREB", "DUBROVNIK", "ZADAR", "PULA", "ROMA", "ROME", "MILANO",
        "VENEZIA", "VENICE", "FIRENZE", "FLORENCE", "NAPOLI", "BOLOGNA", "VERONA", "TORINO", "BARCELONA", "MADRID",
        "VALENCIA", "SEVILLA", "MALAGA", "PALMA", "LISBOA", "LISBON", "LONDON", "EDINBURGH", "DUBLIN",
        "AMSTERDAM", "ROTTERDAM", "BRUXELLES", "BRUSSELS", "ANTWERPEN", "ZURICH", "GENEVE", "GENEVA", "BASEL",
        "LUZERN", "LUXEMBOURG", "KOBENHAVN", "COPENHAGEN", "STOCKHOLM", "OSLO", "HELSINKI", "ATHENS", "ATHINA",
    )
}
