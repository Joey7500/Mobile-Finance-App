package io.github.joey7500.vaultflow.domain.budget

import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.Transaction

enum class ReserveLevel {
    /** Cash stays above the floor even after the rest of this month's budget is spent. */
    HEALTHY,

    /** Cash is above the floor now, but spending the remaining budget would dip into it. */
    AT_RISK,

    /** Liquid cash is already below the floor. */
    BREACHED,
}

/**
 * The reserve is a BALANCE constraint, not a monthly expense:
 *
 *   safeToSpend = liquidCash − reserveFloor − remainingPlannedOutflow
 *
 * Expected income that has not arrived yet is deliberately NOT counted, so the
 * number errs on the side of caution.
 */
data class ReserveStatus(
    val liquidMinor: Long,
    val floorMinor: Long,
    val remainingPlannedOutflowMinor: Long,
) {
    /** Cash above the floor right now. */
    val headroomMinor: Long get() = liquidMinor - floorMinor

    /** What you can spend or invest beyond your budget without touching the reserve. */
    val safeToSpendMinor: Long get() = liquidMinor - floorMinor - remainingPlannedOutflowMinor

    val level: ReserveLevel
        get() = when {
            liquidMinor < floorMinor -> ReserveLevel.BREACHED
            safeToSpendMinor < 0 -> ReserveLevel.AT_RISK
            else -> ReserveLevel.HEALTHY
        }
}

object ReserveCalculator {
    const val DEFAULT_FLOOR_MINOR = 5_000_00L

    /** Anchor balance plus every transaction booked to the account after the anchor moment. */
    fun balanceOf(account: Account, transactions: List<Transaction>): Long =
        account.anchorBalanceMinor + transactions
            .filter { it.accountId == account.id && it.timestampMillis > account.anchorDateMillis }
            .sumOf { it.amountCzkMinor }

    fun liquidTotal(accounts: List<Account>, balances: Map<Long, Long>): Long =
        accounts.filter { it.isLiquid }.sumOf { balances[it.id] ?: it.anchorBalanceMinor }

    fun status(liquidMinor: Long, floorMinor: Long, remainingPlannedOutflowMinor: Long) =
        ReserveStatus(liquidMinor, floorMinor, remainingPlannedOutflowMinor)
}
