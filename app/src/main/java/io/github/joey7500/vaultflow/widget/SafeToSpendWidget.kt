package io.github.joey7500.vaultflow.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.joey7500.vaultflow.MainActivity
import io.github.joey7500.vaultflow.VaultFlowApp
import io.github.joey7500.vaultflow.domain.Money
import io.github.joey7500.vaultflow.domain.budget.BudgetEngine
import io.github.joey7500.vaultflow.domain.budget.ReserveCalculator
import io.github.joey7500.vaultflow.domain.budget.ReserveLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the widget shows; computed with the same rules as the Budget tab. */
data class WidgetSnapshot(val safeToSpendMinor: Long?, val level: ReserveLevel?, val periodLabel: String, val showAmount: Boolean)

/** Home-screen widget: "safe to spend or invest" right now. Tapping it opens the (locked) app. */
class SafeToSpendWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = runCatching { load(context) }.getOrNull()
        provideContent { Content(snapshot) }
    }

    private suspend fun load(context: Context): WidgetSnapshot {
        val c = (context.applicationContext as VaultFlowApp).container
        c.initialize()
        val view = c.finance.periodView(0).first()
        val accounts = c.finance.accounts.first()
        val balances = c.finance.accountBalances.first()
        val floor = c.finance.reserveFloorMinor.first()
        val summary = BudgetEngine.summarize(view.categories, view.transactions)
        val status = if (accounts.isEmpty()) {
            null
        } else {
            ReserveCalculator.status(ReserveCalculator.liquidTotal(accounts, balances), floor, summary.remainingPlannedOutflowMinor)
        }
        return WidgetSnapshot(status?.safeToSpendMinor, status?.level, view.period.label(), c.prefs.widgetShowAmount)
    }

    @androidx.compose.runtime.Composable
    private fun Content(s: WidgetSnapshot?) {
        val accent = when (s?.level) {
            ReserveLevel.HEALTHY -> Color(0xFF34D399)
            ReserveLevel.AT_RISK -> Color(0xFFFBBF24)
            ReserveLevel.BREACHED -> Color(0xFFF87171)
            null -> Color(0xFF9CA3AF)
        }
        val label = when (s?.level) {
            ReserveLevel.HEALTHY -> "Healthy"
            ReserveLevel.AT_RISK -> "At risk"
            ReserveLevel.BREACHED -> "Below floor"
            null -> "Open VaultFlow"
        }
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF121826))
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            Text("SAFE TO SPEND", style = TextStyle(color = ColorProvider(Color(0xFF9CA3AF)), fontSize = 11.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(2.dp))
            val amount = when {
                s?.safeToSpendMinor == null -> "—"
                !s.showAmount -> "••••• Kč"
                else -> Money.format(s.safeToSpendMinor)
            }
            Text(amount, style = TextStyle(color = ColorProvider(Color(0xFFE5E7EB)), fontSize = 24.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(2.dp))
            Text(
                label + (s?.periodLabel?.let { " · $it" } ?: ""),
                style = TextStyle(color = ColorProvider(accent), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }

    companion object {
        /** Refresh all placed widgets (after a captured payment, sync, or leaving the app). */
        fun refresh(context: Context) {
            val c = (context.applicationContext as VaultFlowApp).container
            c.scope.launch { runCatching { SafeToSpendWidget().updateAll(context.applicationContext) } }
        }
    }
}

class SafeToSpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SafeToSpendWidget()
}
