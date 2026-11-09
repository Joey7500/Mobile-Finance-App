package io.github.joey7500.vaultflow.domain.budget

import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.CategoryKeys
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetEngineTest {
    private val salary = Category(1, CategoryKeys.SALARY, "Salary", "Income", FlowKind.INCOME, 45_000_00, 0)
    private val food = Category(2, CategoryKeys.GROCERIES, "Groceries", "Food", FlowKind.EXPENSE, 6_000_00, 1)
    private val eating = Category(3, CategoryKeys.EATING_OUT, "Eating out", "Food", FlowKind.EXPENSE, 2_000_00, 2)
    private val uncertain = Category(4, CategoryKeys.UNCERTAIN, "Uncertain", "Other", FlowKind.EXPENSE, 0, 3)
    private val invest = Category(5, CategoryKeys.INVESTMENTS, "Investments", "Wealth", FlowKind.INVESTMENT, 10_000_00, 4)
    private val transfer = Category(6, CategoryKeys.INTERNAL_TRANSFER, "Internal", "Transfers", FlowKind.TRANSFER, 0, 5)
    private val cats = listOf(salary, food, eating, uncertain, invest, transfer)

    private fun tx(amount: Long, cat: Long?) = Transaction(timestampMillis = 1, amountMinor = amount, merchant = "x", categoryId = cat)

    @Test fun `totals and net cash flow`() {
        val s = BudgetEngine.summarize(
            cats,
            listOf(tx(45_000_00, 1), tx(-2_500_00, 2), tx(-10_000_00, 5), tx(-5_000_00, 6), tx(5_000_00, 6)),
        )
        assertEquals(45_000_00L, s.incomeMinor)
        assertEquals(2_500_00L, s.expensesMinor)
        assertEquals(10_000_00L, s.investmentsMinor)
        assertEquals(32_500_00L, s.netCashFlowMinor)
    }

    @Test fun `uncategorised spend counts as uncertain expense`() {
        val s = BudgetEngine.summarize(cats, listOf(tx(-450_00, null), tx(-100_00, 4)))
        assertEquals(2, s.uncertainCount)
        assertEquals(550_00L, s.uncertainMinor)
        assertEquals(550_00L, s.expensesMinor)
    }

    @Test fun `reimbursement lowers category spend`() {
        val s = BudgetEngine.summarize(cats, listOf(tx(-1_600_00, 3), tx(400_00, 3)))
        assertEquals(1_200_00L, s.lines.first { it.category.id == 3L }.actualMinor)
    }

    @Test fun `remaining planned outflow ignores overspent categories`() {
        // groceries 6000 budget, spent 7000 -> 0 remaining; eating 2000 budget, 500 spent -> 1500; invest 10000 -> 10000
        val s = BudgetEngine.summarize(cats, listOf(tx(-7_000_00, 2), tx(-500_00, 3)))
        assertEquals(11_500_00L, s.remainingPlannedOutflowMinor)
        assertTrue(s.lines.first { it.category.id == 2L }.isOverBudget)
    }

    @Test fun `reserve levels`() {
        assertEquals(ReserveLevel.HEALTHY, ReserveCalculator.status(30_000_00, 5_000_00, 20_000_00).level)
        assertEquals(ReserveLevel.AT_RISK, ReserveCalculator.status(20_000_00, 5_000_00, 20_000_00).level)
        assertEquals(ReserveLevel.BREACHED, ReserveCalculator.status(4_000_00, 5_000_00, 0).level)
        assertEquals(5_000_00L, ReserveCalculator.status(30_000_00, 5_000_00, 20_000_00).safeToSpendMinor)
    }

    @Test fun `account balance applies only transactions after the anchor`() {
        val acc = Account(1, "Main", AccountType.BANK, anchorBalanceMinor = 10_000_00, anchorDateMillis = 1_000)
        val txs = listOf(
            Transaction(timestampMillis = 500, amountMinor = -999_00, merchant = "old", accountId = 1),
            Transaction(timestampMillis = 2_000, amountMinor = -1_000_00, merchant = "new", accountId = 1),
            Transaction(timestampMillis = 3_000, amountMinor = -50_00, merchant = "other", accountId = 2),
        )
        assertEquals(9_000_00L, ReserveCalculator.balanceOf(acc, txs))
    }
}
