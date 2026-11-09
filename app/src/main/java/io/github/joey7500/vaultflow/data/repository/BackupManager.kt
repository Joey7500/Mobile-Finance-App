package io.github.joey7500.vaultflow.data.repository

import androidx.room.withTransaction
import io.github.joey7500.vaultflow.data.local.VaultDatabase
import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.CategoryEntity
import io.github.joey7500.vaultflow.data.local.entity.ManualAssetEntity
import io.github.joey7500.vaultflow.data.local.entity.RuleEntity
import io.github.joey7500.vaultflow.data.local.entity.SenderEntity
import io.github.joey7500.vaultflow.data.local.entity.SettingEntity
import io.github.joey7500.vaultflow.data.local.entity.SnapshotEntity
import io.github.joey7500.vaultflow.data.local.entity.TransactionEntity
import io.github.joey7500.vaultflow.data.remote.Http
import io.github.joey7500.vaultflow.domain.backup.BackupCrypto
import io.github.joey7500.vaultflow.domain.backup.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class BackupPayload(
    val formatVersion: Int = 1,
    val createdAtMillis: Long,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val accounts: List<AccountEntity>,
    val rules: List<RuleEntity>,
    val manualAssets: List<ManualAssetEntity>,
    val snapshots: List<SnapshotEntity>,
    val senders: List<SenderEntity>,
    val settings: List<SettingEntity>,
)

/**
 * Password-encrypted export / import of the whole vault.
 *
 * API keys are intentionally NOT part of the backup: they stay in the device
 * Keystore and you re-enter them after restoring on a new phone. The capture
 * log (raw notification text) is not exported either.
 */
class BackupManager(private val db: VaultDatabase) {

    suspend fun export(password: CharArray): String = withContext(Dispatchers.IO) {
        val payload = BackupPayload(
            createdAtMillis = System.currentTimeMillis(),
            categories = db.categories().getAll(),
            transactions = db.transactions().getAll(),
            accounts = db.accounts().getAll(),
            rules = db.rules().getAll(),
            manualAssets = db.manualAssets().getAll(),
            snapshots = db.snapshots().getAll(),
            senders = db.senders().getAll(),
            settings = db.settings().getAll(),
        )
        val json = Http.json.encodeToString(BackupPayload.serializer(), payload)
        BackupCrypto.encrypt(json.toByteArray(Charsets.UTF_8), password)
    }

    /** Replaces ALL current data with the backup content. Throws [BackupException] on a bad file/password. */
    suspend fun import(blob: String, password: CharArray): Int = withContext(Dispatchers.IO) {
        val json = String(BackupCrypto.decrypt(blob, password), Charsets.UTF_8)
        val payload = try {
            Http.json.decodeFromString(BackupPayload.serializer(), json)
        } catch (e: Exception) {
            throw BackupException("Backup content is not readable by this app version.", e)
        }
        db.withTransaction {
            db.transactions().deleteAll()
            db.categories().deleteAll()
            db.accounts().deleteAll()
            db.rules().deleteAll()
            db.manualAssets().deleteAll()
            db.snapshots().deleteAll()
            db.senders().deleteAll()
            db.settings().deleteAll()
            db.captureLog().deleteAll()

            db.categories().insertAll(payload.categories)
            db.accounts().insertAll(payload.accounts)
            db.transactions().insertAll(payload.transactions)
            db.rules().insertAll(payload.rules)
            db.manualAssets().insertAll(payload.manualAssets)
            db.snapshots().insertAll(payload.snapshots)
            db.senders().insertAll(payload.senders)
            db.settings().insertAll(payload.settings)
        }
        payload.transactions.size
    }
}
