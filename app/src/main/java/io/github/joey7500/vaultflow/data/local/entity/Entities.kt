package io.github.joey7500.vaultflow.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Entities are @Serializable so the encrypted backup can export them verbatim.

@Serializable
@Entity(tableName = "categories", indices = [Index(value = ["key"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String?,
    val name: String,
    val groupName: String,
    val kind: String,
    val monthlyBudgetMinor: Long,
    val sortOrder: Int,
    val archived: Boolean = false,
)

@Serializable
@Entity(
    tableName = "transactions",
    indices = [Index("timestampMillis"), Index("accountId"), Index("categoryId"), Index("fingerprint")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val amountMinor: Long,
    val currency: String,
    val amountCzkMinor: Long,
    val merchant: String,
    val accountId: Long?,
    val categoryId: Long?,
    val source: String,
    val status: String,
    val confidence: Double,
    val note: String,
    val sourcePackage: String?,
    val fingerprint: String?,
)

@Serializable
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val anchorBalanceMinor: Long,
    val anchorDateMillis: Long,
    val isLiquid: Boolean,
    /** Newline-separated fragments of your own account numbers (encrypted DB only). */
    val ownIdentifiers: String,
    val sortOrder: Int = 0,
)

@Serializable
@Entity(tableName = "rules", indices = [Index(value = ["pattern"], unique = true)])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val matchType: String,
    val categoryId: Long,
    val createdAtMillis: Long,
)

@Serializable
@Entity(tableName = "manual_assets")
data class ManualAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val valueMinor: Long,
    val updatedAtMillis: Long,
    val note: String = "",
)

/** One net-worth data point per calendar day. */
@Serializable
@Entity(tableName = "net_worth_snapshots")
data class SnapshotEntity(
    @PrimaryKey val epochDay: Long,
    val totalMinor: Long,
    val trading212Minor: Long,
    val goldMinor: Long,
    val banksMinor: Long,
    val buildingSavingsMinor: Long,
    val otherMinor: Long,
    val createdAtMillis: Long,
)

/** An app whose notifications contained money amounts. Only the package name is stored until you enable it. */
@Serializable
@Entity(tableName = "senders")
data class SenderEntity(
    @PrimaryKey val packageName: String,
    val enabled: Boolean,
    val accountId: Long?,
    val seenCount: Int,
    val lastSeenMillis: Long,
)

/** What the listener did with a notification. Purged automatically after a few days. */
@Serializable
@Entity(tableName = "capture_log", indices = [Index("postedAtMillis")])
data class CaptureLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postedAtMillis: Long,
    val packageName: String,
    val title: String,
    val text: String,
    val outcome: String,
    val transactionId: Long?,
)

@Serializable
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Result row for per-account balance deltas. */
data class AccountDelta(val accountId: Long, val total: Long)
