package io.github.joey7500.vaultflow.core.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Provides the SQLCipher passphrase. A random 256-bit passphrase is generated
 * once, wrapped (encrypted) with a hardware Keystore key and stored in private
 * app storage. Only the wrapped form ever touches the disk.
 *
 * Design note: the Keystore key is intentionally NOT bound to biometric
 * authentication. The notification listener must be able to write captured
 * transactions while the app UI is locked; the biometric prompt protects the
 * UI, the Keystore protects the data at rest.
 */
class DatabaseKeyManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    @Synchronized
    fun passphrase(): ByteArray {
        prefs.getString(PREF_WRAPPED, null)?.let { wrapped ->
            return cipher.decrypt(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = cipher.encrypt(raw)
        prefs.edit().putString(PREF_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
        return raw
    }

    companion object {
        private const val PREFS = "vault_db_key"
        private const val PREF_WRAPPED = "wrapped_passphrase"
        private const val KEY_ALIAS = "vaultflow_db_wrapping_key"
    }
}
