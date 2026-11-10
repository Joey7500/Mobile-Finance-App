package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/** A transaction extracted from a single notification. */
data class ParsedNotification(
    /** Signed minor units in [currency]. Negative = money out. */
    val amountMinor: Long,
    val currency: String,
    val merchant: String,
    /** Account balance mentioned in the same notification, if any (absolute, minor units). */
    val mentionedBalanceMinor: Long?,
    val isFromWallet: Boolean,
)

enum class Direction { IN, OUT }

/**
 * Turns a notification (title + text) into a transaction, or returns null when
 * the notification is not a completed money movement (marketing, OTP codes,
 * 3-D Secure confirmations, declined payments, balance-only alerts...).
 *
 * The parser is deliberately bank-agnostic: it looks for an amount with a
 * currency, decides the direction from wording, and extracts a merchant.
 */
object NotificationParser {
    const val GOOGLE_WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"

    /**
     * Wording of notifications that are NOT a completed transaction. Each entry
     * must start at a word boundary ("akce" must not match "transakce").
     */
    private val IGNORE = Regex(
        "\\b(?:" + listOf(
            // "Potvrďte platbu" (please confirm) is a request, "potvrzujeme platbu" (we confirm) a receipt.
            "potvrdte", "potvrdit", "please confirm", "confirm your", "confirm the", "to confirm", "approve",
            "schvalte", "schvalit", "overte", "overeni", "verify", "verification",
            "kod\\b", "code\\b", "otp\\b", "heslo", "password",
            "zamitnut", "zamitl", "declined", "rejected", "failed", "neprovedena", "nepodarilo", "neuspes",
            "insufficient", "nedostatek",
            "akce\\b", "sleva", "nabidka", "offer", "promo",
        ).joinToString("|") + ")",
    )

    private val IN_WORDS = listOf(
        // Not "prijem": it also matches "příjemce" (recipient) in outgoing payments.
        "prichozi", "prisla", "prislo", "prisel", "prijata", "prijato", "prijem na", "pripsan",
        "obdrzel", "vraceni", "vracen", "refund", "received", "incoming", "credited",
        "deposit", "you got", "sent you", "poslal vam", "poslala vam", "zaslal vam",
        "vyplata", "mzda", "salary",
        "dobiti", "dobito", "top-up", "topped up", "money added", "added to", "pridano",
    )

    private val OUT_WORDS = listOf(
        "odchozi", "odesla", "odeslan", "platba", "platbu", "zaplac", "vyber", "vydaj", "debet", "odepsan", "strzen",
        "paid", "payment", "purchase", "spent", "debited", "withdrawal", "you sent",
        "card transaction", "transakce kartou",
    )

    private val MERCHANT_PATTERNS = listOf(
        Regex("""(?i)u\s+obchodn[ií]ka\s+(.+?)(?:\s+dne\b|\s+v\s+hodnot|\s+za\b|[,;\n]|\.(?:\s|$)|$)"""),
        Regex("""(?i)(?:obchodn[ií]k|merchant|p[řr][ií]jemce|recipient|payee)\s*[:\-]\s*(.+?)(?:[,;\n]|$)"""),
        Regex("""(?i)(?:^|\s)(?:at|u)\s+([A-Z0-9][^,;\n]{1,40}?)(?:\s+(?:on|dne|v\s+hodnot|for|za)\b|[,;\n]|\.(?:\s|$)|$)"""),
        Regex("""(?i)(?:^|\s)(?:from|od)\s*:?\s+([A-Za-zÀ-ž0-9][^,;\n]{1,40}?)(?:\s+(?:on|dne)\b|[,;\n]|\.(?:\s|$)|$)"""),
    )

    /** Words that show a message is about a money MOVEMENT (not just a balance statement). */
    private val MOVEMENT_WORDS = listOf(
        "platb", "odesl", "odchoz", "prichoz", "prisl", "pripsan", "transakc", "kartou", "karta ", "card",
        "paid", "payment", "prevod", "transfer", "vyber", "debit", "credit", "received", "sent", "zaplac",
        "nakup", "purchase", "refund", "vracen", "strzen", "dobit", "top-up", "topped", "spent",
    )
    private val BALANCE_WORDS = listOf("zustatek", "disponibiln", "k dispozici", "balance", "available")

    fun isIgnored(normalized: String): Boolean = IGNORE.containsMatchIn(normalized)

    /** "Zůstatek na účtu ... je 3 700 Kč": a balance statement without any payment. */
    fun isBalanceOnly(normalized: String): Boolean =
        BALANCE_WORDS.any { normalized.contains(it) } && MOVEMENT_WORDS.none { normalized.contains(it) }

