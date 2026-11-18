package io.github.joey7500.vaultflow.domain.budget

import io.github.joey7500.vaultflow.domain.budget.OverspendLevel.CRITICAL
import io.github.joey7500.vaultflow.domain.budget.OverspendLevel.NONE
import io.github.joey7500.vaultflow.domain.budget.OverspendLevel.WARNING
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class OverspendTest {
    private val threshold = Overspend.DEFAULT_RED_THRESHOLD_MINOR
    private fun expense(budget: Long, actual: Long, t: Long = threshold) = Overspend.level(FlowKind.EXPENSE, budget, actual, t)

    @Test fun `default red threshold is 1 000 CZK`() {
        assertEquals(1_000_00L, Overspend.DEFAULT_RED_THRESHOLD_MINOR)
    }

    @Test fun `within budget is never coloured`() {
        assertEquals(NONE, expense(6_000_00, 0))
        assertEquals(NONE, expense(6_000_00, 5_999_99))
        assertEquals(NONE, expense(6_000_00, 6_000_00)) // exactly on budget is not over
    }

    @Test fun `over by less than the threshold is a warning`() {
        assertEquals(WARNING, expense(6_000_00, 6_000_01)) // 1 haler over
        assertEquals(WARNING, expense(6_000_00, 6_500_00))
        assertEquals(WARNING, expense(6_000_00, 6_999_99)) // 1 haler below the threshold
    }

    @Test fun `over by the threshold or more is critical`() {
        assertEquals(CRITICAL, expense(6_000_00, 7_000_00)) // exactly 1 000 CZK over
        assertEquals(CRITICAL, expense(6_000_00, 7_000_01))
        assertEquals(CRITICAL, expense(6_000_00, 25_000_00))
    }

    @Test fun `no budget set means no overspend colour`() {
        assertEquals(NONE, expense(0, 3_000_00))
        assertEquals(NONE, expense(-1, 3_000_00))
    }

    @Test fun `income is never over budget`() {
        assertEquals(NONE, Overspend.level(FlowKind.INCOME, 45_000_00, 60_000_00, threshold))
    }

    @Test fun `investments follow the same rule as expenses`() {
        assertEquals(WARNING, Overspend.level(FlowKind.INVESTMENT, 10_000_00, 10_500_00, threshold))
        assertEquals(CRITICAL, Overspend.level(FlowKind.INVESTMENT, 10_000_00, 11_000_00, threshold))
    }

    @Test fun `custom threshold moves the red line`() {
        assertEquals(WARNING, expense(2_000_00, 4_000_00, t = 2_500_00))
        assertEquals(CRITICAL, expense(2_000_00, 4_500_00, t = 2_500_00))
        assertEquals(CRITICAL, expense(2_000_00, 2_100_00, t = 50_00))
    }

    @Test fun `zero or negative threshold makes every overspend red`() {
        assertEquals(CRITICAL, expense(2_000_00, 2_000_01, t = 0))
        assertEquals(CRITICAL, expense(2_000_00, 2_000_01, t = -500_00))
        assertEquals(NONE, expense(2_000_00, 2_000_00, t = 0))
    }

    @Test fun `category line level agrees with isOverBudget`() {
        val food = Category(2, CategoryKeys.GROCERIES, "Groceries", "Food", FlowKind.EXPENSE, 6_000_00, 1)
        val salary = Category(1, CategoryKeys.SALARY, "Salary", "Income", FlowKind.INCOME, 45_000_00, 0)
        fun tx(amount: Long, cat: Long) = Transaction(timestampMillis = 1, amountMinor = amount, merchant = "x", categoryId = cat)
        val lines = BudgetEngine.summarize(
            listOf(salary, food),
            listOf(tx(-6_400_00, 2), tx(50_000_00, 1)),
        ).lines
        val foodLine = lines.first { it.category.id == 2L }
        val salaryLine = lines.first { it.category.id == 1L }
        assertEquals(WARNING, foodLine.overspendLevel(threshold))
        assertEquals(foodLine.isOverBudget, foodLine.overspendLevel(threshold) != NONE)
        assertEquals(NONE, salaryLine.overspendLevel(threshold))
        assertEquals(CRITICAL, foodLine.overspendLevel(300_00))
    }
}
