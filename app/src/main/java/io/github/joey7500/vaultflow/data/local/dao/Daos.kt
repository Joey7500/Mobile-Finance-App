package io.github.joey7500.vaultflow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import io.github.joey7500.vaultflow.data.local.entity.AccountDelta
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
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(items: List<CategoryEntity>): List<Long>

    @Insert
    suspend fun insert(item: CategoryEntity): Long

    @Update
    suspend fun update(item: CategoryEntity)

    @Query("UPDATE categories SET monthlyBudgetMinor = :minor WHERE id = :id")
    suspend fun setBudget(id: Long, minor: Long)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE timestampMillis >= :from AND timestampMillis < :to ORDER BY timestampMillis DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestampMillis >= :since ORDER BY timestampMillis DESC")
    suspend fun getSince(since: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY timestampMillis")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Insert
    suspend fun insert(item: TransactionEntity): Long

    @Insert
    suspend fun insertAll(items: List<TransactionEntity>)

    @Update
    suspend fun update(item: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE transactions SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    /** Sum of transactions booked after each account's anchor date. */
    @Query(
        """
        SELECT t.accountId AS accountId, COALESCE(SUM(t.amountCzkMinor), 0) AS total
        FROM transactions t INNER JOIN accounts a ON t.accountId = a.id
        WHERE t.timestampMillis > a.anchorDateMillis
        GROUP BY t.accountId
        """,
    )
    fun observeAccountDeltas(): Flow<List<AccountDelta>>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT * FROM transactions WHERE accountId = :accountId AND timestampMillis >= :since ORDER BY timestampMillis DESC")
    fun observeForAccount(accountId: Long, since: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COALESCE(SUM(amountCzkMinor), 0) FROM transactions WHERE accountId = :accountId AND timestampMillis > :after AND timestampMillis <= :upTo")
    suspend fun sumForAccount(accountId: Long, after: Long, upTo: Long): Long

    @Query("SELECT COUNT(*) FROM transactions WHERE timestampMillis >= :from AND timestampMillis < :to AND evidence LIKE '%EMAIL%'")
    fun observeEmailConfirmed(from: Long, to: Long): Flow<Int>
}

@Dao
interface ProcessedMessageDao {
    @Query("SELECT * FROM processed_messages WHERE `key` = :key")
    suspend fun get(key: String): ProcessedMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: ProcessedMessageEntity)

    @Query("DELETE FROM processed_messages WHERE processedAtMillis < :before")
    suspend fun purgeBefore(before: Long)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    suspend fun getAll(): List<AccountEntity>

    @Upsert
    suspend fun upsert(item: AccountEntity): Long

    @Insert
    suspend fun insertAll(items: List<AccountEntity>)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY pattern")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules")
    suspend fun getAll(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RuleEntity): Long

    @Insert
    suspend fun insertAll(items: List<RuleEntity>)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rules")
    suspend fun deleteAll()
}

@Dao
interface ManualAssetDao {
    @Query("SELECT * FROM manual_assets ORDER BY id")
    fun observeAll(): Flow<List<ManualAssetEntity>>

    @Query("SELECT * FROM manual_assets ORDER BY id")
    suspend fun getAll(): List<ManualAssetEntity>

    @Upsert
    suspend fun upsert(item: ManualAssetEntity): Long

    @Insert
    suspend fun insertAll(items: List<ManualAssetEntity>)

    @Query("DELETE FROM manual_assets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM manual_assets")
    suspend fun deleteAll()
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM net_worth_snapshots ORDER BY epochDay")
    fun observeAll(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM net_worth_snapshots ORDER BY epochDay")
    suspend fun getAll(): List<SnapshotEntity>

    @Upsert
    suspend fun upsert(item: SnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SnapshotEntity>)

    @Query("DELETE FROM net_worth_snapshots")
    suspend fun deleteAll()
}

@Dao
interface SenderDao {
    @Query("SELECT * FROM senders ORDER BY enabled DESC, lastSeenMillis DESC")
    fun observeAll(): Flow<List<SenderEntity>>

    @Query("SELECT * FROM senders")
    suspend fun getAll(): List<SenderEntity>

    @Query("SELECT * FROM senders WHERE packageName = :pkg")
    suspend fun get(pkg: String): SenderEntity?

    @Upsert
    suspend fun upsert(item: SenderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SenderEntity>)

    @Query("DELETE FROM senders WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    @Query("DELETE FROM senders")
    suspend fun deleteAll()
}

@Dao
interface CaptureLogDao {
    @Query("SELECT * FROM capture_log ORDER BY postedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CaptureLogEntity>>

    @Insert
    suspend fun insert(item: CaptureLogEntity)

    @Query("DELETE FROM capture_log WHERE postedAtMillis < :before")
    suspend fun purgeBefore(before: Long)

    @Query("DELETE FROM capture_log")
    suspend fun deleteAll()
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingEntity>

    @Upsert
    suspend fun put(item: SettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SettingEntity>)

    @Query("DELETE FROM settings")
    suspend fun deleteAll()
}
