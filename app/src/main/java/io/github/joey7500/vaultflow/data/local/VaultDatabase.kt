package io.github.joey7500.vaultflow.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.joey7500.vaultflow.core.security.DatabaseKeyManager
import io.github.joey7500.vaultflow.data.local.dao.AccountDao
import io.github.joey7500.vaultflow.data.local.dao.CaptureLogDao
import io.github.joey7500.vaultflow.data.local.dao.CategoryDao
import io.github.joey7500.vaultflow.data.local.dao.ManualAssetDao
import io.github.joey7500.vaultflow.data.local.dao.ProcessedMessageDao
import io.github.joey7500.vaultflow.data.local.dao.RuleDao
import io.github.joey7500.vaultflow.data.local.dao.SenderDao
import io.github.joey7500.vaultflow.data.local.dao.SettingDao
import io.github.joey7500.vaultflow.data.local.dao.SnapshotDao
import io.github.joey7500.vaultflow.data.local.dao.TransactionDao
import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.CaptureLogEntity
import io.github.joey7500.vaultflow.data.local.entity.CategoryEntity
import io.github.joey7500.vaultflow.data.local.entity.ManualAssetEntity
import io.github.joey7500.vaultflow.data.local.entity.ProcessedMessageEntity
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
        ProcessedMessageEntity::class,
    ],
    version = 3,
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
    abstract fun processedMessages(): ProcessedMessageDao

    companion object {
        private const val DB_NAME = "vaultflow.db"

        /** v1 -> v2: card digits per account, used to route card payments. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN cardIdentifiers TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 -> v3: multi-source evidence, transfer aliases, overdraft, balance checks, processed e-mails. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN evidence TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN transferAliases TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN overdraftMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN lastCheckAtMillis INTEGER")
                db.execSQL("ALTER TABLE accounts ADD COLUMN lastCheckDiffMinor INTEGER")
                db.execSQL("ALTER TABLE accounts ADD COLUMN lastReportedMinor INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `processed_messages` (`key` TEXT NOT NULL, " +
                        "`processedAtMillis` INTEGER NOT NULL, `handled` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        /** Demo Mode: synthetic data in memory only, gone when the app process dies. */
        fun openInMemoryDemo(context: Context): VaultDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, VaultDatabase::class.java).build()
    }
}
