package io.github.joey7500.vaultflow.domain.budget

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetPeriodTest {
    private val payday = PeriodSettings(startDay = 15, incomeGraceDays = 3)

    @Test fun `calendar month by default`() {
        val p = BudgetPeriods.containing(LocalDate.of(2026, 11, 11), PeriodSettings())
        assertEquals(LocalDate.of(2026, 11, 1), p.start)
        assertEquals(LocalDate.of(2026, 12, 1), p.endExclusive)
        assertEquals("November 2026", p.label(Locale.ENGLISH))
    }

    @Test fun `payday period before and after the start day`() {
        val before = BudgetPeriods.containing(LocalDate.of(2026, 11, 11), payday)
        assertEquals(LocalDate.of(2026, 10, 15), before.start)
        assertEquals(LocalDate.of(2026, 11, 14), before.lastDay)
        val after = BudgetPeriods.containing(LocalDate.of(2026, 11, 20), payday)
        assertEquals(LocalDate.of(2026, 11, 15), after.start)
        assertEquals("15 Nov – 14 Dec 2026", after.label(Locale.ENGLISH))
    }

    @Test fun `rent at month end and salary on the 15th fall into the same period`() {
        val p = BudgetPeriods.containing(LocalDate.of(2026, 11, 15), payday)
        assertTrue(BudgetPeriods.belongsTo(LocalDate.of(2026, 11, 15), isIncome = true, p, payday))
        assertTrue(BudgetPeriods.belongsTo(LocalDate.of(2026, 11, 30), isIncome = false, p, payday))
    }

    @Test fun `salary paid early on Friday the 13th funds the next period`() {
        val next = BudgetPeriods.containing(LocalDate.of(2026, 11, 15), payday)
        val previous = BudgetPeriods.shift(next, -1, payday)
        val friday = LocalDate.of(2026, 11, 13)
        assertTrue(BudgetPeriods.belongsTo(friday, isIncome = true, next, payday))
        assertFalse(BudgetPeriods.belongsTo(friday, isIncome = true, previous, payday))
        // ...but a coffee on that Friday still belongs to the old period.
        assertTrue(BudgetPeriods.belongsTo(friday, isIncome = false, previous, payday))
        assertEquals(LocalDate.of(2026, 11, 12), BudgetPeriods.queryRange(next, payday).first)
    }

    @Test fun `shift handles month lengths`() {
        val p = BudgetPeriods.containing(LocalDate.of(2027, 1, 20), PeriodSettings(startDay = 28))
        assertEquals(LocalDate.of(2026, 12, 28), p.start)
        assertEquals(LocalDate.of(2027, 1, 28), BudgetPeriods.shift(p, 1, PeriodSettings(startDay = 28)).start)
        assertEquals(LocalDate.of(2027, 2, 28), BudgetPeriods.shift(p, 2, PeriodSettings(startDay = 28)).start)
    }
}
