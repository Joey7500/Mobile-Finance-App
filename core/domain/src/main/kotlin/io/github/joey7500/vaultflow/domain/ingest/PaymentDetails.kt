package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.classify.MerchantNormalizer
import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/** What kind of bank message this is. */
enum class BankMessageKind { CARD_PAYMENT, OUTGOING_TRANSFER, INCOMING_TRANSFER, BALANCE, OTHER }

/**
 * The structured part of a bank message: who or what was paid and why.
 * Česká spořitelna e-mails carry it in a sentence ("… u Hospoda U Lipy 2.")
 * and in an "Informace o transakci" block of "Label: value" lines
 * ("Na účet: …", "Zpráva pro příjemce: padel 3. 12.", "Konstantní symbol: 0").
 */
data class PaymentDetails(
    val kind: BankMessageKind = BankMessageKind.OTHER,
    /** Card payments: the shop as the card terminal names it ("FAKESPORT s.r.o."). */
    val merchant: String? = null,
    /** Card payments: the city when the shop name ends with one ("DECATHLON OLOMOUC" -> "Olomouc"). */
    val location: String? = null,
    /** Transfers: the other party's name, when the bank states it. */
    val counterpartyName: String? = null,
    /** Transfers: the other party's account as written ("7654321/9998"). */
    val counterpartyAccount: String? = null,
    /** The payer's own words: "Zpráva pro příjemce", "Poznámka", "Popis"… */
    val message: String? = null,
    val variableSymbol: String? = null,
    val constantSymbol: String? = null,
    val specificSymbol: String? = null,
) {
    /** "VS 123 · SS 9", empty when none. */
    val symbols: String
        get() = listOfNotNull(
            variableSymbol?.let { "VS $it" }, constantSymbol?.let { "KS $it" }, specificSymbol?.let { "SS $it" },
        ).joinToString(" · ")
}

object PaymentDetailsParser {

    /** A "Label:" at the start of a line: letters and spaces only (a time like "10:12" is not a label). */
    private val LABEL_LINE = Regex("""^(\p{L}[\p{L} .]{0,40}?)\s*:\s*(.*)$""")

    private val MESSAGE_LABELS = setOf(
        "zprava pro prijemce", "zprava pro platce", "zprava pro mne", "zprava pro me", "zprava",
        "poznamka pro prijemce", "poznamka pro platce", "poznamka pro mne", "poznamka pro me", "poznamka",
        "popis platby", "popis pro prijemce", "popis pro me", "popis", "ucel platby", "ucel",
        "informace pro prijemce", "informace pro platce", "message for recipient", "message", "reference",
        "description", "note",
    )

    private val NAME_LABELS = setOf(
        "nazev protiuctu", "nazev uctu protistrany", "nazev protistrany", "jmeno protistrany", "protistrana",
        "nazev uctu prijemce", "nazev prijemce", "jmeno prijemce", "prijemce", "nazev uctu platce",
        "nazev platce", "jmeno platce", "platce", "odesilatel", "jmeno odesilatele", "nazev odesilatele",
        "counterparty", "beneficiary", "payee", "recipient", "payer", "sender",
    )

    private val TO_ACCOUNT_LABELS = setOf("na ucet", "ucet prijemce", "cislo uctu prijemce", "protiucet", "ucet protistrany")
    private val FROM_ACCOUNT_LABELS = setOf("z uctu", "ucet platce", "cislo uctu platce", "ucet odesilatele", "protiucet", "ucet protistrany")

    private val ACCOUNT = Regex("""(?<![\d/])(?:\d{1,6}-)?\d{2,10}/\d{4}(?!\d)""")

    /** "Platba kartou u Hospoda U Lipy 2" (ČS e-mail subject). */
    private val CARD_SUBJECT = Regex("""(?i)^\s*platba\s+kartou\s+u\s+(.+?)\s*$""")

    /**
     * "potvrzujeme platbu kartou 1111 ve výši 214,00 Kč ze dne 20. 11. 2026 u Hospoda U Lipy 2."
     * The shop ends at the sentence's full stop ("u FAKESPORT s.r.o.." keeps "s.r.o.").
     */
    private val CARD_SENTENCE = Regex(
        """(?is)platb[uay]\s+kartou\b.{0,160}?\su\s+(.+?)(?:\.(?=[ \t]*(?:\n|$))|\.\s+(?=\p{Lu})|\n|$)""",
    )

    /** "Lidl dekuje za nakup" -> "Lidl": terminals append a thank-you to the shop name. */
    private val THANKS = Regex("""(?i)\s+(?:d[eě]kuj(?:e|eme)(?:\s+za\s+(?:n[aá]kup|nav[sš]t[eě]vu|platbu))?|thank(?:s|\s+you)(?:\s+for\b.*)?)\s*$""")

