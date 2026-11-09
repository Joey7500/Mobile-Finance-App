package io.github.joey7500.vaultflow.domain.text

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("[\\s\\u00A0\\u202F]+")

    /** "Kavárna U Parku" -> "kavarna u parku" */
    fun lowerAscii(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
            .lowercase(Locale.ROOT).replace(SPACES, " ").trim()

    /** "Kavárna U Parku" -> "KAVARNA U PARKU" (used for merchant matching). */
    fun merchantKey(s: String): String = lowerAscii(s).uppercase(Locale.ROOT)

    /** Stable short hash used to recognise an identical notification posted twice. */
    fun fingerprint(vararg parts: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }
}
