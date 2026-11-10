package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.text.TextNormalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Where an incoming bank message came from. */
enum class Channel { NOTIFICATION, EMAIL }

/** How an account number is used in a message. */
enum class AccountRole {
    /** "z účtu X", "from account X": money left X. */
    FROM,

    /** "na účet X", "to account X": money went to X. */
    TO,

    /** Mentioned without direction, e.g. "Na účtu X je k dispozici…". */
    MENTION,
}

data class AccountMention(
    /** Main account number digits without prefix and bank code ("19-123456/9999" -> "123456"). */
    val number: String,
    val bankCode: String?,
    val role: AccountRole,
    /** As written in the message, e.g. "123456/9999". */
    val raw: String,
)

/**
 * Everything one bank notification or e-mail tells us. A message can carry a
 * transaction, a balance, or both (Česká spořitelna e-mails do).
 */
data class BankMessage(
    val transaction: ParsedNotification?,
    /** "k dispozici 5 000 Kč": available funds as stated, may include an overdraft. */
    val availableMinor: Long?,
    /** "(včetně kontokorentu 1 000 Kč)": overdraft included in [availableMinor]. */
    val overdraftMinor: Long?,
    val accounts: List<AccountMention>,
    val cardDigits: List<String>,
    /** Exact payment time when the message states one ("10. 11. 2026 17:00"). */
    val eventTimeMillis: Long?,
    /** Payment date when only a date is stated ("ze dne 10. 11. 2026"). */
    val eventDate: LocalDate?,
    /** Merchant came from explicit wording, not from a fallback. */
    val hasExplicitMerchant: Boolean,
    /** "k dispozici / disponibilní / available": the figure may include an overdraft. */
    val availableMayIncludeOverdraft: Boolean = false,
) {
    /** Your own money on the account: available minus overdraft. */
    val ownFundsMinor: Long? get() = availableMinor?.let { it - (overdraftMinor ?: 0L) }
}

object BankMessageParser {

    // "19-1234567890/9999", "123456/9998" (bank code always 4 digits).
    private val ACCOUNT_WITH_CODE = Regex("""(?<![\d/])(?:(\d{1,6})-)?(\d{2,10})/(\d{4})(?!\d)""")

    // "Na účtu 1234567890 je k dispozici": bare number right after the word "účet/účtu".
    private val ACCOUNT_BARE = Regex("""(?i)(?:účt[uěo]|účet|uctu|ucet|account)\s*(?:č\.\s*)?(?:(\d{1,6})-)?(\d{6,10})(?![\d/])""")

    private val CARD_PATTERNS = listOf(
        Regex("""(?i)kart(?:ou|a|y|ě|e)?\s*(?:č\.\s*|číslo\s*)?[*x•·.\s]*(\d{4})(?!\d)"""),
        Regex("""(?i)card\s*(?:ending\s*(?:in\s*)?|no\.?\s*)?[*x•·.\s]*(\d{4})(?!\d)"""),
        Regex("""(?i)(?:visa|mastercard|maestro|debit|credit)\s*[*x•·.\s]*(\d{4})(?!\d)"""),
    )

    // "10. 11. 2026 17:00", "10.11.2026 v 17:00", "10. 11. 2026"
    private val DATE_TIME = Regex("""(?<!\d)(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4})(?:\s*(?:v|at|,)?\s*(\d{1,2}):(\d{2}))?""")

    private val FROM_WORDS = listOf("z uctu", "z účtu", "from account", "from", "odesilatel", "plátce", "platce", "protiucet")
    private val TO_WORDS = listOf("na ucet", "na účet", "na vas ucet", "na váš účet", "to account", "příjemce", "prijemce", "recipient")

