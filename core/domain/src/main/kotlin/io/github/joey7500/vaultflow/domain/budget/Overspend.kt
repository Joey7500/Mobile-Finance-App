package io.github.joey7500.vaultflow.domain.budget

import io.github.joey7500.vaultflow.domain.model.FlowKind

/** How bad an over-budget category is; drives amber (warning) vs red (critical). */
enum class OverspendLevel {
    /** Within budget, no budget set, or an income category. */
    NONE,

    /** Over budget by less than the red threshold. */
    WARNING,

    /** Over budget by the red threshold or more. */
    CRITICAL,
}

object Overspend {
    /** Default: over by 1 000 Kč or more turns red. */
    const val DEFAULT_RED_THRESHOLD_MINOR: Long = 1_000_00L

    /**
     * Over by less than [redThresholdMinor] = WARNING, by [redThresholdMinor] or more = CRITICAL.
     * A threshold of 0 (or below) makes every overspend red. Income is never "over":
     * receiving more than planned is good news.
     */
    fun level(kind: FlowKind, budgetMinor: Long, actualMinor: Long, redThresholdMinor: Long): OverspendLevel {
        if (kind == FlowKind.INCOME || budgetMinor <= 0) return OverspendLevel.NONE
        val over = actualMinor - budgetMinor
        return when {
            over <= 0 -> OverspendLevel.NONE
            over >= redThresholdMinor.coerceAtLeast(0) -> OverspendLevel.CRITICAL
            else -> OverspendLevel.WARNING
        }
    }
}

/** Same rules as [CategoryLine.isOverBudget], split into warning / critical. */
fun CategoryLine.overspendLevel(redThresholdMinor: Long): OverspendLevel =
    Overspend.level(category.kind, budgetMinor, actualMinor, redThresholdMinor)
