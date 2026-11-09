package io.github.joey7500.vaultflow.domain.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Password-based encryption for the export file, so a backup can live in
 * Google Drive / OneDrive without exposing anything:
 * PBKDF2-HMAC-SHA256 (key derivation) + AES-256-GCM (authenticated encryption).
 *
 * Format: "VFB1:" + base64( salt[16] | iv[12] | ciphertext+tag )
 */
object BackupCrypto {
    private const val MAGIC = "VFB1:"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    const val ITERATIONS = 150_000
    const val MIN_PASSWORD_LENGTH = 10

    fun encrypt(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): String {
        if (password.size < MIN_PASSWORD_LENGTH) {
            throw BackupException("Use a backup password with at least $MIN_PASSWORD_LENGTH characters.")
        }
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return MAGIC + Base64.getEncoder().encodeToString(salt + iv + ct)
    }

    fun decrypt(blob: String, password: CharArray): ByteArray {
        val trimmed = blob.trim()
        if (!trimmed.startsWith(MAGIC)) throw BackupException("Not a VaultFlow backup file.")
        val raw = try {
            Base64.getDecoder().decode(trimmed.removePrefix(MAGIC))
        } catch (e: IllegalArgumentException) {
            throw BackupException("Backup file is corrupted.", e)
        }
        if (raw.size <= SALT_LEN + IV_LEN) throw BackupException("Backup file is corrupted.")
        val salt = raw.copyOfRange(0, SALT_LEN)
        val iv = raw.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ct = raw.copyOfRange(SALT_LEN + IV_LEN, raw.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw BackupException("Wrong password, or the file was modified.", e)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
