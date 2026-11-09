package io.github.joey7500.vaultflow.core.security

import android.content.Context
import android.util.Base64

/**
 * The in-app API key vault. Secrets (Trading 212 key + secret) are typed or
 * pasted once on the phone, encrypted with a hardware Keystore key and stored
 * in private app storage. They never appear in source code, Gradle files,
 * BuildConfig, logs or backups.
 */
class SecretVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    fun get(name: String): String? = runCatching {
        prefs.getString(name, null)?.let { String(cipher.decrypt(Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }
    }.getOrNull()

    fun put(name: String, value: String?) {
        val editor = prefs.edit()
        if (value.isNullOrBlank()) {
            editor.remove(name)
        } else {
            val enc = cipher.encrypt(value.trim().toByteArray(Charsets.UTF_8))
            editor.putString(name, Base64.encodeToString(enc, Base64.NO_WRAP))
        }
        editor.apply()
    }

    fun has(name: String): Boolean = prefs.contains(name)

    fun clearAll() = prefs.edit().clear().apply()

    companion object {
        const val T212_API_KEY = "t212_api_key"
        const val T212_API_SECRET = "t212_api_secret"
        private const val PREFS = "vault_secrets"
        private const val KEY_ALIAS = "vaultflow_secret_vault_key"
    }
}
