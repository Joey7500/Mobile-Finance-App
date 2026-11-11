package io.github.joey7500.vaultflow.domain.budget

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How budget periods are cut.
 *
 * Calendar months (startDay = 1) suit people paid on the 1st. If your salary
 * arrives mid-month, start the period on PAYDAY instead: the salary then funds
 * the same period's rent and spending, and the reserve watchdog doesn't panic
 * in the days before payday.
 */
data class PeriodSettings(
    /** Day of month a period starts (1..28). */
    val startDay: Int = 1,
    /**
     * Income (salary, gifts) arriving up to this many days BEFORE the start
     * counts toward the new period, so a salary paid early (weekend, holiday)
     * still funds the period it is meant for.
     */
    val incomeGraceDays: Int = 0,
) {
    val safeStartDay: Int get() = startDay.coerceIn(1, 28)
    val safeGraceDays: Int get() = incomeGraceDays.coerceIn(0, 10)
}

data class BudgetPeriod(val start: LocalDate, val endExclusive: LocalDate) {
    val lastDay: LocalDate get() = endExclusive.minusDays(1)

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    /** "November 2026" for calendar months, "15 Nov – 14 Dec 2026" otherwise. */
    fun label(locale: Locale = Locale.getDefault()): String =
        if (start.dayOfMonth == 1) {
            start.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.uppercase() }
        } else {
            val f = DateTimeFormatter.ofPattern("d MMM", locale)
            "${start.format(f)} – ${lastDay.format(f)} ${lastDay.year}"
        }
}

object BudgetPeriods {

    fun containing(date: LocalDate, s: PeriodSettings): BudgetPeriod {
        val day = s.safeStartDay
        val start = if (date.dayOfMonth >= day) date.withDayOfMonth(day) else date.minusMonths(1).withDayOfMonth(day)
        return BudgetPeriod(start, start.plusMonths(1))
    }

    fun shift(period: BudgetPeriod, by: Int, s: PeriodSettings): BudgetPeriod =
        containing(period.start.plusMonths(by.toLong()), s)

    /** The date a transaction is counted on: income may be pulled forward into the next period. */
    fun effectiveDate(date: LocalDate, isIncome: Boolean, s: PeriodSettings): LocalDate =
        if (isIncome) date.plusDays(s.safeGraceDays.toLong()) else date

    fun belongsTo(date: LocalDate, isIncome: Boolean, period: BudgetPeriod, s: PeriodSettings): Boolean =
        period.contains(effectiveDate(date, isIncome, s))

    /** Dates to load from the database for a period (includes early income). */
    fun queryRange(period: BudgetPeriod, s: PeriodSettings): Pair<LocalDate, LocalDate> =
        period.start.minusDays(s.safeGraceDays.toLong()) to period.endExclusive
}
