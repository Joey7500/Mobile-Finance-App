package io.github.joey7500.vaultflow.domain.budget

import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.Transaction

/** Budget vs. actual for one category in one month. Amounts are positive minor units. */
data class CategoryLine(
    val category: Category,
    val budgetMinor: Long,
    /**
     * INCOME: money received. EXPENSE / INVESTMENT: money spent, net of
     * reimbursements (an inflow tagged with an expense category lowers it).
     */
    val actualMinor: Long,
    val transactionCount: Int,
) {
    val remainingMinor: Long get() = budgetMinor - actualMinor

    /** 0..1+ fill of the progress bar. */
    val progress: Float
        get() = when {
            budgetMinor > 0 -> actualMinor.toFloat() / budgetMinor.toFloat()
            actualMinor > 0 -> 1f
            else -> 0f
        }

    val isOverBudget: Boolean
        get() = category.kind != FlowKind.INCOME && budgetMinor > 0 && actualMinor > budgetMinor
}

data class MonthSummary(
    val lines: List<CategoryLine>,
    val incomeMinor: Long,
    /** All living expenses including the Uncertain bucket, so unreviewed spend still counts. */
    val expensesMinor: Long,
    val investmentsMinor: Long,
    val uncertainCount: Int,
    val uncertainMinor: Long,
    val plannedIncomeMinor: Long,
    val plannedExpensesMinor: Long,
    val plannedInvestmentsMinor: Long,
) {
    /** Income − expenses − investments for the month (cash-flow, not balance). */
    val netCashFlowMinor: Long get() = incomeMinor - expensesMinor - investmentsMinor

    /** Budgeted outflows not spent yet: what the rest of the month is still expected to cost. */
    val remainingPlannedOutflowMinor: Long
        get() = lines
            .filter { (it.category.kind == FlowKind.EXPENSE && !it.category.isUncertain) || it.category.kind == FlowKind.INVESTMENT }
            .sumOf { maxOf(0L, it.budgetMinor - it.actualMinor) }
}

object BudgetEngine {

    /**
     * Builds the month summary. Transactions without a category (or with a
     * category id that no longer exists) land in the Uncertain bucket.
     */
    fun summarize(categories: List<Category>, transactions: List<Transaction>): MonthSummary {
        val byId = categories.associateBy { it.id }
        val uncertain = categories.firstOrNull { it.isUncertain }

        val actual = HashMap<Long, Long>()
        val counts = HashMap<Long, Int>()
        var uncertainCount = 0
        var uncertainMinor = 0L
        var orphanUncertainMinor = 0L

        for (tx in transactions) {
            val category = tx.categoryId?.let(byId::get) ?: uncertain
            val isUncertain = category == null || category.isUncertain
            if (isUncertain) {
                uncertainCount++
                uncertainMinor += -tx.amountCzkMinor
            }
            if (category == null) {
                orphanUncertainMinor += -tx.amountCzkMinor
                continue
            }
            val contribution = when (category.kind) {
                FlowKind.INCOME -> tx.amountCzkMinor
                FlowKind.EXPENSE, FlowKind.INVESTMENT -> -tx.amountCzkMinor
                FlowKind.TRANSFER -> 0L
            }
            actual[category.id] = (actual[category.id] ?: 0L) + contribution
            counts[category.id] = (counts[category.id] ?: 0) + 1
        }

        val lines = categories
            .sortedBy { it.sortOrder }
            .map { CategoryLine(it, it.monthlyBudgetMinor, actual[it.id] ?: 0L, counts[it.id] ?: 0) }

        fun sumKind(kind: FlowKind) = lines.filter { it.category.kind == kind }.sumOf { it.actualMinor }
        fun planKind(kind: FlowKind) = lines.filter { it.category.kind == kind }.sumOf { it.budgetMinor }

        return MonthSummary(
            lines = lines,
            incomeMinor = sumKind(FlowKind.INCOME),
            expensesMinor = sumKind(FlowKind.EXPENSE) + orphanUncertainMinor,
            investmentsMinor = sumKind(FlowKind.INVESTMENT),
            uncertainCount = uncertainCount,
            uncertainMinor = uncertainMinor,
            plannedIncomeMinor = planKind(FlowKind.INCOME),
            plannedExpensesMinor = planKind(FlowKind.EXPENSE),
            plannedInvestmentsMinor = planKind(FlowKind.INVESTMENT),
        )
    }
}