    fun parse(
        sourceKey: String,
        channel: Channel,
        title: String?,
        text: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BankMessage? {
        val t = title.orEmpty().trim()
        val body = text.orEmpty().trim()
        val full = listOf(t, body).filter { it.isNotEmpty() }.joinToString("\n")
        if (full.isBlank()) return null
        val normalized = TextNormalizer.lowerAscii(full)
        if (NotificationParser.isIgnored(normalized)) return null

        // E-mail subjects are rarely the merchant; notification titles often are (George, Wallet).
        val tx = NotificationParser.parse(sourceKey, t, body, titleMayBeMerchant = channel == Channel.NOTIFICATION)
        val explicitMerchant = NotificationParser.merchantFromPatterns(
            if (channel == Channel.NOTIFICATION) t else "", body, sourceKey == NotificationParser.GOOGLE_WALLET_PACKAGE,
        ) != null

        val amounts = AmountParser.findAll(full)
        val overdraft = amounts.firstOrNull { precededBy(full, it.range.first, listOf("kontokorent", "overdraft", "povolen")) }
        val available = if (NotificationParser.isBalanceOnly(normalized)) {
            amounts.firstOrNull { it != overdraft }
        } else {
            amounts.firstOrNull { it.isBalance && it != overdraft }
        }

        val accounts = accountMentions(full)
        val cards = cardDigits(full)
        val (eventTime, eventDate) = eventTime(full, zone)

        if (tx == null && available == null) return null
        return BankMessage(
            transaction = tx,
            availableMinor = available?.minor,
            overdraftMinor = overdraft?.minor,
            accounts = accounts,
            cardDigits = cards,
            eventTimeMillis = eventTime,
            eventDate = eventDate,
            hasExplicitMerchant = explicitMerchant,
            availableMayIncludeOverdraft = listOf("k dispozici", "disponibil", "available").any { normalized.contains(it) },
        )
    }

    fun accountMentions(text: String): List<AccountMention> {
        val out = mutableListOf<AccountMention>()
        val taken = mutableListOf<IntRange>()
        for (m in ACCOUNT_WITH_CODE.findAll(text)) {
            val number = m.groupValues[2].trimStart('0').ifEmpty { "0" }
            // A date like 10/2026 is not an account: require at least 4 digits before the slash.
            if (m.groupValues[2].length < 4) continue
            out += AccountMention(number, m.groupValues[3], roleBefore(text, m.range.first), m.value)
            taken += m.range
        }
        for (m in ACCOUNT_BARE.findAll(text)) {
            val g = m.groups[2] ?: continue
            if (taken.any { it.first <= g.range.last && g.range.first <= it.last }) continue
            out += AccountMention(g.value.trimStart('0'), null, roleBefore(text, m.range.first), g.value)
        }
        return out.distinctBy { it.number to it.role }
    }

    fun cardDigits(text: String): List<String> =
        CARD_PATTERNS.flatMap { p -> p.findAll(text).map { it.groupValues[1] }.toList() }.distinct()

    /** First date in the message; with a time when one follows it. */
    fun eventTime(text: String, zone: ZoneId): Pair<Long?, LocalDate?> {
        for (m in DATE_TIME.findAll(text)) {
            val (d, mo, y) = Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            if (mo !in 1..12 || d !in 1..31 || y !in 2000..2100) continue
            val date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: continue
            val hh = m.groupValues[4].toIntOrNull()
            val mm = m.groupValues[5].toIntOrNull()
            if (hh != null && mm != null && hh in 0..23 && mm in 0..59) {
                return LocalDateTime.of(date.year, date.month, date.dayOfMonth, hh, mm).atZone(zone).toInstant().toEpochMilli() to date
            }
            return null to date
        }
        return null to null
    }

    /** Main account digits from what the user typed ("19-1234567890/9999" -> "1234567890"). */
    fun normalizeAccountNumber(input: String): String =
        input.substringBefore('/').substringAfter('-').filter(Char::isDigit).trimStart('0')

    /** True when a mentioned number is the user's account ([own] may be just the last digits). */
    fun sameAccount(mentioned: String, own: String): Boolean {
        val o = normalizeAccountNumber(own)
        if (o.length < 4) return false
        return mentioned == o || (o.length >= 6 && mentioned.endsWith(o))
    }

    private fun roleBefore(text: String, start: Int): AccountRole {
        val window = TextNormalizer.lowerAscii(text.substring(maxOf(0, start - 22), start))
        val from = FROM_WORDS.map(TextNormalizer::lowerAscii).maxOfOrNull { w -> window.lastIndexOf(w) } ?: -1
        val to = TO_WORDS.map(TextNormalizer::lowerAscii).maxOfOrNull { w -> window.lastIndexOf(w) } ?: -1
        return when {
            from < 0 && to < 0 -> AccountRole.MENTION
            from > to -> AccountRole.FROM
            else -> AccountRole.TO
        }
    }

    private fun precededBy(text: String, start: Int, words: List<String>): Boolean {
        val window = TextNormalizer.lowerAscii(text.substring(maxOf(0, start - 24), start))
        return words.any { window.contains(it) }
    }
}
