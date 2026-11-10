package io.github.joey7500.vaultflow.email

import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

data class FetchedEmail(
    val messageId: String,
    /** Lower-case sender address, e.g. "ceskasporitelna@csas.cz". */
    val from: String,
    val subject: String,
    val receivedAtMillis: Long,
    val text: String,
)

class MailAuthException(message: String) : Exception(message)

/**
 * Read-only IMAP access to a Gmail mailbox with an App Password
 * (imap.gmail.com:993, TLS). Opens the folder READ_ONLY: nothing is marked,
 * moved or deleted, and nothing is ever sent.
 */
object ImapMailFetcher {
    private const val HOST = "imap.gmail.com"

    /** Blocking: call from a background thread. */
    fun fetch(address: String, appPassword: String, sinceMillis: Long, folderName: String = "INBOX", max: Int = 300): List<FetchedEmail> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", HOST)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        }
        val store = Session.getInstance(props).getStore("imaps")
        try {
            store.connect(HOST, address.trim(), appPassword.replace(" ", ""))
        } catch (e: AuthenticationFailedException) {
            throw MailAuthException("Gmail rejected the login. Use the 16-letter App Password, not your Google password.")
        }
        try {
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            try {
                val messages = folder.search(ReceivedDateTerm(ComparisonTerm.GE, Date(sinceMillis)))
                return messages.takeLast(max).mapNotNull { m -> runCatching { toFetched(m) }.getOrNull() }
                    .filter { it.receivedAtMillis >= sinceMillis }
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    /** Connects and counts recent messages: used by the "Test" button. */
    fun test(address: String, appPassword: String): Int =
        fetch(address, appPassword, System.currentTimeMillis() - 3L * 24 * 3600 * 1000).size

    private fun toFetched(m: Message): FetchedEmail {
        val from = (m.from?.firstOrNull() as? InternetAddress)?.address?.lowercase().orEmpty()
        val received = (m.receivedDate ?: m.sentDate)?.time ?: System.currentTimeMillis()
        val id = m.getHeader("Message-ID")?.firstOrNull() ?: "$from|$received|${m.subject}"
        return FetchedEmail(id, from, m.subject.orEmpty(), received, MailText.extract(m).take(8_000))
    }
}

/** Plain text of an e-mail: prefers text/plain, falls back to stripped HTML. */
object MailText {
    fun extract(part: Part): String = when {
        part.isMimeType("text/plain") -> part.content?.toString().orEmpty()
        part.isMimeType("text/html") -> htmlToText(part.content?.toString().orEmpty())
        part.isMimeType("multipart/alternative") -> {
            val mp = part.content as Multipart
            val parts = (0 until mp.count).map { mp.getBodyPart(it) }
            parts.firstOrNull { it.isMimeType("text/plain") }?.let(::extract)
                ?: parts.firstOrNull()?.let(::extract).orEmpty()
        }
        part.isMimeType("multipart/*") -> {
            val mp = part.content as Multipart
            (0 until mp.count).joinToString("\n") { extract(mp.getBodyPart(it)) }
        }
        part.isMimeType("message/rfc822") -> (part.content as? Part)?.let(::extract).orEmpty()
        else -> ""
    }

    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>|</h\\d>"), "\n")
        .replace(Regex("(?i)</td>"), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty() }
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
        .trim()
}
