package io.github.joey7500.vaultflow.core.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-sensitive flags that must be readable before the encrypted DB is opened. */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEMO, value).commit()
        }

    /** Off by default: FLAG_SECURE hides balances from screenshots and the Recents preview. */
    var allowScreenshots: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOTS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SCREENSHOTS, value).apply()
        }

    /** Show the amount on the home-screen widget (off = only the Healthy / At risk status). */
    var widgetShowAmount: Boolean
        get() = prefs.getBoolean(KEY_WIDGET_AMOUNT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIDGET_AMOUNT, value).apply()
        }

    private val budgetShowTx = MutableStateFlow(prefs.getBoolean(KEY_BUDGET_SHOW_TX, true))
    private val budgetTxExpanded = MutableStateFlow(prefs.getBoolean(KEY_BUDGET_TX_EXPANDED, true))

    /** Show the Transactions section on the Budget tab at all (Settings → Budget tab). */
    val budgetShowTransactions: StateFlow<Boolean> = budgetShowTx.asStateFlow()

    /** Transactions section on the Budget tab expanded or collapsed; survives restarts. */
    val budgetTransactionsExpanded: StateFlow<Boolean> = budgetTxExpanded.asStateFlow()

    fun setBudgetShowTransactions(show: Boolean) {
        prefs.edit().putBoolean(KEY_BUDGET_SHOW_TX, show).apply()
        budgetShowTx.value = show
    }

    fun setBudgetTransactionsExpanded(expanded: Boolean) {
        prefs.edit().putBoolean(KEY_BUDGET_TX_EXPANDED, expanded).apply()
        budgetTxExpanded.value = expanded
    }

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()
        }

    private companion object {
        const val KEY_DEMO = "demo_mode"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_SCREENSHOTS = "allow_screenshots"
        const val KEY_WIDGET_AMOUNT = "widget_show_amount"
        const val KEY_BUDGET_SHOW_TX = "budget_show_transactions"
        const val KEY_BUDGET_TX_EXPANDED = "budget_transactions_expanded"
    }
}
