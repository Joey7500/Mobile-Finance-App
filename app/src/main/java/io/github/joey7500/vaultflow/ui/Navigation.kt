package io.github.joey7500.vaultflow.ui

import androidx.compose.runtime.compositionLocalOf

/** What a drill-down list shows. */
sealed interface TxFilter {
    val title: String

    data class Category(val categoryId: Long, override val title: String, val isUncertain: Boolean = false) : TxFilter
    data class Kind(val kind: KindFilter) : TxFilter {
        override val title: String get() = kind.label
    }
}

enum class KindFilter(val label: String) { INCOME("Income"), SPENT("Spent"), INVESTED("Invested") }

/** Full-screen detail pages opened on top of the tabs. */
sealed interface Detail {
    data class Transactions(val filter: TxFilter, val periodOffset: Int) : Detail
    data class AccountDetail(val accountId: Long) : Detail
}

/** Opens a detail page; back (gesture or arrow) closes it. */
fun interface Navigator {
    fun open(detail: Detail)
}

val LocalNavigator = compositionLocalOf<Navigator> { Navigator { } }
