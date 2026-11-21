package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.text.TextNormalizer

/**
 * Bank boilerplate that says nothing about WHAT was paid: "Odešla platba",
 * "Příchozí platba", "Platba kartou", "Odchozí úhrada", "Payment to 123/0800".
 * Such a text is never kept as a transaction's name when anything better exists.
 */
object GenericNames {
    private val WORDS = setOf(
        // Czech
        "platba", "platby", "platbu", "platbe", "kartou", "karta", "karty", "karte", "odchozi", "prichozi",
        "odesla", "odeslana", "odeslano", "odeslani", "prisla", "prislo", "pripsana", "pripsano", "pripsani",
        "provedena", "provedeno", "uhrada", "uhrady", "uhradu", "transakce", "transakci", "prevod", "prevodem",
        "pohyb", "pohyby", "na", "z", "ze", "uctu", "ucet", "uctem", "vas", "vasem", "nova", "novy", "nove",
        "informace", "oznameni", "notifikace", "zustatek", "vyber", "bankomat", "bankomatu", "vraceni",
        "potvrzeni", "o", "u", "inkaso", "trvaly", "prikaz", "sepa", "okamzita", "okamzity", "zahranicni",
        // English
        "payment", "card", "transaction", "incoming", "outgoing", "transfer", "received", "sent", "refund",
        "atm", "new", "account", "from", "to", "paid", "purchase", "withdrawal",
    )

    /** True when [name] consists only of boilerplate words and numbers (or is empty). */
    fun isGeneric(name: String): Boolean {
        val words = TextNormalizer.lowerAscii(name).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.isEmpty() || words.all { it in WORDS || it.all(Char::isDigit) }
    }
}
