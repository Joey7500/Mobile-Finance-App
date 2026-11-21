package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.EmailMode
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource

/**
 * "E-mail first": for an account whose bank e-mails every payment (Česká spořitelna), the
 * push is booked AT ONCE (balance, budget and safe-to-spend react immediately) but its name
 * and category are provisional. The bank e-mail (~6 min later, sometimes hours) then names
 * the payment: the shop or counterparty, the payment message, the counterparty account.
 * If no e-mail comes within the wait (default 3 h), the push's version stays final.
 */
object EmailFirst {
    const val HOUR_MS: Long = 3600L * 1000
    const val DEFAULT_WAIT_MS: Long = 3 * HOUR_MS
    val WAIT_CHOICES_HOURS = listOf(1, 3, 6, 12)

    /** AUTO: ČS e-mails every payment, KB (and banks we do not know) at least the transfers, no e-mail sender: off. */
    fun autoMode(mailSenderDomains: Collection<String>): EmailMode = when {
        mailSenderDomains.isEmpty() -> EmailMode.OFF
        mailSenderDomains.any { it == "csas.cz" || it.endsWith(".csas.cz") } -> EmailMode.ALL
        else -> EmailMode.TRANSFERS
    }

    fun resolve(mode: EmailMode, mailSenderDomains: Collection<String>): EmailMode =
        if (mode == EmailMode.AUTO) autoMode(mailSenderDomains) else mode

    /** Will the bank e-mail this payment? */
    fun expectsEmail(mode: EmailMode?, cardPayment: Boolean): Boolean = when (mode) {
        EmailMode.ALL -> true
        EmailMode.TRANSFERS -> !cardPayment
        else -> false
    }

    /** Still waiting for its e-mail (shows the badge, stays out of Quick review). */
    fun isWaiting(t: Transaction, nowMillis: Long, waitMs: Long): Boolean =
        t.awaitingEmail && TransactionSource.EMAIL !in t.evidence && nowMillis - t.timestampMillis < waitMs

    /** Marked as waiting, but the e-mail came or the wait is over: the record is final. */
    fun isExpired(t: Transaction, nowMillis: Long, waitMs: Long): Boolean =
        t.awaitingEmail && !isWaiting(t, nowMillis, waitMs)

    private val NUMBERISH = Regex("""\S*\d\S*""")
    private val PUNCT_ONLY = Regex("""(?<!\S)[^\p{L}\s]+(?!\S)""")

    /** "padel 3. 12." -> "padel": dates, amounts, symbols and lone punctuation removed. */
    fun messageWords(message: String): String =
        message.replace(NUMBERISH, " ").replace(PUNCT_ONLY, " ").replace(Regex("\\s+"), " ").trim().trim('.', ',', ';', ':', '-')

    /**
     * The payment message as a name when the bank names no shop or person:
     * "padel 3. 12." -> "Padel", "nájem listopad" -> "Nájem listopad". Null when nothing is left.
     */
    fun nameFromMessage(message: String): String? {
        val words = messageWords(message).take(40).trim()
        if (words.count(Char::isLetter) < 2 || GenericNames.isGeneric(words)) return null
        return words.replaceFirstChar { it.uppercaseChar() }
    }
}