    fun parse(
        title: String,
        body: String,
        accounts: List<AccountMention>,
        amountMinor: Long?,
        hasBalance: Boolean,
    ): PaymentDetails {
        val normalized = TextNormalizer.lowerAscii("$title\n$body")
        val fields = fields(body) + fields(title)
        fun field(labels: Set<String>): String? = fields.firstOrNull { it.first in labels }?.second?.takeIf { it.isNotBlank() }

        val cardMerchant = cardMerchant(title, body)
        val kind = kind(normalized, cardMerchant != null, amountMinor, hasBalance)

        val message = field(MESSAGE_LABELS)?.let(::cleanText)?.takeIf { it.any(Char::isLetterOrDigit) && it != "-" }
        val name = fields.firstOrNull { (label, value) ->
            label in NAME_LABELS && value.any(Char::isLetter) && !ACCOUNT.containsMatchIn(value)
        }?.second?.let(::cleanText)

        val outgoing = (amountMinor ?: 0L) < 0
        val accountField = field(if (outgoing) TO_ACCOUNT_LABELS else FROM_ACCOUNT_LABELS)?.let { ACCOUNT.find(it)?.value }
        val otherRole = if (outgoing) AccountRole.TO else AccountRole.FROM
        val counterpartyAccount = when (kind) {
            BankMessageKind.OUTGOING_TRANSFER, BankMessageKind.INCOMING_TRANSFER, BankMessageKind.OTHER ->
                accountField ?: accounts.firstOrNull { it.role == otherRole && it.bankCode != null }?.raw
            else -> null
        }

        return PaymentDetails(
            kind = kind,
            merchant = cardMerchant,
            location = cardMerchant?.let(MerchantNormalizer::trailingCity),
            counterpartyName = name,
            counterpartyAccount = counterpartyAccount,
            message = message,
            variableSymbol = symbol(field(setOf("variabilni symbol", "vs"))),
            constantSymbol = symbol(field(setOf("konstantni symbol", "ks"))),
            specificSymbol = symbol(field(setOf("specificky symbol", "ss"))),
        )
    }

    /**
     * "Label: value" pairs in order. A label alone on its line ("Datum a čas platby:")
     * takes the next line as its value, unless that line is itself a label.
     */
    fun fields(text: String): List<Pair<String, String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < lines.size) {
            val m = LABEL_LINE.find(lines[i])
            if (m != null) {
                val label = TextNormalizer.lowerAscii(m.groupValues[1]).trimEnd('.', ' ')
                var value = m.groupValues[2].trim()
                if (value.isEmpty() && i + 1 < lines.size && !LABEL_LINE.containsMatchIn(lines[i + 1])) {
                    value = lines[i + 1]
                    i++
                }
                out += label to value
            }
            i++
        }
        return out
    }

    fun cardMerchant(title: String, body: String): String? {
        val raw = CARD_SUBJECT.find(title)?.groupValues?.get(1)
            ?: CARD_SENTENCE.find(body)?.groupValues?.get(1)
            ?: return null
        val cleaned = cleanText(raw).replace(THANKS, "").trim()
        return cleaned.takeIf { it.length >= 2 && !AmountParser.containsMoney(it) }
    }

    private fun kind(n: String, card: Boolean, amountMinor: Long?, hasBalance: Boolean): BankMessageKind = when {
        amountMinor == null && hasBalance -> BankMessageKind.BALANCE
        amountMinor == null -> BankMessageKind.OTHER
        n.contains("smer platby: odchozi") -> BankMessageKind.OUTGOING_TRANSFER
        n.contains("smer platby: prichozi") -> BankMessageKind.INCOMING_TRANSFER
        card || listOf("platbu kartou", "platba kartou", "card payment", "paid by card").any { n.contains(it) } ->
            BankMessageKind.CARD_PAYMENT
        listOf("odesla platba", "odeslana platba", "odchozi platba", "odchozi uhrada", "odchozi prevod").any { n.contains(it) } ->
            BankMessageKind.OUTGOING_TRANSFER
        listOf("prisla platba", "prichozi platba", "prichozi uhrada", "prichozi prevod", "pripsana platba", "byla pripsana").any { n.contains(it) } ->
            BankMessageKind.INCOMING_TRANSFER
        else -> BankMessageKind.OTHER
    }

    /** Symbols are digits; "0" or "0000" means none. */
    private fun symbol(value: String?): String? =
        value?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() && it.any { c -> c != '0' } }?.trimStart('0')

    private fun cleanText(s: String): String =
        s.replace(Regex("[\\s\\u00A0\\u202F]+"), " ").trim().take(120)
}
