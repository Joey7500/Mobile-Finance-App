package io.github.joey7500.vaultflow.domain.model

/** How a category affects the monthly cash-flow equation. */
enum class FlowKind {
    /** Money coming in (salary, gifts, side income). */
    INCOME,

    /** Money spent (rent, food, fun...). Inflows into an expense category count as reimbursements. */
    EXPENSE,

    /** Money moved to wealth building (broker, building savings). Not "spent", but not free cash either. */
    INVESTMENT,

    /** Movement between your own accounts. Excluded from income and expense totals. */
    TRANSFER,
}

/** Stable keys for categories the app's logic needs to find regardless of their display name. */
object CategoryKeys {
    const val SALARY = "salary"
    const val GIFTS = "gifts"
    const val OTHER_INCOME = "other_income"
    const val RENT = "rent"
    const val SUBSCRIPTIONS = "subscriptions"
    const val GROCERIES = "groceries"
    const val EATING_OUT = "eating_out"
    const val TRAVELLING = "travelling"
    const val FUN = "fun"
    const val HOLIDAY = "holiday"
    const val OTHER_EXPENSES = "other_expenses"
    const val UNCERTAIN = "uncertain"
    const val INVESTMENTS = "investments"
    const val INTERNAL_TRANSFER = "internal_transfer"
}

data class Category(
    val id: Long,
    val key: String?,
    val name: String,
    val group: String,
    val kind: FlowKind,
    /** Monthly budget in minor units (positive). For INCOME categories this is the expected income. */
    val monthlyBudgetMinor: Long,
    val sortOrder: Int,
) {
    val isUncertain: Boolean get() = key == CategoryKeys.UNCERTAIN
}

enum class TransactionSource { MANUAL, GOOGLE_WALLET, BANK_NOTIFICATION, IMPORT, DEMO }

enum class TransactionStatus {
    /** Seen only from a fast but approximate source (e.g. Wallet FX estimate). */
    PENDING,
    SETTLED,
}

data class Transaction(
    val id: Long = 0,
    val timestampMillis: Long,
    /** Signed amount in the original currency's minor units. Negative = money out. */
    val amountMinor: Long,
    val currency: String = "CZK",
    /** Signed amount converted to CZK minor units. Equals [amountMinor] for CZK. */
    val amountCzkMinor: Long = amountMinor,
    val merchant: String,
    val accountId: Long? = null,
    /** Null means "not categorised yet" and is treated as Uncertain. */
    val categoryId: Long? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val status: TransactionStatus = TransactionStatus.SETTLED,
    val confidence: Double = 1.0,
    val note: String = "",
    /** Package that produced the notification, if captured automatically. */
    val sourcePackage: String? = null,
    /** Hash of the raw notification text, used to drop exact re-posts. */
    val fingerprint: String? = null,
)

enum class AccountType { BANK, CASH, SAVINGS }

data class Account(
    val id: Long,
    val name: String,
    val type: AccountType,
    /** Balance you confirmed on [anchorDateMillis]; later transactions are applied on top. */
    val anchorBalanceMinor: Long,
    val anchorDateMillis: Long,
    /** Liquid accounts count towards the reserve floor. */
    val isLiquid: Boolean = true,
    /**
     * Fragments (e.g. the last digits of your own account numbers) that identify
     * transfers between your own accounts. Stored only in the encrypted DB.
     */
    val ownIdentifiers: List<String> = emptyList(),
)

/** A user-maintained asset with a manually entered value (building savings, cash at home, ...). */
data class ManualAsset(
    val id: Long,
    val name: String,
    val kind: ManualAssetKind,
    val valueMinor: Long,
    val updatedAtMillis: Long,
    val note: String = "",
)

enum class ManualAssetKind { BUILDING_SAVINGS, OTHER }

enum class RuleMatch { CONTAINS, EXACT }

/** A learned or user-written classification rule (Tier 1). */
data class ClassificationRule(
    val id: Long,
    /** Normalised (upper-case, no diacritics) pattern. */
    val pattern: String,
    val match: RuleMatch,
    val categoryId: Long,
    val createdAtMillis: Long,
)
