package io.github.joey7500.vaultflow.domain.model

import io.github.joey7500.vaultflow.domain.Money

/** Template used to seed a fresh vault. Budgets are generic starting points, edit them in the app. */
data class CategoryTemplate(
    val key: String,
    val name: String,
    val group: String,
    val kind: FlowKind,
    val budgetMinor: Long,
)

object DefaultCategories {
    val all: List<CategoryTemplate> = listOf(
        CategoryTemplate(CategoryKeys.SALARY, "Salary", "Income", FlowKind.INCOME, Money.ofUnits(0L)),
        CategoryTemplate(CategoryKeys.GIFTS, "Gifts", "Income", FlowKind.INCOME, 0),
        CategoryTemplate(CategoryKeys.OTHER_INCOME, "Other income", "Income", FlowKind.INCOME, 0),

        CategoryTemplate(CategoryKeys.RENT, "Rent / Mortgage", "Fixed", FlowKind.EXPENSE, 0),
        CategoryTemplate(CategoryKeys.SUBSCRIPTIONS, "Subscriptions", "Fixed", FlowKind.EXPENSE, 0),

        CategoryTemplate(CategoryKeys.GROCERIES, "Groceries", "Food", FlowKind.EXPENSE, 0),
        CategoryTemplate(CategoryKeys.EATING_OUT, "Eating out", "Food", FlowKind.EXPENSE, 0),

        CategoryTemplate(CategoryKeys.TRAVELLING, "Travelling", "Lifestyle", FlowKind.EXPENSE, 0),
        CategoryTemplate(CategoryKeys.FUN, "Fun & hobbies", "Lifestyle", FlowKind.EXPENSE, 0),
        CategoryTemplate(CategoryKeys.HOLIDAY, "Holiday", "Lifestyle", FlowKind.EXPENSE, 0),

        CategoryTemplate(CategoryKeys.OTHER_EXPENSES, "Other expenses", "Other", FlowKind.EXPENSE, 0),
        CategoryTemplate(CategoryKeys.UNCERTAIN, "Uncertain", "Other", FlowKind.EXPENSE, 0),

        CategoryTemplate(CategoryKeys.INVESTMENTS, "Investments", "Wealth", FlowKind.INVESTMENT, 0),
        CategoryTemplate(CategoryKeys.INTERNAL_TRANSFER, "Internal transfer", "Transfers", FlowKind.TRANSFER, 0),
    )
}
