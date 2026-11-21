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
    const val CLOTHING = "clothing"
    const val DRUGSTORE = "drugstore"
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

enum class TransactionSource {
    MANUAL, GOOGLE_WALLET, BANK_NOTIFICATION, IMPORT, DEMO,

    /** Bank e-mail (arrives ~6 min after the payment; carries the settled amount and the balance). */
    EMAIL,

    /** Expected counterpart of a transfer between your own accounts, until the other bank confirms it. */
    INFERRED,
}

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
    /** Every source that reported this transaction (Wallet, bank app, e-mail...). */
    val evidence: Set<TransactionSource> = setOf(source),
    /** Fingerprints of EVERY message fused into this record: a replay of any of them is recognised. */
    val fingerprints: Set<String> = setOfNotNull(fingerprint),
    /**
     * Earliest possible time when only the DATE is known (a bank e-mail saying "ze dne 10. 11.").
     * The record then happened somewhere in [earliestMillis, timestampMillis]. Null = exact time.
     */
    val earliestMillis: Long? = null,
    /** The payer's own words from the bank e-mail ("padel 3. 12."): what the payment was for. */
    val description: String = "",
    /** The other party's account as the bank wrote it ("7654321/9998"). Stored only in the encrypted DB. */
    val counterpartyAccount: String? = null,
    /** Variable / constant / specific symbol, e.g. "VS 1126". */
    val symbols: String = "",
    /** Which report the [merchant] name came from (null: older record, derived from [evidence]). */
    val nameSource: TransactionSource? = null,
    /** You picked the category yourself: no later report or rule may change it. */
    val categoryLocked: Boolean = false,
    /**
     * Booked from a push for an account whose bank e-mails are the main source: the name and
     * category are provisional until the e-mail arrives (or the wait times out).
     */
    val awaitingEmail: Boolean = false,
) {
    /** This record already contains the message with fingerprint [fp]. */
    fun knows(fp: String?): Boolean = fp != null && (fp == fingerprint || fp in fingerprints)

    val isConfirmedByBank: Boolean
        get() = TransactionSource.EMAIL in evidence || TransactionSource.BANK_NOTIFICATION in evidence
}

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
    /**
     * Last digits of payment cards linked to this account ("1111"). A card
     * payment notification mentioning them is booked to this account.
     */
    val cardIdentifiers: List<String> = emptyList(),
    /**
     * Merchant names that mean "money goes to THIS account", e.g. "REVOLUT" for
     * a card top-up of your Revolut. Such payments become internal transfers.
     */
    val transferAliases: List<String> = emptyList(),
    /** Overdraft (kontokorent) included in the bank's "available" figure. */
    val overdraftMinor: Long = 0,
    /** When the bank last reported this account's balance, and how far the app was off. */
    val lastCheckAtMillis: Long? = null,
    val lastCheckDiffMinor: Long? = null,
    /** Whether this account's bank e-mails are the main source of names and categories. */
    val emailMode: EmailMode = EmailMode.AUTO,
)

/**
 * "Bank e-mails are the main source" for an account. A push is booked at once either way;
 * with e-mails as the main source it is marked "awaiting e-mail" until the e-mail names it.
 */
enum class EmailMode {
    /** Decided from the bank whose e-mails are mapped to the account (ČS: all, KB: transfers, none: off). */
    AUTO,

    /** Every payment is also e-mailed (Česká spořitelna). */
    ALL,

    /** Transfers are e-mailed, card payments are not (Komerční banka). */
    TRANSFERS,

    /** No e-mails (Revolut). */
    OFF,
}

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

/** Where a rule came from; decides its priority and confidence. */
enum class RuleOrigin {
    /** You ticked "Always classify this merchant like this". Highest priority, always trusted. */
    USER,

    /** Learned from your manual corrections without "always": trusted once you repeated it. */
    LEARNED,
}

/** A classification rule: yours ("always") or learned from your corrections. */
data class ClassificationRule(
    val id: Long,
    /** Normalised (upper-case, no diacritics) pattern. */
    val pattern: String,
    val match: RuleMatch,
    val categoryId: Long,
    val createdAtMillis: Long,
    val origin: RuleOrigin = RuleOrigin.USER,
    /** How many times you filed this merchant under this category (LEARNED rules). */
    val hits: Int = 1,
)
