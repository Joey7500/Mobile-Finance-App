package io.github.joey7500.vaultflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.joey7500.vaultflow.VaultFlowApp
import io.github.joey7500.vaultflow.di.AppContainer
import io.github.joey7500.vaultflow.ui.accounts.AccountDetailScreen
import io.github.joey7500.vaultflow.ui.accounts.AccountsScreen
import io.github.joey7500.vaultflow.ui.budget.BudgetScreen
import io.github.joey7500.vaultflow.ui.detail.TransactionListScreen
import io.github.joey7500.vaultflow.ui.settings.SettingsScreen
import io.github.joey7500.vaultflow.ui.theme.VaultColors
import io.github.joey7500.vaultflow.ui.wealth.WealthScreen

/** Snackbar host shared by all tabs. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as VaultFlowApp).container

/** ViewModel bound to the current [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}

private enum class Tab(val label: String) { BUDGET("Budget"), ACCOUNTS("Accounts"), WEALTH("Wealth"), SETTINGS("Settings") }

@Composable
fun AppShell(onRestartRequested: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Stack of opened detail pages (category lists, account detail); back pops one.
    val details = remember { mutableStateListOf<Detail>() }
    val navigator = remember { Navigator { details.add(it) } }
    BackHandler(enabled = details.isNotEmpty()) { details.removeAt(details.lastIndex) }
    val snackbar = remember { SnackbarHostState() }
    val isDemo = appContainer().isDemo

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalNavigator provides navigator) {
        Scaffold(
            containerColor = VaultColors.Background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = VaultColors.Surface) {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i; details.clear() },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.BUDGET -> Icons.Filled.DateRange
                                        Tab.ACCOUNTS -> Icons.Filled.AccountBox
                                        Tab.WEALTH -> Icons.Filled.Star
                                        Tab.SETTINGS -> Icons.Filled.Settings
                                    },
                                    contentDescription = t.label,
                                )
                            },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = VaultColors.SurfaceHigh),
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (isDemo) {
                    Text(
                        "DEMO MODE · synthetic data · your real vault is untouched",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColors.Background,
                        modifier = Modifier.fillMaxWidth().background(VaultColors.Amber).padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                Box(Modifier.fillMaxSize()) {
                    val top = details.lastOrNull()
                    when {
                        top is Detail.Transactions -> TransactionListScreen(top) { details.removeAt(details.lastIndex) }
                        top is Detail.AccountDetail -> AccountDetailScreen(top.accountId) { details.removeAt(details.lastIndex) }
                        else -> when (Tab.entries[tab]) {
                            Tab.BUDGET -> BudgetScreen()
                            Tab.ACCOUNTS -> AccountsScreen()
                            Tab.WEALTH -> WealthScreen()
                            Tab.SETTINGS -> SettingsScreen(onRestartRequested)
                        }
                    }
                }
            }
        }
    }
}