    fun parse(packageName: String, title: String?, text: String?, titleMayBeMerchant: Boolean = true): ParsedNotification? {
        val t = title.orEmpty().trim()
        val body = text.orEmpty().trim()
        val full = listOf(t, body).filter { it.isNotEmpty() }.joinToString("\n")
        if (full.isBlank()) return null

        val normalized = TextNormalizer.lowerAscii(full)
        if (IGNORE.containsMatchIn(normalized)) return null
        if (isBalanceOnly(normalized)) return null

        val amounts = AmountParser.findAll(full)
        val txAmount = amounts.firstOrNull { !it.isBalance } ?: return null
        if (txAmount.minor <= 0L) return null
        val balance = amounts.firstOrNull { it.isBalance }?.minor

        val isWallet = packageName == GOOGLE_WALLET_PACKAGE
        val direction = direction(normalized, txAmount, isWallet)
        val signed = if (direction == Direction.OUT) -txAmount.minor else txAmount.minor

        return ParsedNotification(
            amountMinor = signed,
            currency = txAmount.currency,
            merchant = extractMerchant(if (titleMayBeMerchant || isWallet) t else "", body, isWallet),
            mentionedBalanceMinor = balance,
            isFromWallet = isWallet,
        )
    }

    fun direction(normalizedText: String, amount: FoundAmount, isWallet: Boolean): Direction {
        if (amount.explicitSign < 0) return Direction.OUT
        if (amount.explicitSign > 0) return Direction.IN
        if (isWallet) {
            return if (normalizedText.contains("refund") || normalizedText.contains("vracen")) Direction.IN else Direction.OUT
        }
        // Explicit direction ("Směr platby: odchozí") beats any other wording.
        val saysOut = normalizedText.contains("odchozi") || normalizedText.contains("outgoing")
        val saysIn = normalizedText.contains("prichozi") || normalizedText.contains("incoming")
        if (saysOut && !saysIn) return Direction.OUT
        if (saysIn && !saysOut) return Direction.IN
        if (IN_WORDS.any { normalizedText.contains(it) }) return Direction.IN
        if (OUT_WORDS.any { normalizedText.contains(it) }) return Direction.OUT
        return Direction.OUT
    }

    /** Merchant from explicit wording only ("u obchodníka X", "at X", "od X"), or null. */
    fun merchantFromPatterns(title: String, body: String, isWallet: Boolean): String? {
        if (isWallet && title.isNotBlank() && !title.contains("wallet", ignoreCase = true)) {
            return clean(title)
        }
        for (source in listOf(body, title)) {
            for (p in MERCHANT_PATTERNS) {
                val m = p.find(source) ?: continue
                val candidate = clean(m.groupValues[1])
                if (candidate.length >= 2 && !AmountParser.containsMoney(candidate)) return candidate
            }
        }
        return null
    }

    fun extractMerchant(title: String, body: String, isWallet: Boolean): String {
        merchantFromPatterns(title, body, isWallet)?.let { return it }
        // Many banks (e.g. George) put the merchant in the TITLE and a generic
        // "72,90 CZK paid by card 1234." in the body: prefer a non-generic title.
        val cleanTitle = clean(title)
        if (cleanTitle.length >= 2 && !isGenericTitle(cleanTitle)) return cleanTitle
        val stripped = clean(stripAmounts(body).replace(GREETING, " "))
        return when {
            stripped.length in 2..60 -> stripped
            cleanTitle.isNotBlank() -> cleanTitle
            else -> stripped.take(60)
        }
    }

    private val GREETING = Regex("""(?i)dobr[ýy]\s+den[^\n,]*,?|hello[^\n,]*,?|hi [^\n,]*,""")

    private val GENERIC_TITLE_WORDS = setOf(
        "platba", "kartou", "karta", "karty", "odchozi", "prichozi", "transakce", "prevod", "uhrada",
        "payment", "card", "transaction", "incoming", "outgoing", "transfer", "received", "sent",
        "vraceni", "refund", "vyber", "bankomat", "atm", "nova", "new", "z", "na", "uctu", "ucet", "account",
        "uctem", "platby", "prevodem", "informace", "oznameni", "notifikace", "pohyb", "pohyby", "zustatek",
    )

    /** "Platba kartou", "Příchozí platba", "Card payment" carry no merchant. */
    private fun isGenericTitle(title: String): Boolean {
        val words = TextNormalizer.lowerAscii(title).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.isEmpty() || words.all { it in GENERIC_TITLE_WORDS || it.all(Char::isDigit) }
    }

    private fun stripAmounts(s: String): String {
        var out = s
        AmountParser.findAll(s).sortedByDescending { it.range.first }.forEach {
            out = out.removeRange(it.range)
        }
        return out
    }

    private fun clean(s: String): String =
        s.replace(Regex("[\\s\\u00A0\\u202F]+"), " ").trim()
            .replace(Regex("^[^\\p{L}\\p{N}]+"), "") // leading emoji / symbols ("💳 VUT PRODEJNA 1")
            .trim().trim('.', ',', ':', ';', '-', '–').trim().take(60)
}
