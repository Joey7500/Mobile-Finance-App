package io.github.joey7500.vaultflow.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.joey7500.vaultflow.core.security.DatabaseKeyManager
import io.github.joey7500.vaultflow.data.local.dao.AccountDao
import io.github.joey7500.vaultflow.data.local.dao.CaptureLogDao
import io.github.joey7500.vaultflow.data.local.dao.CategoryDao
import io.github.joey7500.vaultflow.data.local.dao.ManualAssetDao
import io.github.joey7500.vaultflow.data.local.dao.RuleDao
import io.github.joey7500.vaultflow.data.local.dao.SenderDao
import io.github.joey7500.vaultflow.data.local.dao.SettingDao
import io.github.joey7500.vaultflow.data.local.dao.SnapshotDao
import io.github.joey7500.vaultflow.data.local.dao.TransactionDao
import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.CaptureLogEntity
import io.github.joey7500.vaultflow.data.local.entity.CategoryEntity
import io.github.joey7500.vaultflow.data.local.entity.ManualAssetEntity
import io.github.joey7500.vaultflow.data.local.entity.RuleEntity
import io.github.joey7500.vaultflow.data.local.entity.SenderEntity
import io.github.joey7500.vaultflow.data.local.entity.SettingEntity
import io.github.joey7500.vaultflow.data.local.entity.SnapshotEntity
import io.github.joey7500.vaultflow.data.local.entity.TransactionEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CategoryEntity::class,
        TransactionEntity::class,
        AccountEntity::class,
        RuleEntity::class,
        ManualAssetEntity::class,
        SnapshotEntity::class,
        SenderEntity::class,
        CaptureLogEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun categories(): CategoryDao
    abstract fun transactions(): TransactionDao
    abstract fun accounts(): AccountDao
    abstract fun rules(): RuleDao
    abstract fun manualAssets(): ManualAssetDao
    abstract fun snapshots(): SnapshotDao
    abstract fun senders(): SenderDao
    abstract fun captureLog(): CaptureLogDao
    abstract fun settings(): SettingDao

    companion object {
        private const val DB_NAME = "vaultflow.db"

        /**
         * The real vault: SQLCipher (AES-256) encrypted, keyed by a passphrase
         * wrapped in the hardware Keystore.
         *
         * There is deliberately NO fallbackToDestructiveMigration(): a schema
         * change without a migration must crash in development, never silently
         * wipe your financial history. Add a Migration for every version bump.
         */
        fun openEncrypted(context: Context): VaultDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DatabaseKeyManager(context).passphrase())
            return Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .build()
        }

        /** Demo Mode: synthetic data in memory only, gone when the app process dies. */
        fun openInMemoryDemo(context: Context): VaultDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, VaultDatabase::class.java).build()
    }
}
